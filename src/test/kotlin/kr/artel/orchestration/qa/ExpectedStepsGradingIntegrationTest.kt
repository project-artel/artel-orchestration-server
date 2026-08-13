package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaTryScoreRepository
import kr.artel.orchestration.qa.service.EXPECTED_STEPS_GRADER
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * 기대 판정 라벨과 에이전트의 자기채점을 대조하는 결정적 채점자(ARTEL-301).
 *
 * 여기서 지키는 것은 숫자가 맞느냐보다 **무엇을 무엇과 섞지 않느냐**다: 오탐과 미탐은 접히지 않고,
 * 미보고는 일치도 불일치도 아니며, 라벨 없는 스텝은 분모에 들지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ExpectedStepsGradingIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var failureService: QaExecutionFailureService
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var scoreRepository: QaTryScoreRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking { wipe() }

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        scoreRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        scenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `혼동행렬 네 칸이 각각 세어진다`(): Unit = runBlocking {
        // 기대: 통과 / 실패 / 통과 / 실패
        val run = runningRun(listOf(true, false, true, false))

        // 보고: 통과 / 실패 / 실패 / 통과
        //   1 기대통과·보고통과 = 정답
        //   2 기대실패·보고실패 = 정답
        //   3 기대통과·보고실패 = 오탐 (멀쩡한 것을 실패라 함)
        //   4 기대실패·보고통과 = 미탐 (실패해야 할 것을 통과라 함)
        reportStep(run.tryId, step = 1, passed = true)
        reportStep(run.tryId, step = 2, passed = false)
        reportStep(run.tryId, step = 3, passed = false)
        reportStep(run.tryId, step = 4, passed = true)
        router.handle(terminalFrame(run.tryId))

        val matrix = detailOf(run.tryId).get("matrix")
        assertThat(matrix.get("correct_pass").asInt()).isEqualTo(1)
        assertThat(matrix.get("correct_fail").asInt()).isEqualTo(1)
        // 이 둘을 하나로 접으면 미탐이 많은 모델과 오탐이 많은 모델이 같은 점수로 보인다.
        assertThat(matrix.get("false_alarm").asInt()).isEqualTo(1)
        assertThat(matrix.get("miss").asInt()).isEqualTo(1)
    }

    @Test
    fun `보고되지 않은 스텝은 일치로도 불일치로도 세어지지 않는다`(): Unit = runBlocking {
        val run = runningRun(listOf(true, true, true))
        // 두 번째까지만 판정하고 런이 끝났다 — 세 번째는 판정 자체가 없다.
        reportStep(run.tryId, step = 1, passed = true)
        reportStep(run.tryId, step = 2, passed = true)
        router.handle(terminalFrame(run.tryId))

        val detail = detailOf(run.tryId)
        val matrix = detail.get("matrix")
        assertThat(detail.get("labeled_steps").asInt()).isEqualTo(3)
        assertThat(detail.get("reported").asInt()).isEqualTo(2)
        assertThat(detail.get("unreported").asInt()).isEqualTo(1)
        // 일치로 세면 일찍 죽은 런이 만점이 되고, 불일치로 세면 죽었다는 사실이 이중 계산된다.
        assertThat(matrix.get("correct_pass").asInt()).isEqualTo(2)
        assertThat(matrix.get("false_alarm").asInt()).isEqualTo(0)
        assertThat(matrix.get("miss").asInt()).isEqualTo(0)
        assertThat(matrix.get("correct_fail").asInt()).isEqualTo(0)
    }

    @Test
    fun `라벨이 null인 스텝은 분모에서 빠진다`(): Unit = runBlocking {
        val run = runningRun(listOf(true, null, false))
        reportStep(run.tryId, step = 1, passed = true)
        reportStep(run.tryId, step = 2, passed = false)
        reportStep(run.tryId, step = 3, passed = false)
        router.handle(terminalFrame(run.tryId))

        val detail = detailOf(run.tryId)
        // 라벨 없는 2번은 보고됐지만 채점 대상이 아니다.
        assertThat(detail.get("labeled_steps").asInt()).isEqualTo(2)
        assertThat(detail.get("reported").asInt()).isEqualTo(2)
        assertThat(detail.get("expected").map { it.get("step").asInt() }).containsExactly(1, 3)
    }

    @Test
    fun `라벨이 하나도 없는 시나리오는 채점 행을 만들지 않는다`(): Unit = runBlocking {
        val run = runningRun(listOf(null, null))
        reportStep(run.tryId, step = 1, passed = true)
        router.handle(terminalFrame(run.tryId))

        // 빈 행렬을 남기면 "채점했는데 전부 0"과 "채점할 것이 없었다"가 같아진다.
        assertThat(scoreRepository.findByQaTryId(run.tryId).toList()).isEmpty()
    }

    @Test
    fun `라벨을 고쳐도 옛 점수의 기대 벡터 스냅샷은 그대로다`(): Unit = runBlocking {
        val run = runningRun(listOf(true, false))
        reportStep(run.tryId, step = 1, passed = true)
        reportStep(run.tryId, step = 2, passed = false)
        router.handle(terminalFrame(run.tryId))
        val before = detailOf(run.tryId).get("expected").toString()

        // 저작자가 나중에 라벨을 뒤집는다.
        saveLabels(run.scenarioId, listOf(false, true))

        // grader_version만으로는 무엇과 대조했는지 알 수 없다 — 라벨은 시나리오마다 다르기 때문이다.
        assertThat(detailOf(run.tryId).get("expected").toString()).isEqualTo(before)
        assertThat(before).contains("\"expected_passed\":true")
    }

    @Test
    fun `소켓이 죽어 실패한 런도 채점된다`(): Unit = runBlocking {
        val run = runningRun(listOf(true, true))
        reportStep(run.tryId, step = 1, passed = true)

        // 종단 STATUS 없이 연결이 끊긴 경로. 정상 종료에만 채점을 걸면 잘 죽는 모델의 최악 런이
        // 통째로 집계에서 빠져 그 모델이 실제보다 좋아 보인다.
        failureService.agentDisconnected(run.tryId)

        assertThat(qaTryRepository.findById(run.tryId)!!.status).isEqualTo("FAILED")
        assertThat(qaRunRepository.findById(run.runId)!!.status).isEqualTo("FAILED")
        val detail = detailOf(run.tryId)
        assertThat(detail.get("reported").asInt()).isEqualTo(1)
        assertThat(detail.get("unreported").asInt()).isEqualTo(1)
    }

    @Test
    fun `SDK 연결이 끊겨 마지막 try가 실패하면 부모 run도 완료된다`(): Unit = runBlocking {
        val run = runningRun(listOf(true))

        failureService.sdkDisconnected(run.instanceId)

        assertThat(qaTryRepository.findById(run.tryId)!!.status).isEqualTo("FAILED")
        assertThat(qaRunRepository.findById(run.runId)!!.status).isEqualTo("FAILED")
    }

    @Test
    fun `운영자가 취소한 런도 채점된다`(): Unit = runBlocking {
        val run = runningRun(listOf(false))
        reportStep(run.tryId, step = 1, passed = false)

        failureService.cancelled(run.tryId, "운영자가 중단했습니다.")

        assertThat(qaTryRepository.findById(run.tryId)!!.status).isEqualTo("CANCELLED")
        assertThat(qaRunRepository.findById(run.runId)!!.status).isEqualTo("CANCELLED")
        assertThat(detailOf(run.tryId).get("matrix").get("correct_fail").asInt()).isEqualTo(1)
    }

    @Test
    fun `채점이 실패해도 런은 정상 종료한다`(): Unit = runBlocking {
        val run = runningRun(listOf(true))
        // 시나리오 본문이 읽히지 않는 상태로 망가진다(손으로 고친 행, 계약 드리프트).
        // steps는 리스트여야 하는데 스칼라가 들어 있어 toDraft가 던진다.
        val broken = requireNotNull(scenarioRepository.findById(run.scenarioId))
        scenarioRepository.save(broken.copy(steps = Json.of("5")))

        router.handle(terminalFrame(run.tryId))

        // 채점은 사후 계산이고 입력은 qa_log에 그대로 남는다 — 런을 죽일 이유가 없다.
        assertThat(qaTryRepository.findById(run.tryId)!!.status).isEqualTo("COMPLETED")
        assertThat(scoreRepository.findByQaTryId(run.tryId).toList()).isEmpty()
    }

    @Test
    fun `같은 런을 두 번 채점해도 행은 하나다`(): Unit = runBlocking {
        val run = runningRun(listOf(true))
        reportStep(run.tryId, step = 1, passed = true)
        router.handle(terminalFrame(run.tryId))

        // 취소 직후 지각 도착한 종단 프레임처럼, 종료 경로가 겹쳐 두 번 불릴 수 있다.
        failureService.cancelled(run.tryId, "지각 취소")

        assertThat(scoreRepository.findByQaTryId(run.tryId).toList()).hasSize(1)
    }

    // ----------------------------------------------------------------- helpers

    private data class RunningRun(
        val runId: Long,
        val tryId: Long,
        val scenarioId: Long,
        val instanceId: Long
    )

    /** [labels] 길이만큼 스텝을 가진 시나리오 하나로 런을 열고 RUNNING까지 만든다. */
    private suspend fun runningRun(labels: List<Boolean?>): RunningRun {
        val owner = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "301", login = "grade",
                displayName = "grade", avatarUrl = null, email = "grade@example.com"
            )
        )!!
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "grade-p", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!, appUserId = ownerId,
                role = ProjectRole.OWNER.name, createdAt = now
            )
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!, name = "inst", platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(), createdAt = now, updatedAt = now
            )
        )!!
        val testRun = testRunRepository.save(TestRunEntity(projectId = project.id!!, name = "런"))
        val scenarioId = requireNotNull(
            scenarioRepository.save(
                TestScenarioEntity(projectId = project.id!!).withDraft(draftFor(labels), objectMapper)
            ).id
        )

        val started = persistence.createRunStarting(
            requireNotNull(testRun.id), instance.id!!, ownerId, listOf(scenarioId)
        )
        val tryId = requireNotNull(started.tries.first().id)
        persistence.attachRunAndMarkRunning(
            started.qaRun, tryId, "session-grade-1",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5"}""")
        )
        return RunningRun(requireNotNull(started.qaRun.id), tryId, scenarioId, instance.id!!)
    }

    private fun draftFor(labels: List<Boolean?>) = ScenarioDraft(
        title = "채점",
        description = "",
        steps = labels.mapIndexed { index, label ->
            ScenarioStep(action = "스텝 ${index + 1}", expectedPassed = label)
        }
    )

    private suspend fun saveLabels(scenarioId: Long, labels: List<Boolean?>) {
        val existing = requireNotNull(scenarioRepository.findById(scenarioId))
        scenarioRepository.save(existing.withDraft(draftFor(labels), objectMapper))
    }

    /** 스텝 판정 프레임: `result`가 없고 `step`이 있다 — 런을 끝내지 않는다. */
    private suspend fun reportStep(qaTryId: Long, step: Int, passed: Boolean) {
        router.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = "STATUS",
                qaTryId = qaTryId.toString(),
                timestamp = Instant.now(),
                payload = objectMapper.readTree(
                    """{"status":"${if (passed) "COMPLETED" else "FAILED"}","step":$step,
                        "message":"step $step"}"""
                )
            )
        )
    }

    private fun terminalFrame(qaTryId: Long) =
        QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "STATUS",
            qaTryId = qaTryId.toString(),
            timestamp = Instant.now(),
            payload = objectMapper.readTree(
                """{"status":"COMPLETED","result":"PASSED","message":"done"}"""
            )
        )

    private suspend fun detailOf(qaTryId: Long) =
        objectMapper.readTree(
            scoreRepository.findByQaTryId(qaTryId).toList()
                .single { it.grader == EXPECTED_STEPS_GRADER }
                .detail.asString()
        )
}
