package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
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

private const val EXPAND_SESSION_ID = "knowledge-expand-session"
private const val EXPAND_DIMENSIONS = 1024

private fun expandUnitVector(): List<Double> = List(EXPAND_DIMENSIONS) { if (it == 0) 1.0 else 0.0 }

/**
 * `KNOWLEDGE_EXPAND` WS 프레임과 **검색 결과에 딸리는 1홉 이웃** 검증(ARTEL-275).
 *
 * 여기서 볼 것은 탐색 자체가 아니라(그것은 [KnowledgeGraphTraversalIntegrationTest]) **계약**이다.
 *
 * 1. 확장이 `KNOWLEDGE_EXPAND_RESULT`로 답하고, 실패는 throw가 아니라 ERROR 프레임 + 로그다.
 * 2. 검색 응답이 이웃을 싣되 **기존 필드는 하나도 안 바뀐다** — 순수 추가라는 것이 곧 시험이다.
 * 3. 이웃도 `knowledge_usage`에 남되 `rank`는 NULL이다. 로그를 우회하면 ARTEL-255의 분모가
 *    조용히 모자라고, 0이나 임의의 rank로 채우면 순위와 유용성을 견주는 질의가 틀린다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeExpandRouterIntegrationTest {

    class RecordingPort : QaAgentPort {
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()
        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession(EXPAND_SESSION_ID)

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    class StubEmbedding(private val model: String) : EmbeddingClient {
        override suspend fun embed(texts: List<String>) =
            EmbedResponse(model = model, dimensions = EXPAND_DIMENSIONS, vectors = texts.map { expandUnitVector() })
    }

    @TestConfiguration
    class StubConfig {
        @Bean @Primary
        fun stubEmbedding(properties: KnowledgeBackfillProperties): StubEmbedding = StubEmbedding(properties.model)

        @Bean @Primary
        fun recordingPort(): QaAgentPort = RecordingPort()
    }

    @Autowired private lateinit var inboundRouter: QaAgentInboundRouter
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
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

    private val port: RecordingPort get() = agentPort as RecordingPort

    private var projectId: Long = 0
    private var qaTryId: Long = 0

    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        port.sent.clear()
        edgeRepository.deleteAll()
        usageRepository.deleteAll()
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

    @Test
    fun `EXPAND는 이웃과 seed 요약을 담아 답한다`(): Unit = runBlocking {
        seedRun()
        val seed = givenKnowledge("마을 화면")
        val shop = givenKnowledge("상점 패널")
        givenEdge(seed, shop, "LEADS_TO", "마을 상단바의 상점 버튼")

        expand(seed)

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_EXPAND_RESULT")
        val payload = reply.payload
        assertThat(payload.path("id").asText()).isEqualTo(seed.toString())
        assertThat(payload.path("summary").asText()).isEqualTo("마을 화면")
        assertThat(payload.path("truncated").asBoolean()).isFalse()
        val neighbour = payload.path("neighbors").single()
        assertThat(neighbour.path("id").asText()).isEqualTo(shop.toString())
        assertThat(neighbour.path("relation").asText()).isEqualTo("LEADS_TO")
        assertThat(neighbour.path("origin").asText()).isEqualTo("EDGE")
        assertThat(neighbour.path("note").asText()).isEqualTo("마을 상단바의 상점 버튼")
        assertThat(neighbour.path("score").isNull).describedAs("관계로 온 이웃에는 유사도가 없다").isTrue()
    }

    /** ARTEL-255의 분모다. 이웃이 로그를 우회하면 "이 지식이 쓸모 있었나"가 조용히 모자란다. */
    @Test
    fun `확장으로 나간 이웃도 사용 기록에 남고 rank는 NULL이다`(): Unit = runBlocking {
        seedRun()
        val seed = givenKnowledge("seed")
        val other = givenKnowledge("other")
        givenEdge(seed, other, "REFINES", "이유")

        expand(seed)

        val usage = usageRepository.findAll().toList()
        assertThat(usage).hasSize(1)
        assertThat(usage.single().knowledgeId).isEqualTo(other)
        assertThat(usage.single().rank).describedAs("이웃은 관련도 순위를 매긴 결과가 아니다").isNull()
        assertThat(usage.single().score).isNull()
    }

    @Test
    fun `숫자가 아닌 id는 ERROR 프레임으로 답해 기다리는 도구를 푼다`(): Unit = runBlocking {
        seedRun()

        deliver("""{"knowledge_id":"abc"}""")

        assertThat(port.sent.single().type).isEqualTo("ERROR")
        assertThat(qaLogRepository.findAll().toList().mapNotNull { it.message })
            .anyMatch { it.contains("knowledge_id must be a numeric id") }
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /** 없는 항목은 오류가 아니다 — 스코프 런이 방금 자기가 지운 항목을 펴려는 경우가 정상 경로에 있다. */
    @Test
    fun `없는 항목은 오류가 아니라 빈 결과다`(): Unit = runBlocking {
        seedRun()

        expand(9_999_999L)

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_EXPAND_RESULT")
        assertThat(reply.payload.path("neighbors")).isEmpty()
    }

    /** 대조군 arm에서 도구가 실패하면 Agent가 재시도하며 행동이 달라진다. */
    @Test
    fun `knowledge_mode가 off면 오류가 아니라 빈 결과다`(): Unit = runBlocking {
        seedRun(knowledgeMode = "off")
        val seed = givenKnowledge("seed")
        givenEdge(seed, givenKnowledge("other"), "REFINES", "이유")

        expand(seed)

        val reply = port.sent.single()
        assertThat(reply.type).isEqualTo("KNOWLEDGE_EXPAND_RESULT")
        assertThat(reply.payload.path("neighbors")).isEmpty()
        assertThat(usageRepository.findAll().toList()).describedAs("내보낸 것이 없으니 기록도 없다").isEmpty()
    }

    @Test
    fun `답할 세션이 없으면 일을 시작하지 않는다`(): Unit = runBlocking {
        seedRun(withSession = false)
        val seed = givenKnowledge("seed")

        expand(seed)

        assertThat(port.sent).isEmpty()
        assertThat(qaLogRepository.findAll().toList().mapNotNull { it.message })
            .anyMatch { it.contains("has no Agent session") }
    }

    // ------------------------------------------------ 검색 결과에 딸리는 1홉 이웃

    @Test
    fun `검색 결과가 이웃을 싣고 기존 필드는 그대로다`(): Unit = runBlocking {
        seedRun()
        val hit = givenKnowledgeWithVector("골드가 모자라면 구매가 막힌다")
        val exception = givenKnowledge("상점에서만 안내 문구가 뜬다")
        givenEdge(exception, hit, "REFINES", "상점 화면에서 확인")

        deliver("""{"query":"구매가 막히는 조건"}""", type = "KNOWLEDGE_SEARCH")

        val result = port.sent.single { it.type == "KNOWLEDGE_SEARCH_RESULT" }.payload
        val row = result.path("results").single()
        // 기존 계약이 하나도 안 바뀌었다는 것이 이 단언의 요점이다.
        assertThat(row.path("id").asText()).isEqualTo(hit.toString())
        assertThat(row.path("summary").asText()).isEqualTo("골드가 모자라면 구매가 막힌다")
        assertThat(row.path("description").isTextual).isTrue()
        assertThat(row.path("score").isNumber).isTrue()
        // 그 위에 이웃이 얹힌다.
        val neighbour = row.path("neighbors").single()
        assertThat(neighbour.path("id").asText()).isEqualTo(exception.toString())
        assertThat(neighbour.path("relation").asText()).isEqualTo("REFINES")
        assertThat(neighbour.path("direction").asText()).describedAs("히트가 to 쪽이다").isEqualTo("IN")
    }

    @Test
    fun `관계가 없으면 이웃은 빈 목록이고 검색은 그대로 답한다`(): Unit = runBlocking {
        seedRun()
        givenKnowledgeWithVector("아무 관계도 없는 항목")

        deliver("""{"query":"무엇이든"}""", type = "KNOWLEDGE_SEARCH")

        val row = port.sent.single { it.type == "KNOWLEDGE_SEARCH_RESULT" }.payload.path("results").single()
        assertThat(row.path("neighbors")).isEmpty()
    }

    // --- helpers ---

    private suspend fun expand(knowledgeId: Long) = deliver("""{"knowledge_id":"$knowledgeId"}""")

    private suspend fun deliver(payload: String, type: String = "KNOWLEDGE_EXPAND") {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = qaTryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-08-06T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    private suspend fun givenKnowledge(summary: String): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "UI",
                summary = summary,
                description = "$summary 설명"
            )
        ).id!!

    private suspend fun givenKnowledgeWithVector(summary: String): Long {
        val id = givenKnowledge(summary)
        databaseClient.sql(
            """
            INSERT INTO knowledge_embedding (knowledge_id, kind, model, source_text, embedding)
            VALUES (:knowledgeId, 'QUERY', :model, :text, CAST(:embedding AS vector))
            """.trimIndent()
        )
            .bind("knowledgeId", id)
            .bind("model", backfillProperties.model)
            .bind("text", summary)
            .bind("embedding", EmbeddedText(summary, expandUnitVector()).toVectorLiteral())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
        return id
    }

    private suspend fun givenEdge(from: Long, to: Long, relation: String, note: String) {
        edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = projectId,
                fromKnowledgeId = from,
                toKnowledgeId = to,
                relation = relation,
                note = note
            )
        )
    }

    private suspend fun seedRun(withSession: Boolean = true, knowledgeMode: String? = null) {
        val owner = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = UUID.randomUUID().toString().take(8),
                login = "expander",
                displayName = "expander",
                avatarUrl = null,
                email = "expander@example.com"
            )
        )!!
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-expand", genre = "ACTION", createdAt = now, updatedAt = now)
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
                agentSessionId = if (withSession) EXPAND_SESSION_ID else null,
                status = "RUNNING",
                runConfig = knowledgeMode
                    ?.let { Json.of("""{"knowledge_mode":"$it"}""") }
                    ?: Json.of("{}"),
                startedAt = now
            )
        )!!.id!!
    }
}
