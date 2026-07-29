package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Duration
import java.time.Instant
import java.util.UUID

/**
 * issue 도메인 통합 테스트: 이슈는 QA 인바운드 라우터([QaAgentInboundRouter])가 Agent 프레임을
 * 받아 저장하는 내부 도메인이므로, 그 저장 경로만 검증한다(사용자 조회 API는 없다). payload(JSONB)
 * 때문에 실제 PostgreSQL을 쓴다.
 *
 * 검증: ISSUE 프레임 저장(severity/title/detail), 재전송 멱등, 잘못된 severity는 저장 안 함.
 */
@ActiveProfiles("test")
@SpringBootTest
class IssueIntegrationTest {

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     * 테스트 후에도 반드시 비운다 — qa_try는 game_instance/test_scenario를 하드 FK로 참조하므로,
     * 남겨두면 이 도메인을 모르는 다른 테스트의 game_instance/project 삭제가 FK로 깨진다.
     */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `persists an agent-reported issue with severity, title and full payload as detail`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(
            qaTryId,
            messageId = UUID.randomUUID().toString(),
            severity = "MAJOR",
            title = "Save button does nothing on the pause menu",
            extra = """"detail":"Clicking Save shows no feedback and no file is written","step":3"""
        )

        val issues = issueRepository.findAll().toList()
        assertThat(issues).hasSize(1)
        val issue = issues.single()
        assertThat(issue.severity).isEqualTo("MAJOR")
        assertThat(issue.title).isEqualTo("Save button does nothing on the pause menu")
        val detail = objectMapper.readTree(issue.detail.asString())
        assertThat(detail["step"].asInt()).isEqualTo(3)
        assertThat(detail["detail"].asText()).contains("no file is written")
        // reported_at은 서버 수신 시각이 아니라 Agent가 프레임에 찍은 이벤트 시각을 그대로 보존한다.
        assertThat(issue.reportedAt).isEqualTo(REPORTED_AT)
    }

    @Test
    fun `is idempotent on message id so a re-delivered frame does not duplicate`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)
        val messageId = UUID.randomUUID().toString()

        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")
        deliver(qaTryId, messageId, "CRITICAL", "Crash on level load")

        assertThat(issueRepository.findAll().toList()).hasSize(1)
    }

    @Test
    fun `drops a frame whose severity is not on the ladder without persisting`(): Unit = runBlocking {
        val owner = signIn("42", "octocat")
        val qaTryId = seedRunningQaTry(owner)

        deliver(qaTryId, UUID.randomUUID().toString(), severity = "SEVERE", title = "Unknown severity")

        assertThat(issueRepository.findAll().toList()).isEmpty()
    }

    // --- helpers ---

    private suspend fun deliver(
        qaTryId: Long,
        messageId: String,
        severity: String,
        title: String,
        extra: String? = null
    ) {
        val payload = buildString {
            append("""{"severity":"$severity","title":${objectMapper.writeValueAsString(title)}""")
            if (extra != null) append(",$extra")
            append("}")
        }
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "ISSUE",
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = REPORTED_AT,
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun seedRunningQaTry(owner: AuthenticatedUser): Long {
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "issue-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = project.id!!, payload = Json.of("{}"))
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                instanceKey = UUID.randomUUID().toString().replace("-", "").take(20),
                createdAt = now,
                updatedAt = now
            )
        )!!
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                status = "RUNNING",
                startedAt = now
            )
        )!!
        return qaTry.id!!
    }

    private suspend fun signIn(providerUserId: String, login: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )!!

    private companion object {
        val TIMEOUT: Duration = Duration.ofSeconds(5)

        // Agent가 프레임에 찍는 이벤트 시각. PostgreSQL timestamptz(마이크로초)와 정확히 비교되도록
        // 마이크로초 정밀도로 고정한다.
        val REPORTED_AT: Instant = Instant.parse("2026-07-28T12:34:56.123456Z")
    }
}
