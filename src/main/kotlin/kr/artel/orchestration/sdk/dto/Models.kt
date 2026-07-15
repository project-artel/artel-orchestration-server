package kr.artel.orchestration.sdk.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 웹소켓 메시지의 성격을 구분하는 열거형(Enum)
 */
enum class MessageType {
    SCAN,    // 게임 내 C# 클래스 구조 스캔 데이터 전송용
    COMMAND, // 서버에서 클라이언트로 테스트 실행 명령 푸시용
    ERROR    // 에러 발생 알림용
}

/**
 * 웹소켓 통신 시 모든 메시지를 일관된 형식으로 감싸는 봉투(Envelope) 역할의 클래스
 */
data class WebSocketEnvelope(
    val type: MessageType,
    val payload: String // 실제 전송할 데이터(DTO)가 JSON 문자열 형태로 들어갑니다.
)

/**
 * sdkId 등록 요청 시 사용하는 DTO
 */
data class SdkIdRegistrationRequest(
    val sdkId: String
)

/**
 * 게임(Unity)의 특정 클래스 구조 전체를 표현하는 메타데이터 DTO
 */
data class ClassMetadata(
    @JsonProperty("class_name") val className: String,
    val variables: List<VariableMetadata> = emptyList(),
    val methods: List<MethodMetadata> = emptyList()
)

/**
 * 변수의 메타데이터 (이름과 타입)
 */
data class VariableMetadata(
    val name: String,
    val type: String
)

/**
 * 메서드의 메타데이터 (이름과 매개변수 목록)
 */
data class MethodMetadata(
    val name: String,
    val parameters: List<ParameterMetadata> = emptyList()
)

/**
 * 메서드 매개변수의 메타데이터 (타입과 이름)
 */
data class ParameterMetadata(
    val type: String,
    val name: String
)

/**
 * 서버에서 클라이언트로 특정 테스트 명령을 내릴 때 사용하는 DTO
 */
data class CommandDto(
    @JsonProperty("class_name") val className: String,     // 대상 클래스 이름 (ex: BattleSystem.Player)
    @JsonProperty("method_name") val methodName: String,   // 실행할 메서드 이름 (ex: TakeDamage)
    @JsonProperty("variable_name") val variableName: String, // 실행 전후로 값을 관찰할 대상 변수 이름 (ex: health)
    val parameters: List<Int> = emptyList()                  // 메서드 호출 시 전달할 매개변수(인자) 값 목록
)
