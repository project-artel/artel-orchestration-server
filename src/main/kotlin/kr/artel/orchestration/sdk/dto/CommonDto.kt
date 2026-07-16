package kr.artel.orchestration.sdk.dto

/**
 * 웹소켓으로 들어오는 모든 메시지의 상위 공통 타입을 파악하기 위한 기본 메시지 클래스
 */
data class BaseMessage(
    val type: String
)

/**
 * sdkId 등록 요청 시 사용하는 DTO
 */
data class SdkIdRegistrationRequest(
    val sdkId: String
)
