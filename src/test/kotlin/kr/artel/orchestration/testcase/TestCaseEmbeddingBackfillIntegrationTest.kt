package kr.artel.orchestration.testcase

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.testcase.config.TestCaseEmbeddingProperties
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseEmbeddingRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseEmbeddingBackfillWorker
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

/** V20의 vector(1024)와 같아야 한다. 다르면 INSERT가 거절된다. */
private const val DIMENSIONS = 1024

/**
 * test_case 벡터 백필 통합 테스트(ARTEL-216).
 *
 * 검증: 벡터 없는 케이스만 CONTENT 1벡터로 채우는지, 재실행 시 다시 집지 않는지, 한 tick 상한,
 * 임베딩 실패 시 attempts가 오르고 이후 성공하면 채우는지, 그리고 [discardFor] 무효화(내용 변경 시
 * 208 적재가 쓰는 경로)가 다음 tick 재임베딩으로 이어지는지.
 *
 * Agent `/embed`는 대역([FakeEmbeddingClient])이다 — 실제 임베딩 연동은 이 테스트의 관심사가 아니다.
 * knowledge와 달리 검색쿼리 생성이 없어 케이스당 벡터는 정확히 1개(CONTENT)다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseEmbeddingBackfillIntegrationTest {

    /** 검색어/본문을 결정적 벡터로 바꾸는 대역. 실패는 [embedFails]로 재현한다. */
    class FakeEmbeddingClient(private val model: String) : EmbeddingClient {
        var embedFails: Boolean = false
        val embedCalls = AtomicInteger()

        override suspend fun embed(texts: List<String>): EmbedResponse {
            embedCalls.incrementAndGet()
            if (embedFails) throw IllegalStateException("임베딩 실패(테스트)")
            return EmbedResponse(
                model = model,
                dimensions = DIMENSIONS,
                vectors = texts.map { text ->
                    val seed = text.hashCode().toDouble() / Int.MAX_VALUE
                    List(DIMENSIONS) { index -> seed + index * 1e-6 }
                }
            )
        }
    }

    @TestConfiguration
    class FakeClientConfig {
        @Bean
        @Primary
        fun fakeEmbeddingClient(properties: TestCaseEmbeddingProperties): FakeEmbeddingClient =
            FakeEmbeddingClient(properties.model)
    }

    @Autowired private lateinit var worker: TestCaseEmbeddingBackfillWorker
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var embeddingRepository: TestCaseEmbeddingRepository
    @Autowired private lateinit var properties: TestCaseEmbeddingProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var fake: FakeEmbeddingClient

    private val projectSeq = AtomicLong(70_000)

    @BeforeEach
    fun reset(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM test_case_embedding").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("DELETE FROM test_case").fetch().rowsUpdated().awaitSingle()
        fake.embedFails = false
    }

    @Test
    fun `벡터 없는 케이스에 CONTENT 1벡터를 채운다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val ids = (1..3).map { insertCase(projectId, "RULE", "검 구매 $it", "골드 10 이상", "골드 차감 + 검 획득") }

        val result = worker.runOnce()

        assertThat(result.succeeded).isEqualTo(3)
        assertThat(result.failed).isZero()
        ids.forEach { assertThat(vectorCount(it)).isEqualTo(1) }
        // CONTENT 본문에 케이스 제목/기대결과가 담긴다.
        assertThat(sourceTextsOf(ids[0]).single()).contains("검 구매 1").contains("골드 차감")
    }

    @Test
    fun `이미 채운 케이스는 다시 집지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        insertCase(projectId, "UI", "상점 열기", null, "상점 화면 진입")
        worker.runOnce()

        val second = worker.runOnce()

        assertThat(second.claimed).isZero()
    }

    @Test
    fun `한 tick 상한을 지킨다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        repeat(properties.batchSize + 2) { insertCase(projectId, "RULE", "케이스 $it", null, "기대 $it") }

        val result = worker.runOnce()

        assertThat(result.claimed).isEqualTo(properties.batchSize)
    }

    @Test
    fun `임베딩 실패 시 attempts가 오르고 이후 성공하면 채운다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = insertCase(projectId, "RULE", "결제 확정", "장바구니 있음", "결제 완료")

        fake.embedFails = true
        val failed = worker.runOnce()
        assertThat(failed.succeeded).isZero()
        assertThat(failed.failed).isEqualTo(1)
        assertThat(vectorCount(id)).isZero()

        fake.embedFails = false
        val ok = worker.runOnce()
        assertThat(ok.succeeded).isEqualTo(1)
        assertThat(vectorCount(id)).isEqualTo(1)
    }

    @Test
    fun `discardFor 후 다음 tick이 새 본문으로 재임베딩한다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = insertCase(projectId, "RULE", "검 판매", "검 보유", "골드 획득")
        worker.runOnce()
        assertThat(vectorCount(id)).isEqualTo(1)

        // 208 적재가 내용 변경 시 부르는 경로: 옛 벡터를 버린다.
        embeddingRepository.discardFor(id)
        assertThat(vectorCount(id)).isZero()

        val result = worker.runOnce()
        assertThat(result.succeeded).isEqualTo(1)
        assertThat(vectorCount(id)).isEqualTo(1)
    }

    private suspend fun insertCase(
        projectId: Long,
        category: String,
        title: String,
        precondition: String?,
        expected: String,
    ): Long = testCaseRepository.save(
        TestCaseEntity(
            projectId = projectId,
            category = category,
            title = title,
            precondition = precondition,
            expected = expected,
        )
    ).id!!

    private suspend fun vectorCount(caseId: Long): Long =
        databaseClient.sql(
            "SELECT count(*) AS c FROM test_case_embedding WHERE test_case_id = :id AND source_text IS NOT NULL"
        )
            .bind("id", caseId)
            .map { row: Readable -> row.get("c", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

    private suspend fun sourceTextsOf(caseId: Long): List<String> =
        databaseClient.sql(
            "SELECT source_text FROM test_case_embedding WHERE test_case_id = :id AND source_text IS NOT NULL"
        )
            .bind("id", caseId)
            .map { row: Readable -> row.get("source_text", String::class.java)!! }
            .flow()
            .toList()
}
