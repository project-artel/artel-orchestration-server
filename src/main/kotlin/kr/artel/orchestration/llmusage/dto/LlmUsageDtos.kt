package kr.artel.orchestration.llmusage.dto

import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.NotNull
import jakarta.validation.constraints.PositiveOrZero
import jakarta.validation.constraints.Size
import kr.artel.orchestration.llmusage.entity.LlmUsageServiceType
import java.math.BigDecimal
import java.time.Instant

/**
 * agent가 보내는 사용량 배치. 상한 200은 agent 쪽 전송 배치 크기와 맞춘 계약값이다 —
 * 넘치면 agent가 나눠 보낸다. 빈 배열은 보낼 이유가 없으므로(호출이 없었다면 요청 자체가 없다)
 * 계약 위반으로 보고 400으로 거절한다.
 */
data class LlmUsageBatchRequest(
    @field:Valid
    @field:Size(min = 1, max = 200)
    val records: List<LlmUsageRecord> = emptyList()
)

/**
 * LLM 호출 한 건. 필드 이름은 agent-server(ARTEL-234)와 합의한 계약 그대로다.
 *
 * @property referenceId [service]가 가리키는 테이블의 id. FK가 아니며 null이어도 저장된다
 * (agent가 어떤 프로젝트/실행의 호출인지 모르는 경로가 있다 — 그 경우 지출만 남는다).
 * @property outputTokens `EMBEDDING`에서는 항상 0이다(임베딩은 토큰을 생성하지 않는다).
 * @property costUsd provider가 단가를 알려주지 않으면 없다.
 * @property calledAt agent가 provider를 실제로 호출한 시각(ISO-8601 UTC). 집계 기준 시각이다.
 */
data class LlmUsageRecord(
    @field:NotNull
    val service: LlmUsageServiceType? = null,

    val referenceId: Long? = null,

    @field:NotBlank
    @field:Size(max = 40)
    val provider: String = "",

    @field:NotBlank
    @field:Size(max = 80)
    val model: String = "",

    @field:PositiveOrZero
    val inputTokens: Int = 0,

    @field:PositiveOrZero
    val outputTokens: Int = 0,

    @field:PositiveOrZero
    val cachedInputTokens: Int = 0,

    @field:PositiveOrZero
    val reasoningTokens: Int = 0,

    val costUsd: BigDecimal? = null,

    @field:PositiveOrZero
    val latencyMs: Int = 0,

    @field:NotNull
    val calledAt: Instant? = null
)
