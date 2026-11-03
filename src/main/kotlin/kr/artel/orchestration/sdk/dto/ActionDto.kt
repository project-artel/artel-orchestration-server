package kr.artel.orchestration.sdk.dto

import com.fasterxml.jackson.annotation.JsonInclude

/**
 * Agent 서버로부터 수신하여 SDK로 전달하는 ACTION 메시지 DTO (Agent -> Orchestrator -> SDK)
 */
data class ActionResponseDto(
    val type: String = "ACTION",
    val id: Long,
    val actions: List<ActionItemDto>,
    @get:JsonInclude(JsonInclude.Include.NON_NULL)
    val delivery: ActionDeliveryDto? = null,
)

data class ActionDeliveryDto(val processId: String, val expiresAtUnixMs: Long)

data class ActionAcceptedDto(val type: String, val requestId: Long, val processId: String)

/** 결과 본문은 기존 router로 전달하고, 복구에 필요한 필드만 여기서 읽는다. */
@com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown = true)
data class ActionDeliveryResultDto(val requestId: Long? = null, val processId: String? = null)

/**
 * ACTION 메시지 내부의 개별 액션 상세 정보 DTO
 */
data class ActionItemDto(
    val id: Long,
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<Any>
)
