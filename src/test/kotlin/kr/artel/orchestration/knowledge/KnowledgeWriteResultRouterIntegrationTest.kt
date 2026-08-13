package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
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

private const val SESSION_ID = "knowledge-write-session"

/**
 * 지식 쓰기 프레임의 응답 계약 검증(ARTEL-331).
 *
 * 여기서 보는 것은 저장이 아니다 — 저장은 [KnowledgeMutationInboundIntegrationTest]와
 * [KnowledgeEdgeIntegrationTest]가 이미 본다. 이 스위트가 보는 것은 **무엇이 Agent로 나갔나**다.
 *
 * 계약은 검색·확장의 것을 그대로 쓴다: 성공은 `KNOWLEDGE_WRITE_RESULT`, 거절은 요청의 messageId를
 * correlation으로 문 `ERROR`. RESULT에 outcome 필드를 두지 않는 이유가 그것이다 — 실패를 말하는
 * 방법이 이미 있는데 하나 더 만들면 소비자가 두 곳을 봐야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeWriteResultRouterIntegrationTest {

    /** Agent로 나간 프레임을 붙잡아 두는 대역. 전송 실패도 재현한다. */
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
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
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

    /** 리액티브 트랜잭션은 테스트 롤백이 안 되므로 직접 비운다(다른 통합 테스트와 같은 이유). */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        recorder.sent.clear()
        recorder.sendFails = false
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------------------ 성공

    @Test
    fun `CREATE는 만들어진 항목의 id를 실어 답한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        val messageId = deliver(
            run.qaTryId,
            "KNOWLEDGE_CREATE",
            """{"tag":"RULE","summary":"낙하 데미지","description":"5m부터 1당 2"}"""
        )

        val stored = knowledgeRepository.findVisible(run.projectId, null, null, null).toList().single()
        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("KNOWLEDGE_WRITE_RESULT")
        assertThat(answer.correlationId).isEqualTo(messageId)
        assertThat(answer.payload.path("type").asText()).isEqualTo("KNOWLEDGE_CREATE")
        assertThat(answer.payload.path("knowledge_id").asText())
            .describedAs("id는 문자열이다 — 64비트가 JSON 숫자로 나가면 정밀도가 깎인다")
            .isEqualTo(stored.id.toString())
        assertThat(errorLogs(run.qaTryId)).isEmpty()
    }

    @Test
    fun `LINK는 edge_id를 실어 답한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val from = givenKnowledge(run.projectId, "상점에서만 골드 부족 안내가 뜬다")
        val to = givenKnowledge(run.projectId, "구매는 골드가 모자라면 막힌다")

        deliver(
            run.qaTryId,
            "KNOWLEDGE_LINK",
            """{"from_knowledge_id":"$from","to_knowledge_id":"$to","relation":"REFINES","note":"상점에서 확인"}"""
        )

        val edge = edgeRepository.findAll().toList().single()
        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("KNOWLEDGE_WRITE_RESULT")
        assertThat(answer.payload.path("type").asText()).isEqualTo("KNOWLEDGE_LINK")
        assertThat(answer.payload.path("edge_id").asText()).isEqualTo(edge.id.toString())
    }

    /**
     * DELETE와 UNLINK는 각 라우터의 `else` 갈래를 탄다. 그 갈래도 답하는지를 여기서 본다 —
     * 성공 응답을 `when`의 한 갈래에만 달아 두는 실수는 컴파일로 드러나지 않는다.
     */
    @Test
    fun `DELETE와 UNLINK도 답한다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        val from = givenKnowledge(run.projectId, "왼쪽")
        val to = givenKnowledge(run.projectId, "오른쪽")
        deliver(
            run.qaTryId,
            "KNOWLEDGE_LINK",
            """{"from_knowledge_id":"$from","to_knowledge_id":"$to","relation":"REFINES","note":"확인함"}"""
        )
        val edgeId = edgeRepository.findAll().toList().single().id
        recorder.sent.clear()

        deliver(
            run.qaTryId,
            "KNOWLEDGE_UNLINK",
            """{"from_knowledge_id":"$from","to_knowledge_id":"$to","relation":"REFINES"}"""
        )
        deliver(run.qaTryId, "KNOWLEDGE_DELETE", """{"knowledge_id":"$from"}""")

        val (unlinked, deleted) = recorder.sent.toList()
        assertThat(unlinked.payload.path("type").asText()).isEqualTo("KNOWLEDGE_UNLINK")
        assertThat(unlinked.payload.path("edge_id").asText())
            .describedAs("운영 런은 그 간선을 직접 지운다 — 툼스톤이 아니다")
            .isEqualTo(edgeId.toString())
        assertThat(deleted.payload.path("type").asText()).isEqualTo("KNOWLEDGE_DELETE")
        assertThat(deleted.payload.path("knowledge_id").asText()).isEqualTo(from.toString())
    }

    /**
     * 스코프 런이 baseline을 고치면 그림자 행이 생긴다. 돌려주는 id는 **그림자**여야 한다 —
     * baseline id를 주면 그 런에서 다시 지목할 수 없는 id를 주는 셈이다.
     */
    @Test
    fun `스코프 런의 UPDATE는 그림자 id로 답한다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeScopeId = 7_001L)
        val baseline = givenKnowledge(run.projectId, "운영 지식")

        deliver(run.qaTryId, "KNOWLEDGE_UPDATE", """{"knowledge_id":"$baseline","summary":"실험이 본 것"}""")

        val shadow = knowledgeRepository.findVisible(run.projectId, 7_001L, null, null).toList().single()
        assertThat(shadow.shadowsId).isEqualTo(baseline)
        assertThat(recorder.sent.single().payload.path("knowledge_id").asText()).isEqualTo(shadow.id.toString())
    }

    // ------------------------------------------------------------------ 거절

    @Test
    fun `거절은 요청의 correlation을 문 ERROR로 내려간다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        val messageId = deliver(
            run.qaTryId,
            "KNOWLEDGE_CREATE",
            """{"tag":"NOPE","summary":"s","description":"d"}"""
        )

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.correlationId).isEqualTo(messageId)
        assertThat(answer.payload.path("message").asText()).contains("rejected")
        // 사람이 읽을 흔적은 그대로 남는다. 프레임은 도구를 풀어 주기 위한 것이고 로그는 감사용이다.
        assertThat(errorLogs(run.qaTryId)).hasSize(1)
        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
    }

    /**
     * `frozen`/`off`는 게이트에서 막힌다. 여기서 답하지 않으면 그 런의 **모든** 쓰기가 Agent 쪽
     * 타임아웃을 통째로 태운다 — 지표에 실패로 남지 않는 종류의 회귀다.
     */
    @Test
    fun `frozen 런의 쓰기도 ERROR로 답한다`(): Unit = runBlocking {
        val run = seedRunningQaTry(knowledgeMode = "frozen")

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"쓰면 안 됨","description":"d"}""")

        val answer = recorder.sent.single()
        assertThat(answer.type).isEqualTo("ERROR")
        assertThat(answer.payload.path("message").asText()).contains("knowledge_mode=frozen")
        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).isEmpty()
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    // ------------------------------------------------------- 답하지 않는 경우

    /**
     * 배치 인입은 기다리는 호출부가 없다. 답하면 Agent 쪽에서 짝 없는 응답이 되어 경고만 쌓인다.
     */
    @Test
    fun `배치 인입 KNOWLEDGE는 답하지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry()

        deliver(
            run.qaTryId,
            "KNOWLEDGE",
            """{"source":"qa","game_context":[{"tag":"UI","summary":"체력바","description":"좌상단"}]}"""
        )

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).hasSize(1)
        assertThat(recorder.sent).isEmpty()
    }

    /**
     * 세션이 없으면 답을 못 보낼 뿐, **쓰기는 그대로 수행한다.** 검색·확장은 답할 곳이 없으면 일을
     * 시작조차 하지 않지만(그쪽은 결과가 곧 목적이다) 쓰기가 그러면 지식이 저장되지 않는다.
     *
     * 이 케이스가 곧 구버전 Agent 호환의 회귀 방어다.
     */
    @Test
    fun `세션이 없어도 저장은 되고 프레임만 나가지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry(agentSessionId = null)

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"저장된다","description":"d"}""")

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).hasSize(1)
        assertThat(recorder.sent).isEmpty()
    }

    /** 응답 전송이 실패해도 저장은 이미 끝났고 런은 살아 있어야 한다. */
    @Test
    fun `응답 전송 실패가 런을 죽이지 않는다`(): Unit = runBlocking {
        val run = seedRunningQaTry()
        recorder.sendFails = true

        deliver(run.qaTryId, "KNOWLEDGE_CREATE", """{"tag":"RULE","summary":"저장된다","description":"d"}""")

        assertThat(knowledgeRepository.findVisible(run.projectId, null, null, null).toList()).hasSize(1)
        assertThat(qaTryRepository.findById(run.qaTryId)!!.status).isEqualTo("RUNNING")
    }

    // --- helpers ---

    /** 보낸 프레임의 messageId를 돌려준다. 응답의 correlation이 그것과 같아야 한다. */
    private suspend fun deliver(qaTryId: Long, type: String, payload: String): String {
        val messageId = UUID.randomUUID().toString()
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = type,
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

    private suspend fun givenKnowledge(projectId: Long, summary: String = "요약"): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = summary,
                description = "설명"
            )
        ).id!!

    private suspend fun seedRunningQaTry(
        knowledgeScopeId: Long? = null,
        knowledgeMode: String? = null,
        agentSessionId: String? = SESSION_ID
    ): RunningQaTry {
        val owner = signIn(UUID.randomUUID().toString().take(8))
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-write-result", genre = "ACTION", createdAt = now, updatedAt = now)
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
        val qaTry = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                agentSessionId = agentSessionId,
                status = "RUNNING",
                knowledgeScopeId = knowledgeScopeId,
                runConfig = knowledgeMode
                    ?.let { Json.of("""{"knowledge_mode":"$it"}""") }
                    ?: Json.of("{}"),
                startedAt = now
            )
        )!!
        return RunningQaTry(qaTryId = qaTry.id!!, projectId = project.id!!)
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

    private data class RunningQaTry(val qaTryId: Long, val projectId: Long)
}
