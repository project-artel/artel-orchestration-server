package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
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
import kr.artel.orchestration.knowledge.entity.KnowledgeRetrievalKind
import kr.artel.orchestration.knowledge.entity.KnowledgeUsageEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeUsageRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaAgentPort
import kr.artel.orchestration.qa.service.QaAgentSession
import kr.artel.orchestration.qa.service.QaAgentSessionContext
import kr.artel.orchestration.qa.service.QaExecutionFailureService
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
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList

private const val SESSION_ID = "knowledge-citation-session"

/** V18의 vector(1024)와 같아야 한다. */
private const val DIMENSIONS = 1024

/** 인용을 보고할 수 있는 런의 표식. Agent가 세션 개설 응답에 실어 주는 값(ARTEL-294). */
private const val REPORTING_CONFIG = """{"citation_reporting":true}"""

/** 이 기능 이전의 런, 그리고 표식을 실어 주지 않는 구버전 Agent가 붙은 런. */
private const val LEGACY_CONFIG = """{}"""

private fun unitVector(): List<Double> = List(DIMENSIONS) { if (it == 0) 1.0 else 0.0 }

/**
 * 인용 기록 검증(ARTEL-293).
 *
 * V27이 검색으로 **무엇이 나갔는지**를 기록했다면 여기서 보는 것은 그중 **무엇이 쓰였는지**다.
 * 셋이 갈려야 한다 — 검색됨(usage 행), 읽고 고려됨(관측 불가, 재지 않는다), 행동에 반영됨(인용).
 *
 * 특히 두 가지가 이 스위트의 이유다:
 *
 * 1. **NULL과 false가 갈리는가.** 확정이 없으면 "인용을 보고할 수 없었던 런"과 "보고 가능했는데
 *    인용하지 않은 런"이 영영 같은 값이고, `cited`를 nullable로 둔 이유가 통째로 무의미해진다.
 * 2. **기록이 런을 죽이지 않는가.** 프레임 처리 중 예외가 WS 수신 체인 밖으로 나가면 소켓이
 *    닫히고 런 전체가 실패한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeCitationIntegrationTest {

    class RecordingAgentPort : QaAgentPort {
        val sent: MutableList<QaAgentEnvelope> = CopyOnWriteArrayList()

        override suspend fun createSession(
            context: QaAgentSessionContext,
            onMessage: suspend (QaAgentEnvelope) -> Unit,
            onDisconnect: suspend () -> Unit
        ): QaAgentSession = QaAgentSession(SESSION_ID)

        override suspend fun send(sessionId: String, envelope: QaAgentEnvelope) {
            sent += envelope
        }

        override suspend fun close(sessionId: String) = Unit
    }

    class StubEmbeddingAgent(private val model: String) : EmbeddingClient {
        override suspend fun embed(texts: List<String>): EmbedResponse =
            EmbedResponse(model = model, dimensions = DIMENSIONS, vectors = texts.map { unitVector() })
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
    @Autowired private lateinit var failureService: QaExecutionFailureService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var usageRepository: KnowledgeUsageRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var backfillProperties: KnowledgeBackfillProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var projectId: Long = 0
    private var scenarioId: Long = 0
    private var testRunId: Long = 0
    private var instanceId: Long = 0
    private var ownerId: Long = 0
    private var qaTryId: Long = 0

    @AfterEach
    fun clean(): Unit = runBlocking { wipe() }

    @BeforeEach
    fun cleanAndSeed(): Unit = runBlocking {
        wipe()
        seed(signIn())
    }

    private suspend fun wipe() {
        allowCitationWrites()
        qaLogRepository.deleteAll()
        usageRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        testRunRepository.deleteAll()
        edgeRepository.deleteAll()
        knowledgeRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    // ------------------------------------------------------- retrieval_kind (출처)

    /**
     * 직접 히트와 밀어넣은 이웃은 신호 세기가 다르다 — 밀어넣은 것을 안 쓰는 것은 정상이다.
     * 둘이 한 통에 담기면 인용률이 검색 설정(이웃을 붙이느냐)에 따라 흔들린다.
     */
    @Test
    fun `직접 히트는 DIRECT, 딸려온 이웃은 SEARCH_NEIGHBOR로 남는다`(): Unit = runBlocking {
        val hit = knowledgeWithVector("점프는 스페이스바")
        val neighbour = knowledge("점프대는 두 배로 뛴다")
        edge(hit, neighbour)

        deliver("KNOWLEDGE_SEARCH", """{"query":"어떻게 점프하나"}""")

        val byKnowledge = usageRows().associateBy { it.knowledgeId }
        assertThat(byKnowledge[hit]?.retrievalKind).isEqualTo(KnowledgeRetrievalKind.DIRECT.name)
        assertThat(byKnowledge[neighbour]?.retrievalKind)
            .isEqualTo(KnowledgeRetrievalKind.SEARCH_NEIGHBOR.name)
        // rank로 유추하지 않는다는 것이 이 컬럼의 존재 이유다. 그래도 기존 관례는 그대로 지킨다.
        assertThat(byKnowledge[hit]?.rank).isEqualTo(1)
        assertThat(byKnowledge[neighbour]?.rank).isNull()
    }

    /** 에이전트가 직접 요청한 이웃. 요청해 놓고 안 쓴 것은 밀어넣은 것을 안 쓴 것과 다르다. */
    @Test
    fun `expand로 데려온 이웃은 EXPAND로 남는다`(): Unit = runBlocking {
        val seed = knowledge("씨앗")
        val neighbour = knowledge("이웃")
        edge(seed, neighbour)

        deliver("KNOWLEDGE_EXPAND", """{"knowledge_id":"$seed","depth":1}""")

        val rows = usageRows()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().knowledgeId).isEqualTo(neighbour)
        assertThat(rows.single().retrievalKind).isEqualTo(KnowledgeRetrievalKind.EXPAND.name)
    }

    /** 이 컬럼 이전에 기록된 행은 출처를 **모르는** 것이지 DIRECT가 아니다. */
    @Test
    fun `이 기능 이전에 기록된 행은 retrieval_kind가 NULL로 남는다`(): Unit = runBlocking {
        val id = knowledge("옛 기록")
        usageRepository.save(
            KnowledgeUsageEntity(
                qaTryId = qaTryId,
                knowledgeId = id,
                knowledgeVersion = 1,
                rank = 1,
                retrievedAt = Instant.now()
            )
        )

        assertThat(usageRows().single().retrievalKind).isNull()
    }

    /** step은 Agent가 실어 준다. 안 실어 주면 null이고, 그것이 지금까지의 동작이다. */
    @Test
    fun `검색 프레임의 step이 usage 행에 남는다`(): Unit = runBlocking {
        knowledgeWithVector("지식")

        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":3}""")
        assertThat(usageRows().single().step).isEqualTo(3)

        usageRepository.deleteAll()
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""")
        assertThat(usageRows().single().step).isNull()
    }

    // ------------------------------------------------------------- cited = true

    @Test
    fun `스텝 판정이 인용한 항목의 cited가 true가 된다`(): Unit = runBlocking {
        val used = knowledgeWithVector("쓴 지식")
        val unused = knowledgeWithVector("안 쓴 지식")

        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")
        reportStep(step = 1, usedIds = listOf(used))

        val byKnowledge = usageRows().associateBy { it.knowledgeId }
        assertThat(byKnowledge[used]?.cited).isTrue()
        // 판정 시점에는 "안 쓴 것"이 아직 확정되지 않는다 — 뒤 스텝에서 인용될 수 있다.
        assertThat(byKnowledge[unused]?.cited).isNull()
    }

    /**
     * 이웃으로 한 줄만 본 항목도 인용할 수 있어야 한다. Agent 쪽 가드가 `knows_of()`이고,
     * Orchestration은 애초에 이 런이 내보낸 행만 본다 — 두 규칙이 어긋나면 정당한 인용이 환각으로
     * 집계된다.
     */
    @Test
    fun `딸려온 이웃을 인용해도 기록된다`(): Unit = runBlocking {
        val hit = knowledgeWithVector("히트")
        val neighbour = knowledge("이웃")
        edge(hit, neighbour)

        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")
        reportStep(step = 1, usedIds = listOf(neighbour))

        assertThat(usageRows().single { it.knowledgeId == neighbour }.cited).isTrue()
    }

    /**
     * step은 조인 키가 아니라 기록되는 메타데이터다. 키로 삼으면 2번 스텝에서 검색해 3번 스텝에서
     * 쓴 인용이 어디에도 안 찍혀 증발한다.
     */
    @Test
    fun `앞선 스텝에서 검색한 것을 뒤 스텝에서 인용해도 찍힌다`(): Unit = runBlocking {
        val id = knowledgeWithVector("2번 스텝에서 읽은 것")

        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":2}""")
        reportStep(step = 5, usedIds = listOf(id))

        val row = usageRows().single()
        assertThat(row.cited).isTrue()
        assertThat(row.step).describedAs("기록된 step은 검색 시점 그대로다").isEqualTo(2)
    }

    /** 판정 이후에 난 검색까지 인용으로 물들면 인용률이 위로 새어 나간다. */
    @Test
    fun `판정 이후에 검색된 행은 소급해서 true가 되지 않는다`(): Unit = runBlocking {
        val id = knowledgeWithVector("나중에 읽은 것")

        reportStep(step = 1, usedIds = listOf(id))
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":2}""")

        assertThat(usageRows().single().cited)
            .describedAs("인용 시점보다 뒤에 난 검색이다")
            .isNull()
    }

    /**
     * 환각 인용률 자체가 모델 비교 지표다. 조용히 버리면 그 신호가 사라지고, 인용을 지어내는
     * 모델과 정직한 모델이 같은 점수를 받는다.
     */
    @Test
    fun `검색된 적 없는 id는 거부되고 그 수가 타임라인에 남는다`(): Unit = runBlocking {
        val real = knowledgeWithVector("진짜")

        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")
        reportStep(step = 1, usedIds = listOf(real), rawIds = listOf("999999", "not-a-number"))

        assertThat(usageRows().single().cited).isTrue()
        val logged = qaLogRepository.findAll().toList().mapNotNull { it.message }
        assertThat(logged).anyMatch { it.contains("cited 2 knowledge id(s) this run never retrieved") }
        assertRunning()
    }

    /** 판정 STATUS는 `result=null`이라 런을 끝내지 않는다. 인용이 그 규칙을 바꾸면 안 된다. */
    @Test
    fun `인용을 실은 스텝 판정 STATUS가 런을 끝내지 않는다`(): Unit = runBlocking {
        val id = knowledgeWithVector("지식")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")

        reportStep(step = 1, usedIds = listOf(id))

        assertRunning()
        assertThat(usageRows().single().cited).isTrue()
    }

    /** 기록이 런을 죽이면 안 된다 — 이 파일의 두 번째 이유다. */
    @Test
    fun `인용 기록이 실패해도 런이 살아 있다`(): Unit = runBlocking {
        val id = knowledgeWithVector("지식")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")
        blockCitationWrites()
        try {
            reportStep(step = 1, usedIds = listOf(id))
        } finally {
            allowCitationWrites()
        }

        assertRunning()
        assertThat(qaLogRepository.findAll().toList().mapNotNull { it.message })
            .anyMatch { it.contains("citation recording failed") }
        assertThat(usageRows().single().cited).isNull()
    }

    // ------------------------------------------------------------ cited = false

    /**
     * 확정이 없으면 NULL(보고 불가)과 false(미인용)가 영영 갈리지 않는다. "사장된 지식"이 이
     * 기록의 최종 목적인데 그것이 안 나온다.
     */
    @Test
    fun `종단 STATUS가 미인용 행을 false로 확정한다`(): Unit = runBlocking {
        val used = knowledgeWithVector("쓴 것")
        val unused = knowledgeWithVector("안 쓴 것")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문","step":1}""")
        reportStep(step = 1, usedIds = listOf(used))

        finishRun()

        val byKnowledge = usageRows().associateBy { it.knowledgeId }
        assertThat(byKnowledge[used]?.cited).isTrue()
        assertThat(byKnowledge[unused]?.cited).isFalse()
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("COMPLETED")
    }

    /**
     * 정상 종료에만 걸면 실패한 런은 영영 NULL로 남는다. 소켓이 죽는 것은 예외 상황이 아니라
     * 흔한 종료 경로다.
     */
    @Test
    fun `소켓 사망으로 실패한 런도 확정된다`(): Unit = runBlocking {
        knowledgeWithVector("아무도 안 쓴 것")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""")

        failureService.agentDisconnected(qaTryId)

        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("FAILED")
        assertThat(usageRows().single().cited).isFalse()
    }

    @Test
    fun `취소된 런도 확정된다`(): Unit = runBlocking {
        knowledgeWithVector("아무도 안 쓴 것")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""")

        failureService.cancelled(qaTryId, "운영자가 종료")

        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("CANCELLED")
        assertThat(usageRows().single().cited).isFalse()
    }

    /**
     * **경계는 qa_try이지 WS 세션이 아니다.** 세션 하나가 런의 시나리오들을 순차 실행하므로,
     * 세션 종료에 걸면 앞선 시나리오의 확정이 늦거나 누락된다.
     */
    @Test
    fun `한 세션의 시나리오들이 각자 자기 종료 시점에 확정된다`(): Unit = runBlocking {
        val runId = qaRunRepository.save(
            QaRunEntity(
                testRunId = testRunId,
                gameInstanceId = instanceId,
                startedBy = ownerId,
                agentSessionId = SESSION_ID,
                status = "RUNNING",
                // 시나리오가 활성될 때 라우터가 이 스냅샷을 그 try의 run_config로 새긴다
                // (activatePending). 표식이 여기 없으면 두 번째 시나리오가 확정 대상에서 빠진다.
                runConfig = Json.of(REPORTING_CONFIG),
                startedAt = Instant.now()
            )
        )!!.id!!
        // 첫 시나리오는 이 클래스가 이미 준비한 try를 런에 붙여 쓴다.
        qaTryRepository.save(qaTryRepository.findById(qaTryId)!!.copy(qaRunId = runId))
        val second = seedTry(status = "PENDING", runConfig = REPORTING_CONFIG, qaRunId = runId)

        val first = knowledgeWithVector("첫 시나리오가 읽은 것")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""")
        finishRun()

        assertThat(usageRows(qaTryId).single().cited)
            .describedAs("두 번째 시나리오가 아직 도는 중이어도 첫 시나리오는 이미 끝났다")
            .isFalse()

        // 두 번째 시나리오의 차례. 라우터가 PENDING을 RUNNING으로 올린다.
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""", tryId = second)
        assertThat(usageRows(second).single().cited).isNull()
        assertThat(usageRows(second).single().knowledgeId).isEqualTo(first)

        finishRun(tryId = second)
        assertThat(usageRows(second).single().cited).isFalse()
    }

    /**
     * 인용을 보고할 수 없었던 런까지 false로 찍으면, 그 시기의 지식이 통째로 "아무도 안 쓴 것"이
     * 된다. 판정은 추측이 아니라 `run_config`의 표식으로 한다.
     */
    @Test
    fun `인용을 보고하지 않는 구버전 런은 NULL로 남는다`(): Unit = runBlocking {
        val legacy = seedTry(
            status = "RUNNING",
            runConfig = LEGACY_CONFIG,
            gameInstanceId = gameInstance("legacy-instance")
        )
        knowledgeWithVector("지식")
        deliver("KNOWLEDGE_SEARCH", """{"query":"질문"}""", tryId = legacy)

        finishRun(tryId = legacy)

        assertThat(qaTryRepository.findById(legacy)!!.status).isEqualTo("COMPLETED")
        assertThat(usageRows(legacy).single().cited)
            .describedAs("false는 '보고 가능했는데 안 했다'는 뜻이라 여기 쓰면 거짓말이 된다")
            .isNull()
    }

    /** 대조군(knowledge_mode=off)은 usage 행 자체가 없다. 확정이 아무 일도 안 하고 끝나야 한다. */
    @Test
    fun `usage 행이 없는 런도 정상 종료한다`(): Unit = runBlocking {
        finishRun()

        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("COMPLETED")
        assertThat(usageRows()).isEmpty()
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun deliver(type: String, payload: String, tryId: Long = qaTryId) {
        inboundRouter.handle(
            QaAgentEnvelope(
                messageId = UUID.randomUUID().toString(),
                type = type,
                qaTryId = tryId.toString(),
                correlationId = null,
                timestamp = Instant.parse("2026-08-11T00:00:00Z"),
                payload = objectMapper.readTree(payload)
            )
        )
    }

    /** 스텝 판정 STATUS. `result`가 없으므로 런은 끝나지 않는다. */
    private suspend fun reportStep(
        step: Int,
        usedIds: List<Long> = emptyList(),
        rawIds: List<String> = emptyList(),
        tryId: Long = qaTryId
    ) {
        val ids = (usedIds.map { it.toString() } + rawIds).joinToString(",") { "\"$it\"" }
        deliver(
            "STATUS",
            """{"status":"COMPLETED","step":$step,"message":"스텝 $step 판정","used_knowledge_ids":[$ids]}""",
            tryId
        )
    }

    /** 종단 STATUS. `result`가 있어야 런이 끝난다. */
    private suspend fun finishRun(tryId: Long = qaTryId) {
        deliver("STATUS", """{"status":"COMPLETED","result":"PASSED","message":"런 종료"}""", tryId)
    }

    private suspend fun usageRows(tryId: Long = qaTryId) =
        usageRepository.findByQaTryIdOrderByIdAsc(tryId).toList()

    private suspend fun assertRunning() {
        assertThat(qaTryRepository.findById(qaTryId)!!.status).isEqualTo("RUNNING")
    }

    /**
     * cited UPDATE만 실패시킨다. 실제로 일어날 실패는 DB 장애이고, 그 경로를 그대로 재현하는 것이
     * 서비스를 대역으로 갈아 끼우는 것보다 정직하다 — 라우터가 삼키는 것이 바로 이 예외다.
     *
     * `NOT VALID`라 기존 행은 검사하지 않는다. 반드시 [allowCitationWrites]로 되돌려야 한다 —
     * 스위트가 DB 하나를 공유하므로 남으면 다음 테스트가 이유 없이 깨진다.
     */
    private suspend fun blockCitationWrites() {
        execute("ALTER TABLE knowledge_usage ADD CONSTRAINT ck_cited_test_block CHECK (cited IS NULL) NOT VALID")
    }

    private suspend fun allowCitationWrites() {
        execute("ALTER TABLE knowledge_usage DROP CONSTRAINT IF EXISTS ck_cited_test_block")
    }

    private suspend fun execute(sql: String) {
        databaseClient.sql(sql).fetch().rowsUpdated().awaitFirstOrNull()
    }

    private suspend fun knowledge(summary: String): Long =
        knowledgeRepository.save(
            KnowledgeEntity(
                projectId = projectId,
                source = "DOCS",
                tag = "RULE",
                summary = summary,
                description = "$summary 설명"
            )
        ).id!!

    private suspend fun knowledgeWithVector(summary: String): Long {
        val id = knowledge(summary)
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

    private suspend fun edge(from: Long, to: Long) {
        edgeRepository.save(
            KnowledgeEdgeEntity(
                projectId = projectId,
                fromKnowledgeId = from,
                toKnowledgeId = to,
                relation = "REFINES",
                note = "테스트 관계"
            )
        )
    }

    /**
     * @param gameInstanceId 기본은 이 스위트의 인스턴스. **동시에 활성인 try 둘은 같은 인스턴스에
     *   둘 수 없다**(`uk_qa_try_active_instance`) — 활성 런 둘이 필요한 테스트는 인스턴스를 따로
     *   만든다.
     */
    private suspend fun seedTry(
        status: String,
        runConfig: String,
        qaRunId: Long? = null,
        gameInstanceId: Long = instanceId
    ): Long =
        qaTryRepository.save(
            QaTryEntity(
                testScenarioId = scenarioId,
                gameInstanceId = gameInstanceId,
                qaRunId = qaRunId,
                startedBy = ownerId,
                // 응답을 돌려보낼 세션이 붙어 있어야 한다.
                agentSessionId = SESSION_ID,
                status = status,
                runConfig = Json.of(runConfig),
                startedAt = Instant.now()
            )
        )!!.id!!

    private suspend fun seed(owner: AuthenticatedUser) {
        ownerId = owner.userId.toLong()
        val now = Instant.now()
        val project = projectRepository.save(
            ProjectEntity(name = "knowledge-citation-project", genre = "ACTION", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now
            )
        )
        scenarioId = testScenarioRepository.save(
            TestScenarioEntity(projectId = projectId, payload = Json.of("{}"))
        )!!.id!!
        testRunId = testRunRepository.save(
            TestRunEntity(projectId = projectId, name = "citation-run", createdAt = now, updatedAt = now)
        )!!.id!!
        instanceId = gameInstance("instance")
        qaTryId = seedTry(status = "RUNNING", runConfig = REPORTING_CONFIG)
    }

    private suspend fun gameInstance(name: String): Long {
        val now = Instant.now()
        return gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId,
                name = name,
                platform = "UNITY",
                sdkUuid = UUID.randomUUID().toString(),
                createdAt = now,
                updatedAt = now
            )
        )!!.id!!
    }

    private suspend fun signIn(): AuthenticatedUser =
        oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "293",
                login = "citer",
                displayName = "citer",
                avatarUrl = null,
                email = "citer@example.com"
            )
        )!!
}
