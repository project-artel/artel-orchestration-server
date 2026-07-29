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
 *
 * [screen]은 씬 루트에만, [transform]은 블록에만 실린다. 한 클래스가 두 층을 겸하므로
 * 둘 다 nullable이다. 좌표를 싣지 않는 구버전 SDK도 그대로 동작한다.
 */
data class SdkBlock(
    val id: Int,
    val type: String,
    val name: String,
    val screen: SdkScreenSize? = null,
    val transform: SdkBlockTransform? = null,
    val components: List<SdkComponent> = emptyList(),
    val children: List<SdkBlock> = emptyList()
)

/**
 * 블록의 화면 좌표 정보
 *
 * [onScreen]의 기본값 true는 하위 호환이다. 이 값을 싣지 않는 SDK의 블록은 화면 안에
 * 있는 것으로 본다. 참이라고 해서 눈에 보인다는 뜻은 아니다. 마스크에 잘리거나 다른
 * 오브젝트에 가려진 블록도 참으로 온다.
 */
data class SdkBlockTransform(
    val rect: SdkScreenRect? = null,
    val onScreen: Boolean = true
)

/**
 * 블록이 화면에서 차지하는 영역. 게임 화면 기준 픽셀이며 원점은 좌상단, [x]/[y]는 중심이
 * 아니라 좌상단 모서리다.
 */
data class SdkScreenRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * 씬의 rect들이 측정된 기준 화면 크기
 *
 * 이것이 없으면 픽셀 rect는 의미를 갖지 못한다. x 860은 1920 화면에서는 한가운데지만
 * 1280 화면에서는 오른쪽이다.
 */
data class SdkScreenSize(
    val w: Int,
    val h: Int
)

/**
 * 컴포넌트 정보
 *
 * [interactable]은 사람이 지금 그 UI를 누르거나 입력할 수 있는지다. 버튼과 입력 필드에만 실린다.
 * 기본값 true는 하위 호환이다. 이 필드를 싣지 않는 구버전 SDK의 페이로드는 종전대로 조작 후보에
 * 오른다. 배포 순서를 맞출 필요가 없도록 한 것이다.
 *
 * [sprite]는 이미지/스프라이트 컴포넌트가 그리고 있는 에셋 이름이다. 스프라이트가 비어 있으면
 * SDK가 키 자체를 싣지 않는다.
 */
data class SdkComponent(
    val type: String,
    val name: String,
    val content: String? = null,
    val placeholder: String? = null,
    val sprite: String? = null,
    val interactable: Boolean = true,
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
 *
 * [screen]은 조작 후보들의 rect가 측정된 기준 화면 크기다. SDK가 화면 크기를 싣지 않으면
 * 키 자체가 나가지 않는다.
 *
 * [visuals]는 조작도 관찰도 아닌, 화면에 그려지기만 하는 요소다. 이미지 컴포넌트를 싣지 않는
 * SDK에서는 비어 있는 리스트로 나간다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class AgentGameState(
    val scene: String,
    val screen: AgentScreenSize? = null,
    val interactables: List<Interactable> = emptyList(),
    val observables: Map<String, ObservableValue> = emptyMap(),
    val recentActions: List<AgentActionRecord> = emptyList(),
    val visuals: List<Visual> = emptyList()
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
 *
 * [rect]는 SDK가 보낸 좌상단 기준 픽셀 값을 그대로 옮긴 것이다. 변환하지 않는다.
 * SDK의 `move_mouse`가 이 값을 그대로 받아 내부에서 변환하므로 여기서 한 번 더 뒤집거나
 * 중심점으로 바꾸면 이중 적용이 된다. 좌표가 없는 블록은 0이 아니라 null이다. 0은
 * "화면 좌상단"이라는 유효한 좌표이므로 부재와 구분되어야 한다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Interactable(
    val id: Int,
    val name: String,
    val type: String,
    val actions: List<String>? = null,
    val label: String? = null,
    val placeholder: String? = null,
    val rect: AgentRect? = null,
    val onScreen: Boolean = true
)

/**
 * 조작할 수도 관찰할 수도 없고 그려지기만 하는 시각 요소 (Orchestrator -> Agent)
 *
 * 배경, 아이콘, 캐릭터 스프라이트처럼 `content`도 `states`도 `actions`도 없는 블록은 종전에
 * 어느 목록에도 오르지 못해 에이전트에게 아예 보이지 않았다. 보이지 않는 것은 겨눌 수도 없다.
 *
 * [type]은 `image`(uGUI Image) 또는 `sprite`(SpriteRenderer)다. [sprite]는 그리고 있는 에셋
 * 이름이며 비어 있을 수 있다. [rect]/[onScreen]은 [Interactable]과 같은 규칙이다. SDK가 준
 * 좌상단 기준 픽셀 값을 변환 없이 그대로 옮기고, 좌표가 없으면 0이 아니라 null이다.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class Visual(
    val id: Int,
    val name: String,
    val type: String,
    val sprite: String? = null,
    val rect: AgentRect? = null,
    val onScreen: Boolean = true
)

/**
 * 조작 후보가 화면에서 차지하는 영역. 원점은 좌상단, [x]/[y]는 좌상단 모서리다.
 */
data class AgentRect(
    val x: Int,
    val y: Int,
    val w: Int,
    val h: Int
)

/**
 * rect들이 측정된 기준 화면 크기
 */
data class AgentScreenSize(
    val w: Int,
    val h: Int
)
