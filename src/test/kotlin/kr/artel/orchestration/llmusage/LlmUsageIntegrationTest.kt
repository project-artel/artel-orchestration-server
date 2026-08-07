package kr.artel.orchestration.llmusage

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.llmusage.repository.LlmUsageRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.web.reactive.server.WebTestClient
import java.math.BigDecimal
import java.time.Instant

/**
 * llm_usage 수집 엔드포인트 통합 테스트(ARTEL-233).
 *
 * agent-server와의 계약이 JSON 표면 그 자체라 서비스가 아니라 HTTP로 두드린다 — 필드명(camelCase),
 * 배치 상한, 204 무본문 응답까지 계약대로인지 여기서만 확인할 수 있다. NUMERIC/timestamptz 매핑
 * 때문에 실제 PostgreSQL(컨테이너)을 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest
@AutoConfigureWebTestClient
class LlmUsageIntegrationTest {

    @Autowired private lateinit var webTestClient: WebTestClient
    @Autowired private lateinit var llmUsageRepository: LlmUsageRepository

    /** 리액티브 트랜잭션은 테스트 롤백이 안 되고 실 DB를 공유하므로 직접 비운다(FK가 없어 순서 무관). */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        llmUsageRepository.deleteAll()
    }

    @Test
    fun `stores every record of a batch and stamps the received time`(): Unit = runBlocking {
        val before = Instant.now()

        post(
            """{"records":[
                ${record("QA_RUN", 42)},
                ${record("EMBEDDING", 7)},
                ${record("SCENARIO", 13)}
            ]}"""
        ).expectStatus().isNoContent.expectBody().isEmpty

        val rows = llmUsageRepository.findAll().toList()
        assertThat(rows).hasSize(3)
        assertThat(rows.map { it.service }).containsExactlyInAnyOrder("QA_RUN", "EMBEDDING", "SCENARIO")
        assertThat(rows.map { it.referenceId }).containsExactlyInAnyOrder(42L, 7L, 13L)
        // createdAt은 컬럼 기본값이 아니라 서비스가 stamp한다 — R2DBC가 기본값을 되읽지 않기 때문.
        assertThat(rows).allSatisfy { row ->
            assertThat(row.createdAt).isNotNull()
            assertThat(row.createdAt).isAfterOrEqualTo(before.minusSeconds(1))
        }
        // calledAt은 agent가 호출한 시각 그대로 보존한다(수신 시각과 별개).
        assertThat(rows.map { it.calledAt }.distinct()).containsExactly(CALLED_AT)
        val qaRun = rows.single { it.service == "QA_RUN" }
        assertThat(qaRun.inputTokens).isEqualTo(12043)
        assertThat(qaRun.outputTokens).isEqualTo(318)
        assertThat(qaRun.cachedInputTokens).isEqualTo(10240)
        assertThat(qaRun.latencyMs).isEqualTo(3820)
        assertThat(qaRun.costUsd).isEqualByComparingTo(BigDecimal("0.021374"))
    }

    @Test
    fun `rejects a batch over the 200 record cap`(): Unit = runBlocking {
        val records = (1..201).joinToString(",") { record("QA_RUN", it.toLong()) }

        post("""{"records":[$records]}""").expectStatus().isBadRequest

        assertThat(llmUsageRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `rejects an empty batch`(): Unit = runBlocking {
        post("""{"records":[]}""").expectStatus().isBadRequest

        assertThat(llmUsageRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `stores a record without a reference id or cost`(): Unit = runBlocking {
        post(
            """{"records":[{
                "service":"KNOWLEDGE_QUERY",
                "provider":"openai",
                "model":"openai/text-embedding-3-small",
                "inputTokens":128,
                "outputTokens":0,
                "latencyMs":210,
                "calledAt":"$CALLED_AT"
            }]}"""
        ).expectStatus().isNoContent

        val row = llmUsageRepository.findAll().toList().single()
        assertThat(row.referenceId).isNull()
        assertThat(row.costUsd).isNull()
        // 생략 가능한 토큰 컬럼은 기본 0으로 떨어진다(nullable이면 집계마다 COALESCE가 붙는다).
        assertThat(row.cachedInputTokens).isZero()
        assertThat(row.reasoningTokens).isZero()
        // 임베딩 계열은 출력 토큰이 항상 0이다 — 이상 데이터가 아니다.
        assertThat(row.outputTokens).isZero()
    }

    private fun post(body: String): WebTestClient.ResponseSpec =
        webTestClient.post()
            .uri("/internal/llm-usage")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(body)
            .exchange()

    private fun record(service: String, referenceId: Long): String = """
        {
            "service":"$service",
            "referenceId":$referenceId,
            "provider":"anthropic",
            "model":"anthropic/claude-sonnet-5",
            "inputTokens":12043,
            "outputTokens":318,
            "cachedInputTokens":10240,
            "reasoningTokens":0,
            "costUsd":0.021374,
            "latencyMs":3820,
            "calledAt":"$CALLED_AT"
        }
    """.trimIndent()

    private companion object {
        /** PostgreSQL timestamptz(마이크로초)와 정확히 비교되도록 마이크로초 정밀도로 고정한다. */
        val CALLED_AT: Instant = Instant.parse("2026-08-04T07:31:22.481000Z")
    }
}
