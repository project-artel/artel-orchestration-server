package kr.artel.orchestration.contentmap.observe

import java.time.Instant

/**
 * agent 가 이 액션으로 **겨눈 것**. 귀속의 출발점이다 (ARTEL-450).
 *
 * ## 왜 겨눈 것만 받나
 *
 * 액션 프로토콜의 메서드는 겨냥의 정밀도가 제각각이다. 실측 한 런의 액션 394 개를 그대로 갈라
 * 보면:
 *
 * | 메서드 | 인자 | 겨눈 것 |
 * |---|---|---|
 * | `button_click` | `[32562]` | 오브젝트 하나. `pulse` 가 그 번호에 selector 를 붙여 준다 |
 * | `key_click` · `key_down` · `key_up` | `["Space", 0.1]` | 입력 하나. 어느 기능이 그 키를 받는지는 지도가 안다 |
 * | `move_mouse` · `mouse_down` · `mouse_up` | 좌표 | **없다.** 좌표 위에 무엇이 있었는지는 아무도 말하지 않았다 |
 * | `capture_screen` | `[]` | **없다.** 게임 조작이 아니다 |
 * | `set_axis` | 축 이름과 값 | 지도에 축을 적을 칸이 없다(`Interaction.AXIS` 를 쓴 기능이 실측 0 개) |
 *
 * 아래 둘만 받는다. 좌표를 겨냥으로 읽지 않는 것이 이 타입의 전부다 — 좌표 위에 무엇이 있었는지를
 * 우리가 **추측**하면, 그 추측이 그대로 `capability_observation` 한 행이 되어 다음 이슈가 기능을
 * 승격하는 근거가 된다.
 */
sealed interface ActionTarget {

    /** 이 씬에서 겨눴다. `pulse` 가 말한 씬 이름이다. */
    val scene: String

    /** 로그에 찍고 [ActionTimeline] 이 재시도를 세는 데 쓰는 키. */
    val key: String

    /**
     * 오브젝트 하나를 겨눴다. [selector] 는 `pulse` 가 그 instance id 에 붙여 준 위치 경로다.
     *
     * `capability.control_selector` 와 같은 표기라 그대로 맞대 볼 수 있다. 액션 프로토콜이 받는
     * `int` instance id 는 프로세스를 넘지 못하므로, 그 번호를 지도가 아는 이름으로 바꾸는 자리가
     * 여기 말고 없다.
     */
    data class Control(override val scene: String, val selector: String) : ActionTarget {
        override val key: String get() = "$scene/$selector"
    }

    /**
     * 키 입력 하나를 겨눴다. [inputKey] 는 SDK 표기 그대로(`Space` · `UpArrow`)다.
     *
     * `capability.input_key` 와 맞대며, `any` 로 저장된 기능은 어느 키에도 맞는다
     * (`Interaction.ANY_INPUT_KEY` — 근거가 특정 키를 지목하지 않은 "아무 키" 조작이다).
     */
    data class Key(override val scene: String, val inputKey: String) : ActionTarget {
        override val key: String get() = "$scene#$inputKey"
    }
}

/**
 * SDK 로 나갔고 아직 답을 못 받았거나 창이 안 닫힌 액션 하나 (ARTEL-450).
 *
 * [requestId] 는 `qa_log` 가 발급한 outer id 다. `ACTION_RESULT` 가 `requestId` 로 그 값을 되돌려
 * 주므로, 이것으로 성공/거절을 짝짓는다.
 */
data class DispatchedAction(
    val requestId: Long,
    val target: ActionTarget,
    val method: String,
    val params: List<Any?>,
    val actedAt: Instant,
    val screenId: Long?,
)

/**
 * 귀속 창 하나가 닫혔다. 이 값 하나가 `capability_observation` 한 묶음이 된다 (ARTEL-450).
 *
 * 기능 id 가 아니라 [target] 을 든다 — 어느 기능들이 이 컨트롤 뒤에 있는지는 DB 를 읽는 쪽이
 * 정한다. 타임라인은 DB 를 모른다.
 */
data class ClosedActionWindow(
    val target: ActionTarget,
    val method: String,
    val params: List<Any?>,
    val actedAt: Instant,
    val screenId: Long?,

    /**
     * 이 조작을 확정하기까지 보낸 액션 수. 앞선 시도가 SDK 에 거절당해 메서드를 바꿔 성공한
     * 경우에만 1 보다 크다.
     *
     * `> 1` 이 쌓이는 자리가 힌트가 나쁜 자리이고, 매핑 규칙을 고칠 지점을 알려준다.
     */
    val attempts: Int,

    /** 액션을 보낼 때 우리가 들고 있던 마지막 `reading` 번호. 없을 수 있다 — 첫 액션이 첫 `pulse` 보다 빠르면. */
    val readingBefore: Long?,

    /** 창을 닫은 `pulse` 의 `reading` 번호. */
    val readingAfter: Long?,

    /** 창 안에서 **새로** 달라진 것이 있었나. 판정 규칙은 [ActionTimeline] 의 KDoc 에 있다. */
    val fired: Boolean,

    /** 새로 달라진 것들. 기대와 대조하는 것은 ARTEL-451 이다. */
    val effects: List<ObservedEffect>,
)

/**
 * `pulse` 가 달라졌다고 말한 것 하나를 `capability_effect` 와 맞댈 수 있는 모양으로 옮긴 값.
 *
 * 칸 이름을 `capability_effect` 의 `kind` · `target` · `detail` 과 **일부러 같게** 두었다. 기대와
 * 관측을 맞대는 것이 ARTEL-451 인데, 두 표의 칸 이름이 다르면 그 대조를 읽는 사람이 매번 어느
 * 쪽 어휘인지부터 확인해야 한다.
 *
 * `pulse` 의 `changed` 항목은 세 모양으로 온다:
 *
 * ```
 * "scene"                                                   → 씬이 바뀌었다
 * "TitleScene/Canvas[2]/continue[2]|active"                  → 오브젝트의 상태
 * "TitleScene/TitleSceneController[4]|Scenes.TitleSceneManager::continueButton"
 *                                                            → 그 오브젝트에 걸린 멤버
 * "Battle.Turns.TurnBattleSystem::EnemyTurn"                 → GameObject 에 안 걸린 static
 * ```
 */
data class ObservedEffect(
    /** `scene` · `object` · `member` 중 하나. [ObservedEffectKind] 참고. */
    val kind: String,

    /**
     * 무엇이 달라졌나.
     *
     * `scene` 이면 새 씬 이름, `object` 면 오브젝트 경로, `member` 면 그 멤버를 든 타입
     * (`Battle.Turns.TurnBattleSystem`)이다. `capability_effect.target` 은 `TurnBattleSystem.currentTurn`
     * 처럼 타입과 멤버를 붙여 적으므로, 맞대는 쪽이 [detail] 과 함께 봐야 한다.
     */
    val target: String,

    /** `scene` 이면 직전 씬, `object` 면 facet(`active` · `world` · `offers`), `member` 면 멤버 이름. */
    val detail: String? = null,

    /** 이 변화가 실린 오브젝트 경로. static 멤버와 씬 변화에서는 null 이다. */
    val on: String? = null,
)

/** [ObservedEffect.kind] 의 어휘. */
object ObservedEffectKind {
    const val SCENE = "scene"
    const val OBJECT = "object"
    const val MEMBER = "member"
}
