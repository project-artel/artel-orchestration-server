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
     * 판독 일련번호. `capability_observation.readingBefore`/`readingAfter` 가 가리키는 값이다.
     *
     * 종전에는 모델에 없어 조용히 버려졌다. 조작과 그 뒤 판독을 잇는 축이라 필요하다(ARTEL-785).
     */
    val reading: Long? = null,


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
     * 어떤 GameObject 에도 걸리지 않은 값(`InteractionLock.IsLocked` 등).
     *
     * 종전에는 이 절을 모델에 두지 않아 `@JsonIgnoreProperties` 가 조용히 버렸다. 지도가
     * 말한 효과 대상이 판독에 나타나는지를 재려면 여기가 필요하다 — 씬 오브젝트에 안 걸린
     * 값이 지도의 `capability_effect.target` 에 자주 나온다(ARTEL-785).
     */
    val statics: List<PulseStatic> = emptyList(),
)

/**
 * GameObject 에 걸리지 않은 값 하나.
 *
 * 객체 밑에 접어 넣지 않는 것이 이 채널의 규율이다 — 주인이 없는 값에 주인을 지어내게 된다.
 * agent-server 의 `PulseStatic` 과 같은 모양이고, 두 소비자가 같은 문서를 다르게 읽으면
 * 어느 쪽이 틀렸는지 가릴 수 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseStatic(
    /** 이 값을 선언한 타입. `InteractionLock` 등. */
    val declaring: String? = null,
    /** 멤버 이름. `IsLocked` 등. */
    val member: String? = null,
    val type: String? = null,
    val value: Any? = null,
) {
    /**
     * 지도의 `capability_effect.target` 과 맞대 볼 이름.
     *
     * 지도는 `Player.HpText.text` 처럼 점 표기로, 판독은 선언 타입과 멤버로 나뉘어 온다.
     * 마지막 마디만 남기는 규칙은 `ScenarioStateReader.normalize()` 가 이미 정했고, 경로
     * 계산이 그것을 쓴다 — 여기서 다른 규칙을 만들면 두 곳이 갈라진다.
     */
    val memberName: String? get() = member?.substringAfterLast('.')?.takeIf { it.isNotBlank() }
}

/**
 * `pulse` 가 말한 객체 하나.
 *
 * 어느 목록에 실려 왔는가가 곧 켜짐/꺼짐이다. 객체의 필드가 아니라서 자기모순이 없다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseObject(
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

    /**
     * 이 객체 위에서 읽힌 멤버들.
     *
     * 지도의 `capability_effect.target` 이 `Player.HpText.text` 처럼 **인스턴스 위의 값**을
     * 가리키는 일이 많다. 그것은 정적 필드가 아니라 여기 실린다 — `statics` 만 보면
     * `observable` 범주가 통째로 0 으로 보인다(ARTEL-785 실측에서 그렇게 나왔다).
     */
    val members: List<PulseMember> = emptyList(),

    /** 같은 것을 컴포넌트별로 접은 모양. `on` 을 한 번만 쓴다. */
    val by: List<PulseComponent> = emptyList(),
) {
    /** `discriminator` 와 기능 대조가 쓰는 키. selector 가 없으면 path 로 내려간다. */
    val key: String? get() = selector ?: path

    /** SDK 가 이 객체를 조작 가능하다고 광고했나. 소비자는 아직 없다 — 위 [offers] 주석 참고. */
    val interactive: Boolean get() = !offers.isNullOrEmpty()

    /**
     * 이 객체가 보여 준 멤버 이름들. 접힌 모양(`by`)과 편 모양(`members`)을 함께 본다.
     *
     * 마지막 마디만 남긴다 — 지도는 점 표기로, 판독은 선언 타입과 멤버로 나뉘어 오므로
     * 그 규칙으로만 맞는다. `ScenarioStateReader.normalize()` 와 같은 규칙이고, 두 곳이
     * 갈라지면 시나리오와 QA 가 다른 답을 낸다.
     */
    val memberNames: List<String>
        get() = (members + by.flatMap { it.m })
            .mapNotNull { it.member?.substringAfterLast('.')?.takeIf(String::isNotBlank) }
}

/** 한 객체 위에서 읽힌 멤버 하나. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseMember(
    /** 이 멤버를 선언한 컴포넌트. */
    val on: String? = null,
    val member: String? = null,
    val value: Any? = null,
)

/** 한 컴포넌트가 내놓은 멤버들. `on` 을 한 번만 쓴 접힌 모양이다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class PulseComponent(
    val on: String? = null,
    val m: List<PulseMember> = emptyList(),
)
