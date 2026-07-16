package kr.artel.orchestration.sdk.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty



/**
 * 웹소켓으로 들어오는 모든 메시지의 상위 공통 타입을 파악하기 위한 클래스
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

/**
 * 외부 Artel_SDK로부터 수신하는 전체 게임 상태 구조 (SDK -> Orchestrator)
 */
data class SdkGameState(
    val type: String,
    val id: Int,
    val scene: SdkBlock
)

/**
 * 씬 계층 구조 상의 노드(Scene 혹은 Block)를 표현하는 클래스
 */
data class SdkBlock(
    val id: Int,
    val type: String,
    val name: String,
    val components: List<SdkComponent> = emptyList(),
    val children: List<SdkBlock> = emptyList()
)

/**
 * 컴포넌트 정보
 */
data class SdkComponent(
    val type: String,
    val name: String,
    val content: String? = null,
    val placeholder: String? = null,
    val states: List<SdkState> = emptyList(),
    val actions: List<SdkAction> = emptyList()
)

/**
 * 컴포넌트의 변수 상태 값
 */
data class SdkState(
    val tag: String,
    val name: String,
    val type: String,
    val value: Any? = null
)

/**
 * 컴포넌트의 액션(메서드) 실행 정보
 */
data class SdkAction(
    val sequence: Int,
    val tag: String,
    val name: String,
    val success: Boolean,
    @JsonProperty("returnValue") val returnValue: Any? = null,
    val error: SdkError? = null,
    val timeStamp: String
)

/**
 * 액션 실행 실패 시 에러 정보
 */
data class SdkError(
    val type: String,
    val message: String
)

/**
 * 에이전트 서버로 전달할 정제된 컴팩트 게임 상태 구조 (Orchestrator -> Agent)
 */
data class AgentGameState(
    val scene: String,
    val interactables: List<Interactable> = emptyList(),
    val observables: Map<String, ObservableValue> = emptyMap()
)

/**
 * 관찰 대상의 세부 값과 타입 정보
 */
data class ObservableValue(
    val value: Any?,
    val type: String
)

/**
 * 에이전트가 조작 가능한 타겟 정보
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Interactable(
    val id: Int,
    val name: String,
    val type: String,
    val actions: List<String>? = null,
    val label: String? = null,
    val placeholder: String? = null
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

