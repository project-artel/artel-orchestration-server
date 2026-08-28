package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * `pulse` 문서. **화면을 가르는 데 필요한 칸만** 판다.
 *
 * 중계 경로(`QaSdkBridgeService.routePulse`)는 문서를 읽지 않고 옮기기만 하므로 raw JSON 으로
 * 남는다. 여기는 반대다 — 필드를 읽어 판단하므로 `coding-style.md` 의 `Data Shapes` 가 요구하는
 * 대로 경계에서 한 번 타입으로 판다. 두 경로가 같은 프레임을 다르게 다루는 것이 맞다: 옮기는
 * 쪽은 문서가 바뀌어도 따라 움직이지 않아야 하고, 읽는 쪽은 무엇을 읽었는지 이름으로 남아야
 * 한다.
 *
 * [JsonIgnoreProperties] 로 모르는 칸을 흘린다. SDK 는 칸을 더하면서 배포되고, 그때마다 이쪽이
 * 파싱 실패로 죽으면 화면 적재가 통째로 멈춘다 — `pulse` 는 관측 채널이지 런의 전제가 아니다.
 *
 * 값(`members`)은 담지 않는다. **화면을 가르는 데 쓰지 않기로 한 것**이 `discriminator` 규칙의 핵심이고
 * ([ScreenDiscriminator] 참고), 쓰지 않을 것을 파면 다음 사람이 쓸 수 있다고 읽는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseReading(
    /** 이 `pulse` 가 본 씬. 델타에서도 실려 온다. */
    val scene: String? = null,

    /**
     * 전량 `pulse` 인가.
     *
     * `true` 면 이것이 `pulse` 가 볼 수 있는 전부라 들고 있던 것을 **교체**한다. `false` 면 델타라
     * 얹는다 — 말하지 않은 객체는 있던 자리를 지킨다. agent-server `PulseMemory.apply` 와 같은
     * 규칙이다. 두 소비자가 델타를 다르게 읽으면 어느 쪽이 틀렸는지 가릴 수 없다.
     */
    val whole: Boolean = false,

    /** 켜져 있는 객체. */
    val active: List<PulseObject> = emptyList(),

    /** 꺼져 있는 객체. 빼지 않고 따로 싣는 것이 이 채널의 규율이다. */
    val deactive: List<PulseObject> = emptyList(),

    /**
     * SDK 가 이 `pulse` 에 매긴 순번 (ARTEL-450).
     *
     * **우리 도착 시각과 다른 축이다.** 실측 한 런에서 이 값이 30,290 까지 오르는 동안 우리가 받은
     * `pulse` 는 14,036 개였다 — 절반은 전달 과정에서 사라진다. `capability_observation` 이 창의
     * 경계를 이 번호로 적는 것이 그래서다([ActionTimeline] 의 규칙 4).
     */
    val reading: Long? = null,

    /**
     * 이 `pulse` 에서 달라진 것들의 키 (ARTEL-450).
     *
     * 세 모양으로 온다 — `"scene"` · `"TitleScene/Canvas[2]/continue[2]|active"` ·
     * `"Battle.Turns.TurnBattleSystem::EnemyTurn"`. 읽는 규칙은 [observedEffectOf] 에 있다.
     *
     * **비어 오는 일이 사실상 없다.** 실측 14,489 개 전부가 무언가를 실어 왔고, 적 애니메이터
     * selector 다섯 개가 그 중 2 만 건을 차지한다. 그래서 "달라진 것이 있나" 는 판정이 될 수 없고,
     * [ActionTimeline] 이 액션 직전 구간을 대조군으로 빼는 이유가 이 사실이다.
     */
    val changed: List<String> = emptyList(),
)

/**
 * `pulse` 가 말한 객체 하나.
 *
 * 어느 목록에 실려 왔는가가 곧 켜짐/꺼짐이다. 객체의 필드가 아니라서 자기모순이 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseObject(
    /**
     * 런타임 instance id (ARTEL-450).
     *
     * 액션 프로토콜이 조준에 받는 값이 이것이다 — `button_click` 의 인자가 `[32562]` 인 것이 곧
     * 이 번호다. `capability.control_selector` 는 위치 경로라 그대로는 조준에 못 쓰이고, 이 칸이
     * **그 둘을 잇는 유일한 다리**다. 프로세스를 넘어 살지 못하므로 저장하지 않고
     * [ActionTimeline] 이 런 동안만 들고 있는다.
     */
    @JsonProperty("id")
    val instanceId: Long? = null,

    /**
     * 이 객체가 사는 씬. **문서 최상위와 같은 씬이면 객체가 제 이름을 대지 않는다** — 다른 씬의
     * 객체만 댄다. 읽는 쪽이 최상위 씬으로 메워야 하고, 그 메우기를 빠뜨리면 씬을 넘는 순간
     * 이전 씬 객체가 현재 씬 것으로 세어진다.
     */
    val scene: String? = null,

    /**
     * 형제 인덱스가 붙은 위치 경로(`Canvas[2]/continue[1]`). `discriminator` 의 키다.
     *
     * `CapabilityEntity.controlSelector` 와 같은 표기라 그대로 맞대 볼 수 있다.
     */
    val selector: String? = null,

    /** 사람이 읽는 계층 경로. selector 가 없을 때의 차선 키. */
    val path: String? = null,

    /**
     * 이 객체가 **지금 무엇에 응답하는가**. 있으면 조작 가능한 객체다.
     *
     * 모양을 읽지 않고 비었는지만 본다 — 어휘는 SDK 의 것이고 배포마다 바뀐다. 옛 SDK 는 이
     * 칸을 아예 보내지 않으므로 없는 것이 곧 "조작 불가"는 아니다.
     *
     * **`discriminator` 를 정하는 데 쓰지 않는다** (ARTEL-654). 광고한다는 것은 "지금 무엇에
     * 응답하는가" 이지 "이것이 화면을 식별한다" 가 아니고, 그 둘을 같게 놓았을 때 화면 수가
     * 실제 상태 수가 아니라 플레이 길이에 비례했다. 무엇이 화면을 식별하는지는
     * [ScreenSelectorWhitelist] 가 정한다. 이 칸이 남아 있는 것은 처음 보는 selector 를 목록
     * 후보로 제안하는 데 쓰기 위해서이고, 그 소비자는 ARTEL-655 다.
     */
    @JsonProperty("offers")
    val offers: Map<String, Any?>? = null,
) {
    /** `discriminator` 와 기능 대조가 쓰는 키. selector 가 없으면 path 로 내려간다. */
    val key: String? get() = selector ?: path

    /** SDK 가 이 객체를 조작 가능하다고 광고했나. 소비자는 아직 없다 — 위 [offers] 주석 참고. */
    val interactive: Boolean get() = !offers.isNullOrEmpty()
}
