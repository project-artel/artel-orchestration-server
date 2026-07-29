package kr.artel.orchestration.knowledge

import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.agent.KnowledgeEmbeddingAgent
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.repository.KnowledgeEmbeddingRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeEmbeddingBackfillWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.util.concurrent.atomic.AtomicLong

/**
 * 백필 워커 통합 테스트.
 *
 * 검증: 벡터가 없는 항목만 집는지, 한 tick 상한을 지키는지, 실패 시 `attempts`가 오르고 상한을 넘긴
 * 항목을 건너뛰는지, 두 워커가 같은 행을 집지 않는지(SKIP LOCKED), 소프트삭제된 항목을 건드리지
 * 않는지.
 *
 * Agent는 대역([FakeKnowledgeEmbeddingAgent])이다. ARTEL-184의 실제 엔드포인트와의 연동은 여기서
 * 검증하지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeEmbeddingBackfillIntegrationTest {

    @TestConfiguration
    class FakeAgentConfig {
        @Bean
        @Primary
        fun fakeAgent(properties: KnowledgeBackfillProperties): KnowledgeEmbeddingAgent =
            FakeKnowledgeEmbeddingAgent(properties.model)
    }

    @Autowired
    private lateinit var worker: KnowledgeEmbeddingBackfillWorker

    @Autowired
    private lateinit var knowledgeRepository: KnowledgeRepository

    @Autowired
    private lateinit var embeddingRepository: KnowledgeEmbeddingRepository

    @Autowired
    private lateinit var properties: KnowledgeBackfillProperties

    @Autowired
    private lateinit var databaseClient: DatabaseClient

    @Autowired
    private lateinit var transactionalOperator: TransactionalOperator

    @Autowired
    private lateinit var agent: KnowledgeEmbeddingAgent

    private val fake: FakeKnowledgeEmbeddingAgent get() = agent as FakeKnowledgeEmbeddingAgent

    companion object {
        // KnowledgeIntegrationTest와 같은 이유로 static이다: JUnit이 메서드마다 인스턴스를 새로
        // 만들어 인스턴스 필드면 projectId가 겹친다. 그쪽(9000번대)과 겹치지 않게 대역을 띄운다.
        private val projectSeq = AtomicLong(20_000)
    }

    @BeforeEach
    fun reset() = runBlocking {
        fake.failFor.clear()
        fake.embedFails = false
        // knowledge_embedding은 FK ON DELETE CASCADE라 knowledge를 지우면 함께 사라진다.
        knowledgeRepository.deleteAll()
    }

    // ---------------------------------------------------------------- helpers

    private suspend fun givenKnowledge(projectId: Long, count: Int): List<Long> =
        (1..count).map { index ->
            knowledgeRepository.save(
                KnowledgeEntity(
                    projectId = projectId,
                    source = "DOCS",
                    tag = "RULE",
                    summary = "요약$index",
                    description = "설명$index"
                )
            ).id!!
        }

    private suspend fun countRows(sql: String, vararg binds: Pair<String, Any>): Long {
        var spec = databaseClient.sql(sql)
        binds.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec.map { row -> row.get(0, java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitFirstOrNull() ?: 0L
    }

    private suspend fun vectorCount(knowledgeId: Long): Long = countRows(
        "SELECT COUNT(*) FROM knowledge_embedding WHERE knowledge_id = :id AND embedding IS NOT NULL",
        "id" to knowledgeId
    )

    private suspend fun pendingAttempts(knowledgeId: Long): Long = countRows(
        "SELECT COALESCE(MAX(attempts), 0) FROM knowledge_embedding WHERE knowledge_id = :id AND source_text IS NULL",
        "id" to knowledgeId
    )

    private suspend fun lastError(knowledgeId: Long): String? =
        databaseClient.sql("SELECT last_error FROM knowledge_embedding WHERE knowledge_id = :id AND source_text IS NULL")
            .bind("id", knowledgeId)
            .map { row -> row.get("last_error", String::class.java) }
            .one()
            .awaitFirstOrNull()

    // ------------------------------------------------------------------ tests

    @Test
    fun `벡터가 없는 항목을 채우고 이미 채운 항목은 다시 집지 않는다`(): Unit = runBlocking {
        val ids = givenKnowledge(projectSeq.incrementAndGet(), 3)

        val first = worker.runOnce()
        assertThat(first.succeeded).isEqualTo(3)
        assertThat(first.failed).isZero()
        ids.forEach { assertThat(vectorCount(it)).isEqualTo(FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM.toLong()) }

        // 두 번째 tick은 집을 것이 없어야 한다. 있으면 매 tick 임베딩을 다시 청구하는 것이다.
        val callsBefore = fake.embedCalls.get()
        val second = worker.runOnce()
        assertThat(second.claimed).isZero()
        assertThat(fake.embedCalls.get()).isEqualTo(callsBefore)
    }

    @Test
    fun `한 tick은 batchSize를 넘지 않는다`(): Unit = runBlocking {
        val total = properties.batchSize + 5
        givenKnowledge(projectSeq.incrementAndGet(), total)

        val result = worker.runOnce()

        assertThat(result.claimed).isEqualTo(properties.batchSize)
        assertThat(result.succeeded).isEqualTo(properties.batchSize)
        // 나머지는 다음 tick 몫으로 남아 있어야 한다.
        assertThat(countRows("SELECT COUNT(*) FROM knowledge_embedding WHERE embedding IS NOT NULL"))
            .isEqualTo((properties.batchSize * FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM).toLong())
    }

    @Test
    fun `실패하면 attempts가 오르고 last_error가 남는다`(): Unit = runBlocking {
        val id = givenKnowledge(projectSeq.incrementAndGet(), 1).single()
        fake.failFor += id

        val result = worker.runOnce()

        assertThat(result.failed).isEqualTo(1)
        assertThat(result.succeeded).isZero()
        assertThat(vectorCount(id)).isZero()
        assertThat(pendingAttempts(id)).isEqualTo(1)
        assertThat(lastError(id)).isNotNull()

        // 다음 tick이 다시 시도한다 — 실패가 항목을 큐에서 지우지는 않는다.
        worker.runOnce()
        assertThat(pendingAttempts(id)).isEqualTo(2)
    }

    @Test
    fun `상한을 넘긴 항목은 건너뛰되 행은 조회할 수 있다`(): Unit = runBlocking {
        val id = givenKnowledge(projectSeq.incrementAndGet(), 1).single()
        fake.failFor += id

        repeat(properties.maxAttempts) { worker.runOnce() }
        assertThat(pendingAttempts(id)).isEqualTo(properties.maxAttempts.toLong())

        // 상한에 닿은 뒤로는 집지 않는다. 영원히 실패하는 항목이 매 tick 워커를 점유하면 안 된다.
        val result = worker.runOnce()
        assertThat(result.claimed).isZero()
        assertThat(pendingAttempts(id)).isEqualTo(properties.maxAttempts.toLong())

        // 그래도 행은 남아 last_error와 함께 조회된다.
        assertThat(lastError(id)).isNotNull()
    }

    @Test
    fun `상한을 넘긴 항목이 있어도 다른 항목은 굶지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val poison = givenKnowledge(projectId, 1).single()
        fake.failFor += poison
        repeat(properties.maxAttempts) { worker.runOnce() }

        val healthy = givenKnowledge(projectId, 2)
        val result = worker.runOnce()

        assertThat(result.succeeded).isEqualTo(2)
        healthy.forEach { assertThat(vectorCount(it)).isEqualTo(FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM.toLong()) }
    }

    @Test
    fun `배치가 all-or-nothing으로 깨져도 멀쩡한 항목은 채워진다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val ids = givenKnowledge(projectId, 3)
        // 실제 /knowledge-queries는 한 항목만 실패해도 요청 전체를 422로 떨어뜨린다.
        fake.failFor += ids[1]

        val result = worker.runOnce()

        assertThat(result.succeeded).isEqualTo(2)
        assertThat(result.failed).isEqualTo(1)
        assertThat(vectorCount(ids[0])).isEqualTo(FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM.toLong())
        assertThat(vectorCount(ids[2])).isEqualTo(FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM.toLong())
        assertThat(vectorCount(ids[1])).isZero()
    }

    @Test
    fun `소프트삭제된 항목은 벡터를 만들지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val ids = givenKnowledge(projectId, 2)
        databaseClient.sql("UPDATE knowledge SET deleted_at = CURRENT_TIMESTAMP WHERE id = :id")
            .bind("id", ids[0])
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()

        val result = worker.runOnce()

        assertThat(result.succeeded).isEqualTo(1)
        assertThat(vectorCount(ids[0])).isZero()
        assertThat(vectorCount(ids[1])).isEqualTo(FakeKnowledgeEmbeddingAgent.QUERIES_PER_ITEM.toLong())
    }

    @Test
    fun `두 워커가 같은 행을 집지 않는다`(): Unit = runBlocking {
        givenKnowledge(projectSeq.incrementAndGet(), properties.batchSize * 2)
        embeddingRepository.seedPending("QUERY", properties.model, properties.batchSize * 2)

        // 첫 claim을 트랜잭션 안에 열어 둔 채로 두 번째 claim을 돌린다.
        // SKIP LOCKED가 없으면 두 번째는 첫 트랜잭션이 커밋될 때까지 **막힌다**. 그래서 겹치지
        // 않는 것만이 아니라 "기다리지 않았다"까지 봐야 이 테스트가 SKIP LOCKED를 증명한다.
        val holdMillis = 3_000L
        var secondClaimElapsed = Long.MAX_VALUE

        val (firstBatch, secondBatch) = coroutineScope {
            val held = async {
                transactionalOperator.executeAndAwait {
                    val claimed = embeddingRepository.claimPending(
                        "QUERY", properties.model, properties.maxAttempts, properties.batchSize
                    )
                    delay(holdMillis)
                    claimed
                }!!
            }
            val other = async {
                delay(300)
                val startedAt = System.currentTimeMillis()
                val claimed = embeddingRepository.claimPending(
                    "QUERY", properties.model, properties.maxAttempts, properties.batchSize
                )
                secondClaimElapsed = System.currentTimeMillis() - startedAt
                claimed
            }
            listOf(held, other).awaitAll()
        }

        assertThat(firstBatch).isNotEmpty()
        assertThat(secondBatch).isNotEmpty()
        val overlap = firstBatch.map { it.pendingId }.intersect(secondBatch.map { it.pendingId }.toSet())
        assertThat(overlap).isEmpty()
        assertThat(secondClaimElapsed)
            .describedAs("두 번째 claim이 첫 트랜잭션을 기다렸다면 SKIP LOCKED가 걸리지 않은 것이다")
            .isLessThan(holdMillis / 2)
    }
}
