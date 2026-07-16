package kr.artel.orchestration.sdk.dto

/**
 * Agent 서버로부터 수신하여 SDK로 전달하는 ACTION 메시지 DTO (Agent -> Orchestrator -> SDK)
 */
data class ActionResponseDto(
    val type: String = "ACTION",
    val id: Long,
    val actions: List<ActionItemDto>
)

/**
 * ACTION 메시지 내부의 개별 액션 상세 정보 DTO
 */
data class ActionItemDto(
    val id: Long,
    val jsonrpc: String = "2.0",
    val method: String,
    val params: List<Any>
)
