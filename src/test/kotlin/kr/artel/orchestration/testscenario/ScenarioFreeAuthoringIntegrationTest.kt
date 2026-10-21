package kr.artel.orchestration.testscenario

import org.springframework.r2dbc.core.DatabaseClient
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.deleteQaRuns
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testrun.service.RunScenarioReader
import kr.artel.orchestration.testrun.service.TestRunChatService
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioExecutionMode
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.InvalidFreeScenarioException
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * ARTEL-1028 — 작성 챗봇이 FREE 시나리오를 만들고 고친다. reconcile 이 `execution_mode` 를 저장하고,
 * 값이 없으면 새 시나리오는 ORDERED, 수정은 저장된 값을 유지하며, FREE step 을 고쳐 쓰지 않고, 규칙을
 * 어긴 FREE 는 한 줄도 저장하지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ScenarioFreeAuthoringIntegrationTest {

    @Autowired private lateinit var reconcileService: ScenarioReconcileService
    @Autowired private lateinit var chatService: TestRunChatService
    @Autowired private lateinit var runScenarioReader: RunScenarioReader
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var projectId: Long = 0
    private var ownerId: Long = 0
    private var runId: Long = 0
    private var firstCaseId: Long = 0
    private var secondCaseId: Long = 0

    @BeforeEach
    fun seed(): Unit = runBlocking {
        wipe()
        val now = Instant.now()
        ownerId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "1028", login = "free-author",
                displayName = "free-author", avatarUrl = null, email = "free-author@example.com"
            )
        )!!.userId.toLong()
        projectId = requireNotNull(
            projectRepository.save(
                ProjectEntity(name = "free-p", genre = "ACTION", createdAt = now, updatedAt = now)
            ).id
        )
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now
            )
        )
        firstCaseId = saveCase("상점", "구매 버튼을 누른다")
        secondCaseId = saveCase("전투", "공격 버튼을 누른다")
        runId = requireNotNull(runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id)
    }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        // 남의 클래스가 남긴 qa_run 이 아래 삭제를 FK 로 막지 않게 먼저 비운다(ARTEL-1163).
        db.deleteQaRuns()
        runScenarioRepository.deleteAll()
        runRepository.deleteAll()
        scenarioRepository.deleteAll()
        testCaseRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `FREE 결과는 새 시나리오로 저장되고 step 이 그대로 남는다`(): Unit = runBlocking {
        val outcome = reconcileService.reconcile(
            runId, projectId, ownerId,
            listOf(freeResult(scenarioId = null, firstCaseId, secondCaseId)),
        )

        assertThat(outcome.applied).isEqualTo(1)
        assertThat(outcome.checked.single().executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        val savedId = outcome.saved.single().scenarioId
        val stored = storedDraft(savedId)
        assertThat(stored.executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        // 브리지·시작 안내·조작 채우기가 끼어들지 않았다 — step 하나가 TC 하나다.
        assertThat(stored.steps.map { it.caseId }).containsExactly(firstCaseId, secondCaseId)
        assertThat(stored.steps.map { it.action }).containsExactly("", "")
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().map { it.testScenarioId })
            .containsExactly(savedId)
    }

    @Test
    fun `execution_mode 가 없는 새 결과는 ORDERED 로 저장된다`(): Unit = runBlocking {
        val outcome = reconcileService.reconcile(
            runId, projectId, ownerId,
            listOf(
                ScenarioResult(
                    title = "상점", description = "구매",
                    steps = listOf(ChatScenarioStep(action = "구매 버튼을 누른다", caseId = firstCaseId)),
                )
            ),
        )

        assertThat(storedDraft(outcome.saved.single().scenarioId).executionMode)
            .isEqualTo(ScenarioExecutionMode.ORDERED)
    }

    @Test
    fun `execution_mode 가 없는 수정은 저장된 FREE 를 유지한다`(): Unit = runBlocking {
        val scenarioId = saveLinked(freeDraft(firstCaseId))

        val outcome = reconcileService.reconcile(
            runId, projectId, ownerId,
            listOf(freeResult(scenarioId, firstCaseId, secondCaseId).copy(executionMode = null)),
        )

        assertThat(outcome.applied).isEqualTo(1)
        assertThat(outcome.checked.single().executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        val stored = storedDraft(scenarioId)
        assertThat(stored.executionMode).isEqualTo(ScenarioExecutionMode.FREE)
        assertThat(stored.steps.map { it.caseId }).containsExactly(firstCaseId, secondCaseId)
    }

    @Test
    fun `수정이 다른 execution_mode 를 말하면 모드가 바뀐다`(): Unit = runBlocking {
        val freeId = saveLinked(freeDraft(firstCaseId))
        val orderedId = saveLinked(
            ScenarioDraft(
                title = "전투", description = "",
                steps = listOf(ScenarioStep(action = "공격 버튼을 누른다", caseId = secondCaseId)),
            ),
            position = 1,
        )

        reconcileService.reconcile(
            runId, projectId, ownerId,
            listOf(
                ScenarioResult(
                    scenarioId = freeId, title = "묶음", description = "",
                    steps = listOf(ChatScenarioStep(action = "구매 버튼을 누른다", caseId = firstCaseId)),
                    executionMode = ScenarioExecutionMode.ORDERED,
                ),
                freeResult(orderedId, secondCaseId).copy(title = "전투"),
            ),
        )

        assertThat(storedDraft(freeId).executionMode).isEqualTo(ScenarioExecutionMode.ORDERED)
        assertThat(storedDraft(orderedId).executionMode).isEqualTo(ScenarioExecutionMode.FREE)
    }

    @Test
    fun `규칙을 어긴 FREE 는 400 으로 거절되고 아무것도 저장하지 않는다`(): Unit = runBlocking {
        val scenarioId = saveLinked(freeDraft(firstCaseId))

        assertThatThrownBy {
            runBlocking {
                reconcileService.reconcile(
                    runId, projectId, ownerId,
                    listOf(
                        ScenarioResult(title = "새 묶음", description = "", steps = listOf(caseStep(secondCaseId))),
                        freeResult(scenarioId, firstCaseId, firstCaseId),
                    ),
                )
            }
        }.isInstanceOf(InvalidFreeScenarioException::class.java)
            .extracting("code").isEqualTo("invalid_free_scenario")

        assertThat(storedDraft(scenarioId).steps.map { it.caseId }).containsExactly(firstCaseId)
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()).hasSize(1)
    }

    @Test
    fun `검수만 하는 턴도 규칙을 어긴 FREE 를 거절한다`(): Unit = runBlocking {
        assertThatThrownBy {
            runBlocking {
                reconcileService.reconcile(
                    runId, projectId, ownerId,
                    listOf(
                        freeResult(null, firstCaseId).copy(
                            steps = listOf(ChatScenarioStep(action = "case 없이", stepSource = ScenarioStepSource.HUMAN)),
                        )
                    ),
                    save = false,
                )
            }
        }.isInstanceOf(InvalidFreeScenarioException::class.java)
    }

    @Test
    fun `FREE 가 든 런에서도 카드 커밋이 저장된다`(): Unit = runBlocking {
        val scenarioId = saveLinked(freeDraft(firstCaseId))

        val applied = chatService.commitScenarios(
            ownerId, runId, listOf(freeResult(scenarioId, firstCaseId, secondCaseId).copy(executionMode = null)),
        )

        assertThat(applied).isEqualTo(1)
        assertThat(storedDraft(scenarioId).steps.map { it.caseId }).containsExactly(firstCaseId, secondCaseId)
        assertThat(storedDraft(scenarioId).executionMode).isEqualTo(ScenarioExecutionMode.FREE)
    }

    @Test
    fun `current_scenarios 와 제안은 execution_mode 를 싣는다`(): Unit = runBlocking {
        val freeId = saveLinked(freeDraft(firstCaseId))

        val current = runScenarioReader.currentScenarios(runId)
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(current))[0]["execution_mode"].asText())
            .isEqualTo("FREE")

        val proposals = reconcileService.withExecutionModes(
            runId, projectId,
            listOf(
                ScenarioResult(scenarioId = freeId, title = "묶음"),
                ScenarioResult(title = "묶음"),
                ScenarioResult(title = "새것"),
                ScenarioResult(title = "새것", executionMode = ScenarioExecutionMode.FREE),
            ),
        )
        assertThat(proposals.map { it.executionMode }).containsExactly(
            ScenarioExecutionMode.FREE,
            ScenarioExecutionMode.FREE,
            ScenarioExecutionMode.ORDERED,
            ScenarioExecutionMode.FREE,
        )
    }

    // ----------------------------------------------------------------- helpers

    private fun caseStep(caseId: Long) =
        ChatScenarioStep(action = "", caseId = caseId, stepSource = ScenarioStepSource.CASE)

    private fun freeResult(scenarioId: Long?, vararg caseIds: Long) = ScenarioResult(
        scenarioId = scenarioId,
        title = "묶음",
        description = "TC 묶음",
        steps = caseIds.map(::caseStep),
        executionMode = ScenarioExecutionMode.FREE,
    )

    private fun freeDraft(vararg caseIds: Long) = ScenarioDraft(
        title = "묶음",
        description = "TC 묶음",
        steps = caseIds.map { ScenarioStep(action = "", caseId = it) },
        executionMode = ScenarioExecutionMode.FREE,
    )

    private suspend fun saveCase(scene: String, step: String): Long = requireNotNull(
        testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = scene, step = step, expectedValue = "$step 결과")
        ).id
    )

    private suspend fun saveLinked(draft: ScenarioDraft, position: Int = 0): Long {
        val scenarioId = requireNotNull(
            scenarioRepository.save(TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)).id
        )
        runScenarioRepository.save(
            TestRunScenarioEntity(testRunId = runId, testScenarioId = scenarioId, position = position)
        )
        return scenarioId
    }

    private suspend fun storedDraft(scenarioId: Long): ScenarioDraft =
        requireNotNull(scenarioRepository.findById(scenarioId)).toDraft(objectMapper)
}
