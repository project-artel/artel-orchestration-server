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
 * [cachedInputTokens]와 [cacheWriteTokens]는 [inputTokens]와의 관계가 다르다. 앞은 그 안에 든
 * 값이라 더하면 두 번 세고, 뒤는 별개로 청구되는 양이다.
 *
 * [costEstimated]는 [costUsd]가 provider가 청구한 값인지 우리가 토큰으로 계산한 값인지 가른다.
 * 둘의 유무가 묶여 있다(`ck_llm_usage_cost_origin`) — 금액이 없으면 가릴 것도 없다.
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
    @Column("cache_write_tokens") val cacheWriteTokens: Int = 0,
    @Column("reasoning_tokens") val reasoningTokens: Int = 0,
    @Column("cost_usd") val costUsd: BigDecimal? = null,
    @Column("cost_estimated") val costEstimated: Boolean? = costOriginOf(costUsd, null),
    @Column("latency_ms") val latencyMs: Int,
    @Column("called_at") val calledAt: Instant,
    @Column("created_at") val createdAt: Instant? = null
)

/**
 * 금액의 출처를 `ck_llm_usage_cost_origin` 이 요구하는 모양으로 맞춘다 — 금액이 있으면 출처가
 * 있고, 없으면 출처도 없다.
 *
 * **규칙이 DB 에만 있으면 부르는 쪽마다 그것을 기억해야 한다.** [LlmUsageEntity.costEstimated] 의
 * 기본값이 이것이라, 출처를 말하지 않고 만든 행도 제약을 안 깬다. 말한 값이 있으면 그것이 이긴다.
 *
 * 안 말했을 때의 답이 `false` 인 것은, 이 칸을 모르는 쪽은 provider 가 준 값만 실을 수 있기
 * 때문이다. 계산해 넣는 쪽은 이 칸을 알고 함께 보낸다.
 *
 * 금액이 없는데 출처만 온 경우는 버린다. 가릴 것이 없는 자리에 값이 앉으면 나중에 "계산했는데
 * 금액을 못 실은 행" 처럼 읽힌다.
 */
internal fun costOriginOf(costUsd: BigDecimal?, costEstimated: Boolean?): Boolean? =
    if (costUsd == null) null else costEstimated ?: false
