package kr.artel.orchestration.llmusage.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.math.BigDecimal
import java.time.Instant

/**
 * LLM 호출 한 건의 사용량·비용 기록. agent가 배치로 보낸 것을 그대로 적재한다.
 *
 * [referenceId]는 [service] 값에 따라 다른 테이블을 가리키는 다형 참조라 FK가 없다(V22 주석 참고).
 * 대상 행이 지워져도 지출 기록은 남아야 하므로 null이어도 정상이다.
 *
 * [calledAt]은 agent가 provider를 실제로 호출한 시각이고 [createdAt]은 우리가 배치를 받아 저장한
 * 수신 시각이다. **기간 집계는 항상 [calledAt] 기준**이다.
 *
 * [createdAt]은 컬럼 기본값에 맡기지 않고 서비스에서 stamp한다 — R2DBC는 insert 후 기본값 컬럼을
 * 다시 읽어오지 않아, 그러지 않으면 저장된 엔티티의 createdAt이 null로 나온다(IssueEntity와 동일).
 */
@Table("llm_usage")
data class LlmUsageEntity(
    @Id val id: Long? = null,
    val service: String,
    @Column("reference_id") val referenceId: Long? = null,
    val provider: String,
    val model: String,
    @Column("input_tokens") val inputTokens: Int,
    @Column("output_tokens") val outputTokens: Int,
    @Column("cached_input_tokens") val cachedInputTokens: Int = 0,
    @Column("reasoning_tokens") val reasoningTokens: Int = 0,
    @Column("cost_usd") val costUsd: BigDecimal? = null,
    @Column("latency_ms") val latencyMs: Int,
    @Column("called_at") val calledAt: Instant,
    @Column("created_at") val createdAt: Instant? = null
)
