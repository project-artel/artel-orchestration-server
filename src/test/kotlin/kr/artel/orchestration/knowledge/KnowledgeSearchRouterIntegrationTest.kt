package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.entity.KnowledgeAnchorEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
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
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "knowledge-search-session"

/** V18의 vector(1024)와 같아야 한다. */
private const val DIMENSIONS = 1024

/**
 * 모든 벡터가 같은 방향이다. 이 테스트는 순위가 아니라 프레임 왕복을 보므로 거리를 구분할 필요가
 * 없다(순위·접기 검증은 `KnowledgeVectorSearchIntegrationTest`).
 */
private fun unitVector(): List<Double> = List(DIMENSIONS) { if (it == 0) 1.0 else 0.0 }

/**
 * `KNOWLEDGE_SEARCH` WS 프레임 처리 통합 테스트(ARTEL-186).
 *
 * 검증: 정상 요청이 `KNOWLEDGE_SEARCH_RESULT`로 답하는지, 잘못된 요청과 Agent 장애가 **throw가 아니라**
 * ERROR 프레임 + ORCHE_INTERNAL 로그로 떨어지는지, 그 사이에도 런이 살아 있는지, 검색 결과가
 * qa_log를 오염시키지 않는지.
 *
 * **throw하지 않는 것이 왜 중요한가.** 프레임 하나가 throw하면 WebSocket receive 파이프라인이 onError로
 * 끊겨 소켓이 닫히고, 그것이 onDisconnect로 이어져 QA try 전체가 fail 처리된다. 지식 검색 요청 하나가
 * 실행을 죽여서는 안 된다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeSearchRouterIntegrationTest {

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

    /** 검색어를 고정 벡터로 바꾸는 대역. 등록되지 않은 검색어는 0번 축으로 답한다. */
    class StubEmbeddingAgent(private val model: String) : EmbeddingClient {
        var embedFails: Boolean = false

        override suspend fun embed(texts: List<String>): EmbedResponse {
            if (embedFails) throw IllegalStateException("임베딩 실패(테스트)")
            return EmbedResponse(
                model = model,
                dimensions = DIMENSIONS,
                vectors = texts.map { unitVector() }
            )
        }
    }

    @TestConfiguration
    class StubConfig {
        @Bean
        @Primary
        fun stubEmbeddingAgent(properties: KnowledgeBackfillProperties): StubEmbeddingAgent =
            StubEmbeddingAgent(properties.model)

        @Bean
        @Primary
        fun recordingAgentPort(): QaAgentPort = RecordingAgentPort()
    }

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var anchorRepository: KnowledgeAnchorRepository
    @Autowired private lateinit var usageRepository: KnowledgeUsageRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var backfillProperties: KnowledgeBackfillProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var agentPort: QaAgentPort
    @Autowired private lateinit var embeddingAgent: EmbeddingClient

    private val port: RecordingAgentPort get() = agentPort as RecordingAgentPort
    private val agent: StubEmbeddingAgent get() = embeddingAgent as StubEmbeddingAgent

    private var projectId: Long = 0
    private var qaTryId: Long = 0

    /**
     * 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 FK 순서대로 직접 비운다.
     * 테스트 후에도 비운다 — qa_try가 game_instance/test_scenario를 하드 FK로 참조해, 남기면 이
     * 도메인을 모르는 다른 테스트의 삭제가 FK로 깨진다.
     */
    @AfterEach
    fun clean(): Unit = runBlocking {
        wipe()
    }

    /** JUnit은 같은 클래스의 @BeforeEach 사이 순서를 보장하지 않으므로 정리와 준비를 한 곳에 둔다. */
    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        seedRunningQaTry(signIn())
    }

    private suspend fun wipe() {
        qaLogRepository.deleteAll()
        usageRepository.deleteAll()
        qaTryRepository.deleteAll()
        // knowledge_anchor는 FK가 없는 논리참조라(V55) knowledge보다 먼저 직접 비운다.
        anchorRepository.deleteAll()
        knowledgeRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        port.sent.clear()
        port.sendFails = false
        agent.embedFails = false
    }

    // ------------------------------------------------------------------ tests

    @Test
    fun `검색 요청에 KNOWLEDGE_SEARCH_RESULT로 답한다`(): Unit = runBlocking {
        val id = givenKnowledgeWithVector("점프는 스페이스바")
        val messageId = UUID.randomUUID().toString()

        // Agent가 계약에 없는 필드를 얹어 보내도 요청이 깨지면 안 된다. 계약이 한쪽에서 먼저
        // 넓어지는 것은 정상이고, 그때마다 검색이 ERROR로 떨어지면 짝 이슈를 배포 순서에 묶는다.
        deliver(messageId, """{"query":"어떻게 점프하나","reason":"플레이어 조작을 확인","step":3}""")

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_SEARCH_RESULT")
        assertThat(reply.qaTryId).isEqualTo(qaTryId.toString())
        // Agent가 자기 도구 호출과 맞출 수 있어야 한다.
        assertThat(reply.correlationId).isEqualTo(messageId)
        val results = reply.payload.path("results")
        assertThat(results.size()).isEqualTo(1)
        assertThat(results[0]["id"].asText()).isEqualTo(id.toString())
        assertThat(results[0]["summary"].asText()).isEqualTo("점프는 스페이스바")
        assertThat(reply.payload.path("query").asText()).isEqualTo("어떻게 점프하나")
    }

    /**
     * 지식 본문이 타임라인에 통째로 실리면 안 된다(ARTEL-180이 막아 둔 컨텍스트 증식과 같은 문제).
     * 쓰기 쪽 `KNOWLEDGE`도 qa_log에 남기지 않으므로 대칭이다.
     */
    @Test
    fun `성공한 검색은 qa_log에 지식 본문을 남기지 않는다`(): Unit = runBlocking {
        givenKnowledgeWithVector("점프는 스페이스바")

        deliver(UUID.randomUUID().toString(), """{"query":"어떻게 점프하나"}""")

        assertThat(qaLogRepository.findAll().toList())
            .describedAs("성공 경로는 타임라인에 아무것도 남기지 않는다")
            .isEmpty()
    }

    @Test
    fun `벡터가 없으면 오류가 아니라 빈 결과로 답한다`(): Unit = runBlocking {
        // knowledge는 있지만 백필이 아직 안 돌았다. 백필은 비동기라 이것이 정상 상태다.
        knowledgeRepository.save(
            KnowledgeEntity(projectId = projectId, source = "DOCS", tag = "RULE", summary = "s", description = "d")
        )

        deliver(UUID.randomUUID().toString(), """{"query":"아무거나"}""")

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_SEARCH_RESULT")
        assertThat(reply.payload.path("results").size()).isZero()
        assertThat(qaLogRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `검색어가 없으면 ERROR 프레임으로 답하고 런은 살아 있다`(): Unit = runBlocking {
        deliver(UUID.randomUUID().toString(), """{"tags":["RULE"]}""")

        assertThat(port.sent.single().type).isEqualTo("ERROR")
        assertThat(port.sent.single().payload.path("message").asText()).contains("payload.query is required")
        assertInternalErrorLogged("payload.query is required")
        assertRunStillActive()
    }

    @Test
    fun `모르는 tag는 조용히 무시하지 않고 ERROR로 답한다`(): Unit = runBlocking {
        // 조용히 버리면 Agent는 필터가 걸린 줄 알고 넓은 결과를 읽는다.
        deliver(UUID.randomUUID().toString(), """{"query":"질문","tags":["NOPE"]}""")

        assertThat(port.sent.single().type).isEqualTo("ERROR")
        assertInternalErrorLogged("tags must be one of")
        assertRunStillActive()
    }

    @Test
    fun `모르는 source도 ERROR로 답한다`(): Unit = runBlocking {
        deliver(UUID.randomUUID().toString(), """{"query":"질문","source":"WIKI"}""")

        assertThat(port.sent.single().type).isEqualTo("ERROR")
        assertInternalErrorLogged("source must be one of")
        assertRunStillActive()
    }

    @Test
    fun `Agent 호출이 실패해도 런을 죽이지 않고 ERROR로 답한다`(): Unit = runBlocking {
        givenKnowledgeWithVector("아무 지식")
        agent.embedFails = true

        deliver(UUID.randomUUID().toString(), """{"query":"질문"}""")

        assertThat(port.sent.single().type).isEqualTo("ERROR")
        assertInternalErrorLogged("KNOWLEDGE_SEARCH failed")
        assertRunStillActive()
    }

    /**
     * 응답을 못 보내는 것과 런을 죽이는 것은 다른 문제다. 전송 실패가 위로 던져지면 receive 체인이
     * 끊겨 소켓이 닫히고 런이 실패 처리된다.
     */
    @Test
    fun `응답 전송이 실패해도 throw하지 않는다`(): Unit = runBlocking {
        givenKnowledgeWithVector("아무 지식")
        port.sendFails = true

        deliver(UUID.randomUUID().toString(), """{"query":"질문"}""")

        assertInternalErrorLogged("delivery failed")
        assertRunStillActive()
    }

    // ------------------------------------------------------- 검색 사용 로그 (ARTEL-255)

    /**
     * 검색이 무엇을 내보냈는지가 "이 런이 만든 지식이 쓸모 있었나"의 분모다. 소급이 안 되므로
     * 검색이 도는 지금 남기지 않으면 영영 없다.
     */
    @Test
    fun `검색 한 번이 히트 수만큼 usage 행을 rank와 score로 남긴다`(): Unit = runBlocking {
        // 이 대역은 모든 벡터를 같은 방향으로 만든다. 여기서 보는 것은 순위 계산이 아니라
        // "히트 수만큼 남는가"이므로 거리를 구분할 필요가 없다(순위 검증은 벡터 검색 테스트의 몫).
        val one = givenKnowledgeWithVector("지식 하나")
        val other = givenKnowledgeWithVector("지식 둘")

        deliver(UUID.randomUUID().toString(), """{"query":"질문"}""")

        val rows = usageRepository.findByQaTryIdOrderByIdAsc(qaTryId).toList()
        assertThat(rows).hasSize(2)
        assertThat(rows.map { it.knowledgeId }).containsExactlyInAnyOrder(one, other)
        // 순위는 응답에 실린 순서와 같아야 한다 — 다르면 나중에 순위와 유용성을 견줄 수 없다.
        val reply = port.sent.single().payload.path("results")
        assertThat(rows.sortedBy { it.rank }.map { it.knowledgeId.toString() })
            .containsExactly(reply[0]["id"].asText(), reply[1]["id"].asText())
        assertThat(rows.map { it.rank }).containsExactlyInAnyOrder(1, 2)
        rows.forEach {
            assertThat(it.score).isNotNull()
            assertThat(it.knowledgeVersion).isEqualTo(1)
            // 둘 다 이번 범위에서는 채우지 않는다. cited가 false로 채워지면 이 시기의 런 전부가
            // "아무것도 인용하지 않았다"로 읽힌다.
            assertThat(it.step).isNull()
            assertThat(it.cited).isNull()
        }
    }

    @Test
    fun `결과가 비면 usage 행을 남기지 않는다`(): Unit = runBlocking {
        deliver(UUID.randomUUID().toString(), """{"query":"아무거나"}""")

        assertThat(port.sent.single().type).isEqualTo("KNOWLEDGE_SEARCH_RESULT")
        assertThat(usageRepository.findByQaTryIdOrderByIdAsc(qaTryId).toList()).isEmpty()
    }

    /**
     * 기록은 부가 데이터다. 실패했다고 ERROR로 답하면 이미 만들어진 멀쩡한 검색 결과가 실패로
     * 뒤집히고, Agent 도구는 답 대신 오류를 받는다. 감사 로그만 남기고 결과는 그대로 나가야 한다.
     */
    @Test
    fun `usage 기록이 실패해도 결과는 전달되고 런은 살아 있다`(): Unit = runBlocking {
        givenKnowledgeWithVector("가까운 지식")
        blockUsageWrites()
        try {
            deliver(UUID.randomUUID().toString(), """{"query":"질문"}""")
        } finally {
            allowUsageWrites()
        }

        val reply = port.sent.single()
        assertThat(reply.type)
            .describedAs("기록 실패가 검색 실패로 번지면 안 된다")
            .isEqualTo("KNOWLEDGE_SEARCH_RESULT")
        assertThat(reply.payload.path("results").size()).isEqualTo(1)
        assertInternalErrorLogged("usage logging failed")
        assertRunStillActive()
    }

    @Test
    fun `단수 tag도 받는다`(): Unit = runBlocking {
        givenKnowledgeWithVector("규칙 항목", tag = "RULE")
        givenKnowledgeWithVector("조작 항목", tag = "CONTROL")

        deliver(UUID.randomUUID().toString(), """{"query":"질문","tag":"control"}""")

        val results = port.sent.single().payload.path("results")
        assertThat(results.size()).isEqualTo(1)
        assertThat(results[0]["summary"].asText()).isEqualTo("조작 항목")
    }

    // ------------------------------------------------------------- `anchor` (ARTEL-591)

    /**
     * 프레임의 필드 이름을 못박는다. Agent 쪽(ARTEL-592)이 맞춰야 하는 계약이 이것이다 —
     * 요청은 `scene_name`, 응답은 히트마다 `anchors[].scene_name` / `anchors[].screen_id`다.
     *
     * `anchor` 는 순수 추가 필드라, 이 필드를 모르는 구버전 Agent는 통째로 무시한다.
     */
    @Test
    fun `검색 응답이 anchor 를 싣고 scene_name으로 좁혀진다`(): Unit = runBlocking {
        val combat = givenKnowledgeWithVector("전투 중 ESC는 아무것도 하지 않는다")
        val global = givenKnowledgeWithVector("낙하 데미지는 5m부터")
        anchorRepository.save(
            KnowledgeAnchorEntity(knowledgeId = combat, sceneName = "Combat", screenId = 4_242L)
        )

        deliver(UUID.randomUUID().toString(), """{"query":"ESC를 누르면"}""")

        val all = port.sent.single().payload.path("results")
        assertThat(all.size()).isEqualTo(2)
        val anchored = all.single { it["id"].asText() == combat.toString() }
        assertThat(anchored.path("anchors").size()).isEqualTo(1)
        assertThat(anchored.path("anchors")[0]["scene_name"].asText()).isEqualTo("Combat")
        assertThat(anchored.path("anchors")[0]["screen_id"].asText()).isEqualTo("4242")
        // `anchor` 가 없으면 게임 전체의 사실이라 빈 배열이다.
        assertThat(all.single { it["id"].asText() == global.toString() }.path("anchors")).isEmpty()

        port.sent.clear()
        deliver(UUID.randomUUID().toString(), """{"query":"ESC를 누르면","scene_name":"Combat"}""")

        val narrowed = port.sent.single().payload.path("results")
        assertThat(narrowed.size()).describedAs("anchor 없는 지식까지 걸러야 좁히는 것이 있다").isEqualTo(1)
        assertThat(narrowed[0]["id"].asText()).isEqualTo(combat.toString())
    }

    /**
     * 없는 씬 이름은 오류가 아니라 빈 결과다. tag/source와 달리 우리가 아는 씬 목록이 없어(V55)
     * 검증할 수가 없고, 오류로 답하면 기다리던 Agent 도구가 실패한다.
     */
    @Test
    fun `모르는 scene_name은 오류가 아니라 빈 결과다`(): Unit = runBlocking {
        givenKnowledgeWithVector("아무 지식")

        deliver(UUID.randomUUID().toString(), """{"query":"무엇이든","scene_name":"NoSuchScene"}""")

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_SEARCH_RESULT")
        assertThat(reply.payload.path("results")).isEmpty()
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("RUNNING")
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun deliver(messageId: String, payload: String) {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "KNOWLEDGE_SEARCH",
                qaTryId = qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-07-29T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun assertInternalErrorLogged(fragment: String) {
        val logs = qaLogRepository.findAll().toList()
        assertThat(logs).isNotEmpty()
        assertThat(logs.map { "${it.direction}/${it.type}" }).allMatch { it == "ORCHE_INTERNAL/ERROR" }
        assertThat(logs.mapNotNull { it.message }).anyMatch { it.contains(fragment) }
    }

    private suspend fun assertRunStillActive() {
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /**
     * usage INSERT를 실패시킨다. 실제로 일어날 실패는 DB 장애이고, 그 경로를 그대로 재현하는 것이
     * 서비스를 대역으로 갈아 끼우는 것보다 정직하다 — 라우터가 삼키는 것이 바로 이 예외다.
     *
     * `NOT VALID`라 기존 행을 검사하지 않고 새 INSERT만 막는다. 반드시 [allowUsageWrites]로
     * 되돌려야 한다 — 스위트가 DB 하나를 공유하므로 남으면 다음 테스트가 이유 없이 깨진다.
     */
    private suspend fun blockUsageWrites() {
        execute("ALTER TABLE knowledge_usage ADD CONSTRAINT ck_usage_test_block CHECK (false) NOT VALID")
    }

    private suspend fun allowUsageWrites() {
        execute("ALTER TABLE knowledge_usage DROP CONSTRAINT IF EXISTS ck_usage_test_block")
    }

    private suspend fun execute(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitFirstOrNull()
    }

    private suspend fun givenKnowledgeWithVector(summary: String, tag: String = "RULE"): Long {
        val id = knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = tag,
                summary = summary,
                description = "$summary 설명"
            )
        ).id!!
        databaseClient.sql(
            """
            INSERT INTO knowledge_embedding (knowledge_id, kind, model, source_text, embedding)
            VALUES (:knowledgeId, 'QUERY', :model, :text, CAST(:embedding AS vector))
            """.trimIndent()
        )
            .bind("knowledgeId", id)
            .bind("model", backfillProperties.model)
            .bind("text", summary)
            .bind("embedding", EmbeddedText(summary, unitVector()).toVectorLiteral())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        return id
    }

    private suspend fun seedRunningQaTry(owner: AuthenticatedUser) {
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-search-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val scenario = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId)
        )!!
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = "instance",
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!
        qaTryId = qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenario.id!!,
                gameInstanceId = instance.id!!,
                startedBy = ownerId,
                // 응답을 돌려보낼 세션이 붙어 있어야 한다.
                agentSessionId = SESSION_ID,
                status = "RUNNING",
                startedAt = now
            )
        )!!.id!!
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "186",
                login = "searcher",
                displayName = "searcher",
                avatarUrl = null,
                email = "searcher@example.com"
            )
        )!!

}
