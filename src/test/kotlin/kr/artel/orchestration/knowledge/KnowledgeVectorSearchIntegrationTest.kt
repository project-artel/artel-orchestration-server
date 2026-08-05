package kr.artel.orchestration.knowledge

import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.config.KnowledgeSearchProperties
import kr.artel.orchestration.knowledge.entity.KnowledgeMode
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeQueryEmbeddingException
import kr.artel.orchestration.knowledge.service.KnowledgeSearchService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.data.Offset
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
import java.util.concurrent.atomic.AtomicLong

/** V18의 vector(1024)와 같아야 한다. 다르면 INSERT가 거절된다. */
private const val DIMENSIONS = 1024

/**
 * knowledge 벡터 검색 통합 테스트(ARTEL-186).
 *
 * 검증: 프로젝트 격리, `knowledge_id` 접기, 결과 개수 상한, 빈 결과, tag/source 필터, 소프트삭제 제외,
 * 백필 대기 행(벡터 없음) 제외.
 *
 * **벡터를 백필 워커로 만들지 않고 직접 넣는다.** 워커가 만드는 벡터는 해시 기반이라 어느 항목이 더
 * 가까운지 예측할 수 없고, 순위를 단정할 수 없으면 접기와 상한을 검증할 수 없다. 여기서는 좌표축
 * 벡터를 직접 심어 거리를 계산 가능하게 만든다.
 *
 * 검색어 임베딩은 [FixedQueryEmbeddingAgent]가 대신한다 — 실제 `/embed`와의 연동은 이 테스트의
 * 관심사가 아니다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeVectorSearchIntegrationTest {

    /**
     * 검색어를 미리 등록한 벡터로 바꿔 주는 대역. 등록되지 않은 검색어는 실패시켜, 테스트가
     * 의도하지 않은 검색어로 조용히 엉뚱한 벡터를 쓰는 일을 막는다.
     */
    class FixedQueryEmbeddingAgent(private val model: String) : EmbeddingClient {
        val vectorsByQuery: MutableMap<String, List<Double>> = mutableMapOf()

        /** true면 `/embed`가 실패한다(Agent 장애 재현). */
        var embedFails: Boolean = false

        /** 설정과 다른 모델 slug를 돌려준다(모델 불일치 재현). */
        var modelOverride: String? = null

        /** `/embed` 호출 횟수. knowledge_mode=off가 임베딩을 아예 부르지 않는지 보는 데 쓴다. */
        var embedCalls: Int = 0

        override suspend fun embed(texts: List<String>): EmbedResponse {
            embedCalls++
            if (embedFails) throw IllegalStateException("임베딩 실패(테스트)")
            val vectors = texts.map {
                vectorsByQuery[it] ?: throw IllegalStateException("등록되지 않은 검색어: $it")
            }
            return EmbedResponse(
                model = modelOverride ?: model,
                dimensions = DIMENSIONS,
                vectors = vectors
            )
        }
    }

    @TestConfiguration
    class FixedAgentConfig {
        @Bean
        @Primary
        fun fixedAgent(properties: KnowledgeBackfillProperties): FixedQueryEmbeddingAgent =
            FixedQueryEmbeddingAgent(properties.model)
    }

    @Autowired private lateinit var searchService: KnowledgeSearchService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var backfillProperties: KnowledgeBackfillProperties
    @Autowired private lateinit var searchProperties: KnowledgeSearchProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var agent: EmbeddingClient

    private val fake: FixedQueryEmbeddingAgent get() = agent as FixedQueryEmbeddingAgent

    companion object {
        // 다른 knowledge 테스트(9000·20000번대)와 겹치지 않는 대역.
        private val projectSeq = AtomicLong(30_000)

        private const val NEAR = "가까운 검색어"

        /** 스코프 런 두 개. 값 자체는 의미가 없다 — 서로 다르고 NULL이 아니면 된다. */
        private val SCOPE_A = KnowledgeScope.of(7_001L)
        private val SCOPE_B = KnowledgeScope.of(7_002L)
    }

    @BeforeEach
    fun reset() = runBlocking {
        fake.vectorsByQuery.clear()
        fake.embedFails = false
        fake.modelOverride = null
        fake.embedCalls = 0
        // knowledge_embedding은 FK ON DELETE CASCADE라 knowledge를 지우면 함께 사라진다.
        knowledgeRepository.deleteAll()
        // 검색어는 0번 축을 가리킨다. 0번 축 벡터를 가진 항목이 거리 0으로 가장 가깝다.
        fake.vectorsByQuery[NEAR] = axis(0)
    }

    // ---------------------------------------------------------------- helpers

    /** 한 축만 1인 벡터. 서로 다른 축끼리는 코사인 거리가 정확히 1이다. */
    private fun axis(index: Int): List<Double> = List(DIMENSIONS) { if (it == index) 1.0 else 0.0 }

    /**
     * [primary] 축에 [secondary] 축을 [weight]만큼 섞은 벡터.
     * `axis(primary)`와의 코사인 거리는 `1 - 1/sqrt(1 + weight²)`로, weight가 클수록 멀어진다.
     */
    private fun blend(primary: Int, secondary: Int, weight: Double): List<Double> =
        List(DIMENSIONS) {
            when (it) {
                primary -> 1.0
                secondary -> weight
                else -> 0.0
            }
        }

    private suspend fun givenKnowledge(
        projectId: Long,
        summary: String,
        tag: KnowledgeTag = KnowledgeTag.RULE,
        source: KnowledgeSource = KnowledgeSource.DOCS,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION,
        shadowsId: Long? = null,
        deletedAt: Instant? = null
    ): Long = knowledgeRepository.save(
        KnowledgeEntity(
            projectId = projectId,
            scopeId = scope.id,
            shadowsId = shadowsId,
            deletedAt = deletedAt,
            source = source.name,
            tag = tag.name,
            summary = summary,
            description = "$summary 설명"
        )
    ).id!!

    /** 완성 행(벡터가 있는 행)을 심는다. */
    private suspend fun givenVector(knowledgeId: Long, vector: List<Double>, text: String = "검색쿼리") {
        databaseClient.sql(
            """
            INSERT INTO knowledge_embedding (knowledge_id, kind, model, source_text, embedding)
            VALUES (:knowledgeId, 'QUERY', :model, :text, CAST(:embedding AS vector))
            """.trimIndent()
        )
            .bind("knowledgeId", knowledgeId)
            .bind("model", backfillProperties.model)
            .bind("text", text)
            .bind("embedding", EmbeddedText(text, vector).toVectorLiteral())
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    /** 백필 대기 행(벡터 없음)을 심는다. V18의 큐 센티널과 같은 모양이다. */
    private suspend fun givenPendingRow(knowledgeId: Long) {
        databaseClient.sql(
            """
            INSERT INTO knowledge_embedding (knowledge_id, kind, model)
            VALUES (:knowledgeId, 'QUERY', :model)
            """.trimIndent()
        )
            .bind("knowledgeId", knowledgeId)
            .bind("model", backfillProperties.model)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    // 서비스는 스코프와 모드를 기본값 없이 요구한다(프로덕션 코드에서 빠뜨릴 수 없게). 테스트에서만
    // 지금까지의 동작을 기본값으로 주고, 스코프·모드 테스트에서 명시한다.
    private suspend fun search(
        projectId: Long,
        tags: List<KnowledgeTag> = emptyList(),
        source: KnowledgeSource? = null,
        limit: Int? = null,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION,
        mode: KnowledgeMode = KnowledgeMode.LEARNING
    ) = searchService.search(projectId, scope, mode, NEAR, tags, source, limit)

    // ------------------------------------------------------------------ tests

    @Test
    fun `가까운 항목을 먼저 돌려주고 점수는 코사인 유사도다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val near = givenKnowledge(projectId, "정확히 일치")
        val far = givenKnowledge(projectId, "덜 일치")
        givenVector(near, axis(0))
        givenVector(far, blend(0, 1, 0.5))

        val response = search(projectId)

        assertThat(response.results.map { it.id }).containsExactly(near.toString(), far.toString())
        assertThat(response.model).isEqualTo(backfillProperties.model)
        assertThat(response.query).isEqualTo(NEAR)
        // axis(0)과 자기 자신의 코사인 유사도는 1이다.
        assertThat(response.results[0].score).isCloseTo(1.0, Offset.offset(1e-5))
        // blend(0,1,0.5)와 axis(0)의 유사도는 1/sqrt(1.25) ≈ 0.894.
        assertThat(response.results[1].score).isCloseTo(0.8944, Offset.offset(1e-3))
        assertThat(response.results[0].summary).isEqualTo("정확히 일치")
        assertThat(response.results[0].description).isEqualTo("정확히 일치 설명")
    }

    /**
     * 이 테스트가 이 이슈의 핵심이다. 항목당 QUERY 벡터가 3개라 접지 않으면 같은 항목이 top-k를
     * 여러 칸 차지해 top-10이 실질 top-3이 된다.
     */
    @Test
    fun `같은 knowledge의 벡터가 여러 개여도 한 번만 나오고 가장 가까운 거리를 점수로 쓴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val multi = givenKnowledge(projectId, "벡터 셋")
        val single = givenKnowledge(projectId, "벡터 하나")
        // 한 항목에 3개. 실제 백필이 만드는 모양(ARTEL-184: 항목당 검색쿼리 3개)과 같다.
        givenVector(multi, axis(0), "쿼리1")
        givenVector(multi, blend(0, 1, 0.5), "쿼리2")
        givenVector(multi, axis(1), "쿼리3")
        givenVector(single, blend(0, 1, 0.5))

        val response = search(projectId)

        assertThat(response.results).hasSize(2)
        assertThat(response.results.map { it.id })
            .describedAs("접지 않으면 multi가 세 칸을 차지한다")
            .containsExactly(multi.toString(), single.toString())
        // 세 벡터 중 가장 가까운 것(axis(0), 거리 0)이 그 항목의 점수여야 한다.
        assertThat(response.results[0].score).isCloseTo(1.0, Offset.offset(1e-5))
    }

    @Test
    fun `결과 개수는 상한을 넘지 않고 요청이 더 커도 잘린다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        repeat(searchProperties.maxLimit + 5) { index ->
            val id = givenKnowledge(projectId, "항목$index")
            givenVector(id, blend(0, 1, 0.01 * index))
        }

        assertThat(search(projectId, limit = searchProperties.maxLimit + 100).results)
            .hasSize(searchProperties.maxLimit)
        assertThat(search(projectId, limit = 3).results).hasSize(3)
        // 지정하지 않으면 기본값.
        assertThat(search(projectId).results).hasSize(searchProperties.defaultLimit)
        // 0이나 음수는 거절이 아니라 1로 올린다.
        assertThat(search(projectId, limit = 0).results).hasSize(1)
    }

    @Test
    fun `다른 프로젝트의 지식은 새지 않는다`(): Unit = runBlocking {
        val mine = projectSeq.incrementAndGet()
        val theirs = projectSeq.incrementAndGet()
        val ours = givenKnowledge(mine, "우리 것")
        val hidden = givenKnowledge(theirs, "남의 것")
        givenVector(ours, blend(0, 1, 0.9))
        // 남의 프로젝트 것이 훨씬 가깝다 — 그래도 나와서는 안 된다.
        givenVector(hidden, axis(0))

        val response = search(mine)

        assertThat(response.results).hasSize(1)
        assertThat(response.results.single().summary).isEqualTo("우리 것")
        assertThat(search(theirs).results.single().summary).isEqualTo("남의 것")
    }

    @Test
    fun `벡터가 없거나 결과가 비면 오류가 아니라 빈 결과다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        // 1. 프로젝트에 knowledge 자체가 없다.
        assertThat(search(projectId).results).isEmpty()

        // 2. knowledge는 있는데 백필이 아직 안 돌았다(대기 행만 있다). 백필은 비동기라 정상 상태다.
        val waiting = givenKnowledge(projectId, "아직 백필 안 됨")
        givenPendingRow(waiting)
        val response = search(projectId)
        assertThat(response.results).isEmpty()
        assertThat(response.model).isEqualTo(backfillProperties.model)
    }

    @Test
    fun `tag와 source로 좁힐 수 있다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val docsRule = givenKnowledge(projectId, "문서 규칙", KnowledgeTag.RULE, KnowledgeSource.DOCS)
        val qaControl = givenKnowledge(projectId, "QA 조작", KnowledgeTag.CONTROL, KnowledgeSource.QA)
        val qaUi = givenKnowledge(projectId, "QA 화면", KnowledgeTag.UI, KnowledgeSource.QA)
        listOf(docsRule, qaControl, qaUi).forEach { givenVector(it, axis(0)) }

        assertThat(search(projectId, limit = 10).results).hasSize(3)
        assertThat(search(projectId, tags = listOf(KnowledgeTag.RULE)).results.map { it.summary })
            .containsExactly("문서 규칙")
        // 여러 tag는 합집합이다. 소비자가 자기 태스크에 맞는 tag 묶음을 뽑는다.
        assertThat(search(projectId, tags = listOf(KnowledgeTag.RULE, KnowledgeTag.UI)).results)
            .hasSize(2)
        assertThat(search(projectId, source = KnowledgeSource.QA).results).hasSize(2)
        assertThat(
            search(projectId, tags = listOf(KnowledgeTag.CONTROL), source = KnowledgeSource.QA).results
                .map { it.summary }
        ).containsExactly("QA 조작")
        // 걸러낼 것이 전부면 빈 결과지 오류가 아니다.
        assertThat(search(projectId, tags = listOf(KnowledgeTag.OBJECTIVE)).results).isEmpty()
    }

    @Test
    fun `소프트삭제된 항목은 검색되지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val alive = givenKnowledge(projectId, "살아있음")
        val doomed = givenKnowledge(projectId, "지워짐")
        givenVector(alive, blend(0, 1, 0.9))
        // 지워진 쪽이 더 가깝다 — 필터가 빠지면 이 테스트가 잡는다.
        givenVector(doomed, axis(0))
        knowledgeRepository.save(knowledgeRepository.findById(doomed)!!.copy(deletedAt = Instant.now()))

        val response = search(projectId)

        assertThat(response.results.map { it.summary }).containsExactly("살아있음")
    }

    @Test
    fun `Agent 임베딩 실패는 빈 결과가 아니라 오류로 올라간다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        givenVector(givenKnowledge(projectId, "있음"), axis(0))
        fake.embedFails = true

        // 호출자(라우터)가 "없음"과 "고장"을 구분할 수 있어야 ERROR 프레임으로 답할 수 있다.
        val error = runCatching { search(projectId) }.exceptionOrNull()
        assertThat(error).isNotNull()
    }

    @Test
    fun `Agent가 다른 모델로 임베딩하면 그럴듯한 순위 대신 실패한다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        givenVector(givenKnowledge(projectId, "있음"), axis(0))
        fake.modelOverride = "openai/some-other-embedding-model"

        val error = runCatching { search(projectId) }.exceptionOrNull()
        // common/error 계층(ARTEL-193)을 따른다 — 계층 밖 예외를 만들면 이 검색이 나중에 HTTP로
        // 노출될 때 매핑이 빠진 채로 500이 된다.
        assertThat(error).isInstanceOf(KnowledgeQueryEmbeddingException::class.java)
        assertThat(error).isInstanceOf(ApiException::class.java)
        assertThat(error!!.message).contains("openai/some-other-embedding-model")
    }

    // ---------------------------------------------------------- 스코프 격리 (ARTEL-256)

    /**
     * 검색은 지식창고를 읽는 가장 넓은 경로다. 목록 조회에만 스코프를 걸고 여기를 빠뜨리면 격리가
     * 사실상 없는 것이 되는데, 결과가 그럴듯해서 아무도 못 알아챈다.
     */
    @Test
    fun `검색은 baseline과 자기 스코프만 본다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val baseline = givenKnowledge(projectId, "운영 지식")
        val ofA = givenKnowledge(projectId, "A의 지식", scope = SCOPE_A)
        val ofB = givenKnowledge(projectId, "B의 지식", scope = SCOPE_B)
        listOf(baseline, ofA, ofB).forEach { givenVector(it, axis(0)) }

        assertThat(search(projectId, scope = SCOPE_A).results.map { it.id })
            .containsExactlyInAnyOrder(baseline.toString(), ofA.toString())
        assertThat(search(projectId, scope = SCOPE_B).results.map { it.id })
            .containsExactlyInAnyOrder(baseline.toString(), ofB.toString())
        // 운영 런은 실험 지식을 보지 않는다 — 이 기능 이전과 결과가 같다.
        assertThat(search(projectId).results.map { it.id }).containsExactly(baseline.toString())
    }

    /**
     * 그림자가 가리는 baseline은 검색 결과에서 빠져야 한다. 빠뜨리면 **원본과 수정본이 둘 다** 나와
     * Agent가 같은 지식의 두 판본을 함께 읽는다.
     */
    @Test
    fun `수정 그림자가 가리는 baseline은 검색에서 빠진다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val baseline = givenKnowledge(projectId, "옛 내용")
        val shadow = givenKnowledge(projectId, "A가 고친 내용", scope = SCOPE_A, shadowsId = baseline)
        givenVector(baseline, axis(0))
        givenVector(shadow, axis(0))

        assertThat(search(projectId, scope = SCOPE_A).results.map { it.id }).containsExactly(shadow.toString())
        assertThat(search(projectId).results.map { it.id }).containsExactly(baseline.toString())
        assertThat(search(projectId, scope = SCOPE_B).results.map { it.id }).containsExactly(baseline.toString())
    }

    /**
     * 툼스톤도 baseline을 가려야 한다. 툼스톤 자신은 `deleted_at`이 있어 빠지므로, 가리지 않으면
     * 스코프 런이 자기가 지운 항목을 계속 돌려받는다.
     *
     * 툼스톤에 벡터를 심어 두는 것이 이 테스트의 요점이다 — 실제로는 백필이 삭제된 행을 건너뛰지만,
     * 검색이 `deleted_at`만 믿고 있으면 여기서 툼스톤이 결과로 새어 나온다.
     */
    @Test
    fun `툼스톤이 가리는 baseline은 검색에서 빠지고 툼스톤도 나오지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val baseline = givenKnowledge(projectId, "지워질 운영 지식")
        val tombstone = givenKnowledge(
            projectId, "지워질 운영 지식",
            scope = SCOPE_A, shadowsId = baseline, deletedAt = Instant.now()
        )
        givenVector(baseline, axis(0))
        givenVector(tombstone, axis(0))

        assertThat(search(projectId, scope = SCOPE_A).results).isEmpty()
        assertThat(search(projectId).results.map { it.id }).containsExactly(baseline.toString())
    }

    // ------------------------------------------------------- knowledge_mode (ARTEL-256)

    /**
     * `off`는 지식 없이 도는 대조군이다. 오류가 아니라 정상적인 빈 결과여야 한다 — 오류로 답하면
     * Agent가 도구 실패로 보고 재시도해, 없애려던 변수가 다른 축으로 다시 들어온다.
     */
    @Test
    fun `knowledge_mode off는 지식이 있어도 빈 결과를 준다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        givenVector(givenKnowledge(projectId, "있음"), axis(0))
        val embedCallsBefore = fake.embedCalls

        val response = search(projectId, mode = KnowledgeMode.OFF)

        assertThat(response.results).isEmpty()
        assertThat(response.query).isEqualTo(NEAR)
        assertThat(response.model).isEqualTo(backfillProperties.model)
        // 어차피 버릴 벡터를 만들지 않는다. 대조군 arm에서만 /embed 비용과 지연이 붙으면
        // 그 지연 자체가 새 변수가 된다.
        assertThat(fake.embedCalls).isEqualTo(embedCallsBefore)
    }

    /** `frozen`은 읽기만 막지 않는다 — 검색은 평소대로 돈다. 쓰기 차단은 라우터의 몫이다. */
    @Test
    fun `knowledge_mode frozen은 읽기를 막지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = givenKnowledge(projectId, "있음")
        givenVector(id, axis(0))

        assertThat(search(projectId, mode = KnowledgeMode.FROZEN).results.map { it.id })
            .containsExactly(id.toString())
    }
}
