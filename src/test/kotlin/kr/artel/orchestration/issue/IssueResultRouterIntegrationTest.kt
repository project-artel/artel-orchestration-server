package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaAgentUnavailableException
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "issue-result-session"

/**
 * 이슈 보고의 응답 계약 검증(ARTEL-366).
 *
 * 저장은 [IssueIntegrationTest]가 본다. 이 스위트가 보는 것은 **무엇이 Agent로 나갔나**다.
 *
 * 고치는 결함이 이것이다: 그 전에는 성공이 침묵이고 거절도 운영자 타임라인의 ERROR 행뿐이라,
 * severity 오타 하나면 버그 보고가 조용히 사라지고 모델은 보고했다고 믿었다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class IssueResultRouterIntegrationTest {

    class RecordingAgentPort : QaAgentPort {
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()
        var sendFails: Boolean = false

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession(SESSION_ID)

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            if (sendFails) throw QaAgentUnavailableException("전송 실패(테스트)")
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()
    }

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: QaAgentPort

    private val recorder: RecordingAgentPort get() = agentPort as RecordingAgentPort

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        recorder.sent.clear()
        recorder.sendFails = false
        issueRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `보고가 저장되면 그 id를 실어 답한다`(): Unit = runBlocking {
        val qaTryId = seedRunningQaTry()

        val messageId = deliver(qaTryId, """{"title":"상점에서 골드가 음수로 표시된다","severity":"MAJOR"}""")

        val stored = issueRepository.findAll().toList().single()
        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ISSUE_RESULT")
        assertThat(answer.correlationId).isEqualTo(messageId)
        assertThat(answer.payload.path("type").asText()).isEqualTo("ISSUE")
        assertThat(answer.payload.path("issue_id").asText()).isEqualTo(stored.id.toString())
    }

    /**
     * 이 스위트가 존재하는 이유. severity 오타는 보고를 통째로 잃게 하는데, 그 전에는 모델이
     * 그것을 알 방법이 없었다.
     */
    @Test
    fun `모르는 severity는 거절로 모델에 닿는다`(): Unit = runBlocking {
        val qaTryId = seedRunningQaTry()

        val messageId = deliver(qaTryId, """{"title":"버그","severity":"CRITICALL"}""")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.correlationId).isEqualTo(messageId)
        assertThat(answer.payload.path("message").asText()).contains("severity must be one of")
        assertThat(issueRepository.findAll().toList()).isEmpty()
        // 사람이 볼 흔적도 그대로 남는다.
        assertThat(errorLogs(qaTryId)).hasSize(1)
    }

    /**
     * `uk_issue_message`가 이미 재전송을 흡수한다. 응답이 붙은 지금 확인해야 하는 것은 두 번째
     * 프레임도 **성공**으로, 그것도 **같은 id**로 답하는가다 — 거절로 답하면 재전송한 Agent가
     * 보고를 잃었다고 읽는다.
     */
    @Test
    fun `재전송은 같은 id로 두 번 다 성공한다`(): Unit = runBlocking {
        val qaTryId = seedRunningQaTry()
        val frame = UUID.randomUUID().toString()
        val payload = """{"title":"같은 보고","severity":"MINOR"}"""

        deliver(qaTryId, payload, messageId = frame)
        deliver(qaTryId, payload, messageId = frame)

        assertThat(issueRepository.findAll().toList()).hasSize(1)
        assertThat(recorder.sent).hasSize(2)
        assertThat(recorder.sent.map { it.type }).containsExactly("ISSUE_RESULT", "ISSUE_RESULT")
        val ids = recorder.sent.map { it.payload.path("issue_id").asText() }.distinct()
        assertThat(ids).describedAs("두 응답의 id가 다르면 멱등이 응답까지 오지 않은 것이다").hasSize(1)
    }

    /** 세션이 없으면 답만 못 보낸다. 보고 자체는 저장돼야 한다 — 지식 쓰기와 같은 판단이다. */
    @Test
    fun `세션이 없어도 보고는 저장된다`(): Unit = runBlocking {
        val qaTryId = seedRunningQaTry(agentSessionId = null)

        deliver(qaTryId, """{"title":"세션 없는 런의 보고","severity":"MAJOR"}""")

        assertThat(issueRepository.findAll().toList()).hasSize(1)
        assertThat(recorder.sent).isEmpty()
    }

    @Test
    fun `응답 전송 실패가 런을 죽이지 않는다`(): Unit = runBlocking {
        val qaTryId = seedRunningQaTry()
        recorder.sendFails = true

        deliver(qaTryId, """{"title":"보고","severity":"MAJOR"}""")

        assertThat(issueRepository.findAll().toList()).hasSize(1)
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("RUNNING")
    }

    // --- helpers ---

    private suspend fun deliver(
        qaTryId: Long,
        payload: String,
        messageId: String = UUID.randomUUID().toString()
    ): String {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "ISSUE",
                qaTryId = qaTryId.toString(),
                correlationId = UUID.randomUUID().toString(),
                timestamp = Instant.parse("2026-08-13T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
        return messageId
    }

    private suspend fun errorLogs(qaTryId: Long) =
        qaLogRepository.findAll().toList().filter { it.qaTryId == qaTryId && it.type == "ERROR" }

    private suspend fun seedRunningQaTry(agentSessionId: String? = SESSION_ID): Long {
        val owner = signIn(UUID.randomUUID().toString().take(8))
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "issue-result", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = project.id!!,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        return qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                agentSessionId = agentSessionId,
                status = "RUNNING",
                startedAt = now
            )
        )!!.id!!
    }

    private suspend fun signIn(seed: String): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = seed,
                login = "user-$seed",
                displayName = "user-$seed",
                avatarUrl = null,
                email = "user-$seed@example.com"
            )
        )!!
}
