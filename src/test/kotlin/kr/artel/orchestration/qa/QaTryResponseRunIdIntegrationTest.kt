package kr.artel.orchestration.qa

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
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
 * `qa_try.qa_run_id` 가 `QaTryResponse.qaRunId` 와 `QaRunResponse.tries[].qaRunId` 로 나가는지
 * (ARTEL-722). 화면이 try 화면에서 run 콘솔로 올라가는 링크는 이 값 하나로 만들어진다.
 *
 * 검증의 핵심은 두 try 를 나란히 세우는 것이다 — run 에 속한 try 는 문자열로 변환된 run id 를
 * 내고, `qa_run` 이 생기기 전의 단독 실행(하위호환) try 는 null 을 낸다. 둘 다 400/500 이 아니다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class QaTryResponseRunIdIntegrationTest {

    @Autowired private lateinit var tryService: QaTryService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService

    private var projectId: Long = 0
    private var userId: Long = 0
    private var scenarioId: Long = 0
    private var gameInstanceId: Long = 0

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    /**
     * 정리와 준비를 한 메서드에 두는 것은 JUnit이 같은 클래스의 `@BeforeEach` 사이 순서를
     * 보장하지 않기 때문이다(`KnowledgeGraphViewIntegrationTest`와 같은 이유).
     */
    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        userId = signIn().userId.toLong()
        val now = Instant.now()
        projectId = projectRepository.save(
            ProjectEntity(name = "qa-try-run-id", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = userId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        scenarioId = testScenarioRepository.save(TestScenarioEntity(projectId = projectId))!!.id!!
        gameInstanceId = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
    }

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되므로 FK 순서대로 직접 비운다
     * (`IssueIntegrationTest`와 같은 이유). qa_try 가 qa_run 을, qa_run 이 test_run 을 참조하므로
     * 그 역순으로 지운다.
     */
    private suspend fun wipe() {
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        testRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `run 에 속한 try 는 qaRunId 를 낸다`(): Unit = runBlocking {
        val qaRunId = seedQaRun()
        val qaTryId = seedQaTry(qaRunId = qaRunId)

        val response = tryService.get(qaTryId, userId)

        assertThat(response).isNotNull()
        assertThat(response!!.qaRunId).isEqualTo(qaRunId.toString())
    }

    @Test
    fun `qa_run 이 생기기 전의 단독 실행 try 는 qaRunId 가 null 이다`(): Unit = runBlocking {
        val qaTryId = seedQaTry(qaRunId = null)

        val response = tryService.get(qaTryId, userId)

        assertThat(response).isNotNull()
        assertThat(response!!.qaRunId).isNull()
    }

    @Test
    fun `listByProject 로 낸 목록도 run 소속과 단독을 함께 정확히 낸다`(): Unit = runBlocking {
        val qaRunId = seedQaRun()
        val runTryId = seedQaTry(qaRunId = qaRunId)
        val standaloneTryId = seedQaTry(qaRunId = null)

        val byId = tryService.listByProject(projectId, userId, size = 50).associateBy { it.id }

        assertThat(byId.getValue(runTryId.toString()).qaRunId).isEqualTo(qaRunId.toString())
        assertThat(byId.getValue(standaloneTryId.toString()).qaRunId).isNull()
    }

    /**
     * `QaRunResponse.tries` 는 `QaTryResponse` 와 같은 `toResponse()` 를 거치므로 여기서도
     * qaRunId 가 문자열로 실린다 — Jira AC 의 두 번째 줄이 요구하는 것과 같은 값이다.
     */
    @Test
    fun `QaRunResponse의 tries 각 항목에도 자신이 속한 qaRunId 가 실린다`(): Unit = runBlocking {
        val qaRunId = seedQaRun()
        seedQaTry(qaRunId = qaRunId)

        val response = tryService.getRun(qaRunId, userId)

        assertThat(response).isNotNull()
        assertThat(response!!.tries).isNotEmpty()
        assertThat(response.tries).allSatisfy {
            assertThat(it.qaRunId).isEqualTo(qaRunId.toString())
        }
    }

    // --- helpers ---

    private suspend fun seedQaRun(): Long {
        val now = Instant.now()
        val testRun = testRunRepository.save(TestRunEntity(projectId = projectId, name = "런"))!!
        return qaRunRepository.save(
            QaRunEntity(
                testRunId = testRun.id!!,
                gameInstanceId = gameInstanceId,
                startedBy = userId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now
            )
        )!!.id!!
    }

    private suspend fun seedQaTry(qaRunId: Long?): Long {
        val now = Instant.now()
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = gameInstanceId,
                qaRunId = qaRunId,
                startedBy = userId,
                status = "COMPLETED",
                startedAt = now,
                completedAt = now
            )
        )!!.id!!
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "qa-try-run-id",
                login = "qa-try-run-id",
                displayName = "qa-try-run-id",
                avatarUrl = null,
                email = "qa-try-run-id@example.com"
            )
        )!!
}
