package kr.artel.orchestration.testcase

import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.agent.EmbedResponse
import kr.artel.orchestration.common.embedding.agent.EmbeddingClient
import kr.artel.orchestration.testcase.config.TestCaseEmbeddingProperties
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseQueryEmbeddingException
import kr.artel.orchestration.testcase.service.TestCaseSearchService
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
import java.util.concurrent.atomic.AtomicLong

/** V20의 vector(1024)와 같아야 한다. */
private const val DIMENSIONS = 1024

/** 좌표축 단위 벡터. 서로 다른 축은 코사인 거리 1(완전 직교), 같은 축은 0. */
private fun axis(index: Int): List<Double> = List(DIMENSIONS) { if (it == index) 1.0 else 0.0 }

/**
 * TestCase 벡터 검색 통합 테스트(ARTEL-206).
 *
 * 검증: 프로젝트 격리, category 필터, 결과 상한, 빈 결과, 랭킹(가까운 순), 대기 행(벡터 없음) 제외,
 * 모델 불일치 시 예외.
 *
 * 벡터는 백필 워커로 만들지 않고 좌표축 벡터를 직접 심는다 — 거리를 계산 가능하게 해 순위를 단정한다.
 * 검색어 임베딩은 [FixedQueryEmbeddingClient]가 대신한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseVectorSearchIntegrationTest {

    /** 검색어를 미리 등록한 벡터로 바꾸는 대역. 등록 안 된 검색어는 실패시켜 실수를 드러낸다. */
    class FixedQueryEmbeddingClient(private val model: String) : EmbeddingClient {
        val vectorsByQuery: MutableMap<String, List<Double>> = mutableMapOf()
        var modelOverride: String? = null

        override suspend fun embed(texts: List<String>): EmbedResponse {
            val vectors = texts.map {
                vectorsByQuery[it] ?: throw IllegalStateException("등록되지 않은 검색어: $it")
            }
            return EmbedResponse(model = modelOverride ?: model, dimensions = DIMENSIONS, vectors = vectors)
        }
    }

    @TestConfiguration
    class FixedClientConfig {
        @Bean
        @Primary
        fun fixedQueryEmbeddingClient(properties: TestCaseEmbeddingProperties): FixedQueryEmbeddingClient =
            FixedQueryEmbeddingClient(properties.model)
    }

    @Autowired private lateinit var searchService: TestCaseSearchService
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var properties: TestCaseEmbeddingProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var fake: FixedQueryEmbeddingClient

    private val projectSeq = AtomicLong(80_000)
    private val model: String get() = properties.model

    @BeforeEach
    fun reset(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM test_case_embedding").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("DELETE FROM test_case").fetch().rowsUpdated().awaitSingle()
        fake.vectorsByQuery.clear()
        fake.modelOverride = null
        fake.vectorsByQuery[NEAR] = axis(0)
    }

    @Test
    fun `다른 프로젝트의 케이스는 새지 않는다`(): Unit = runBlocking {
        val mine = projectSeq.incrementAndGet()
        val other = projectSeq.incrementAndGet()
        val a = insertCase(mine, "RULE", "구매")
        insertVector(a, axis(0))
        val x = insertCase(other, "RULE", "구매")
        insertVector(x, axis(0))

        val result = searchService.search(mine, NEAR, null, 10)

        assertThat(result.results.map { it.id }).containsExactly(a.toString())
    }

    @Test
    fun `category로 좁힐 수 있다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        val rule = insertCase(project, "RULE", "골드 차감")
        insertVector(rule, axis(0))
        val ui = insertCase(project, "UI", "상점 버튼")
        insertVector(ui, axis(0))

        val ruleOnly = searchService.search(project, NEAR, "RULE", 10)

        assertThat(ruleOnly.results.map { it.id }).containsExactly(rule.toString())
    }

    @Test
    fun `가까운 순으로 정렬된다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        val near = insertCase(project, "RULE", "구매")
        insertVector(near, axis(0))
        val far = insertCase(project, "RULE", "판매")
        insertVector(far, axis(1))

        val result = searchService.search(project, NEAR, null, 10)

        assertThat(result.results.map { it.id }).containsExactly(near.toString(), far.toString())
        assertThat(result.results.first().score).isGreaterThan(result.results.last().score)
    }

    @Test
    fun `결과 상한을 지킨다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        repeat(5) { insertVector(insertCase(project, "RULE", "케이스 $it"), axis(0)) }

        val result = searchService.search(project, NEAR, null, 3)

        assertThat(result.results).hasSize(3)
    }

    @Test
    fun `벡터가 없으면 빈 결과다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        insertCase(project, "RULE", "아직 임베딩 안 됨") // 벡터 없음

        val result = searchService.search(project, NEAR, null, 10)

        assertThat(result.results).isEmpty()
    }

    @Test
    fun `대기 행(벡터 없음)은 결과에서 빠진다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        val ready = insertCase(project, "RULE", "완성")
        insertVector(ready, axis(0))
        val pending = insertCase(project, "RULE", "대기중")
        insertPending(pending)

        val result = searchService.search(project, NEAR, null, 10)

        assertThat(result.results.map { it.id }).containsExactly(ready.toString())
    }

    @Test
    fun `모델이 설정과 다르면 예외를 던진다`(): Unit = runBlocking {
        val project = projectSeq.incrementAndGet()
        insertVector(insertCase(project, "RULE", "구매"), axis(0))
        fake.modelOverride = "openai/some-other-embedding-model"

        val error = runCatching { searchService.search(project, NEAR, null, 10) }.exceptionOrNull()

        assertThat(error).isInstanceOf(TestCaseQueryEmbeddingException::class.java)
    }

    private suspend fun insertCase(projectId: Long, category: String, title: String): Long =
        testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId,
                category = category,
                title = title,
                precondition = null,
                expected = "$title 기대결과",
            )
        ).id!!

    private suspend fun insertVector(caseId: Long, vector: List<Double>) {
        databaseClient.sql(
            """
            INSERT INTO test_case_embedding (test_case_id, kind, model, source_text, embedding)
            VALUES (:id, 'CONTENT', :model, :text, CAST(:emb AS vector))
            """.trimIndent()
        )
            .bind("id", caseId)
            .bind("model", model)
            .bind("text", "case-$caseId")
            .bind("emb", EmbeddedText("case-$caseId", vector).toVectorLiteral())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private suspend fun insertPending(caseId: Long) {
        databaseClient.sql(
            "INSERT INTO test_case_embedding (test_case_id, kind, model) VALUES (:id, 'CONTENT', :model)"
        )
            .bind("id", caseId)
            .bind("model", model)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    private companion object {
        const val NEAR = "가까운 검색어"
    }
}
