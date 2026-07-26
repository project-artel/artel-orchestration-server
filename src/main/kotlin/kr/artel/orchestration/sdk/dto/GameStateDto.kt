package kr.artel.orchestration.sdk.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

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
    val observables: Map<String, ObservableValue> = emptyMap(),
    val recentActions: List<AgentActionRecord> = emptyList()
)

/**
 * 게임이 실제로 실행한 액션의 기록 (Orchestrator -> Agent)
 *
 * 관찰값 변화와 달리 이것은 추론이 아니라 실행 자체의 증거다. 무엇이 성공했고 무엇을
 * 반환했는지, 실패라면 왜인지가 그대로 들어 있다. 에이전트가 시키지 않은 액션 —
 * 게임이 스스로 실행한 것 — 은 ACTION_RESULT로 오지 않으므로 여기서만 관측된다.
 */
data class AgentActionRecord(
    val target: String,
    val name: String,
    val success: Boolean,
    val returnValue: Any? = null,
    val error: String? = null,
    val at: String
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
