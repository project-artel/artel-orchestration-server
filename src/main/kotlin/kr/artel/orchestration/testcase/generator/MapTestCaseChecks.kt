package kr.artel.orchestration.testcase.generator

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity

/**
 * **기대 항목 하나를 QA 가 판독에서 무엇으로 확인하나.**
 *
 * 기대결과 문장은 사람이 읽는 것이다. 판정하는 쪽은 문장이 아니라 판독(`pulse`)을 본다. 판독은 값을
 * **코드 이름으로** 싣는다 — `ChatWindowController.chatName = {path: "Canvas/ChatWindow/ChatName",
 * label: "나레이터"}`. 그래서 판정에는 코드 이름을, 사람에게는 씬 이름을 준다. 이 구조가 앞의 것이다.
 *
 * 문장만 보내면 판정이 문장 속 이름을 판독에서 찾는다. 사람용 이름(`Canvas/ChatWindow/AnyKeyPrompt`)과
 * 판독의 멤버 이름이 다르면 못 찾고, 실측에서 그 탓에 판정 못 한 기대가 18건이었다.
 *
 * @property kind 지도의 효과 어휘 그대로(`ui-value` · `active-state` · `scene` …)
 * @property lookAt 판독에서 찾을 게임 타입 멤버(`ChatWindowController.chatName`). 판독이 그 멤버를 싣지 않으면
 *   null 이고, 그때는 [name] 으로 찾는다
 * @property field 그 멤버 값에서 볼 칸. 판독이 쓰는 이름 그대로다(`label` · `sprite` · `active` · `world` ·
 *   `state`). 씬 전환은 `scene`, 생기고 사라지는 것은 `present`
 * @property value 기대하는 값. 모르면 null — "바뀐다" 만 확인한다
 * @property name 사람이 찾는 이름. 기대결과 문장의 대상과 같다
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
data class MapTestCaseCheck(
    val kind: String,
    @JsonProperty("look_at") val lookAt: String?,
    val field: String,
    val value: String?,
    val name: String,
)

object MapTestCaseChecks {

    /**
     * @param named 사람용으로 풀린 대상(문장의 대상)
     * @param value 문장에 실은 값. 판독으로 못 맞추는 값은 이미 빠져 있다
     */
    fun of(
        effect: CapabilityEffectEntity,
        named: String,
        value: String?,
        methodId: String?,
        gameTypes: Set<String>?,
    ): MapTestCaseCheck {
        val kind = effect.kind
        val field = fieldOf(kind, effect.target)
        if (kind == SCENE) return MapTestCaseCheck(kind, null, field, named, named)
        val member = effect.target
            ?.let { MapTestCaseTargets.ofOwner(it, methodId) }
            ?.let(::memberOf)
            ?.takeIf { gameTypes != null && MapTestCaseReadings.seesValue(it, gameTypes) }
        val expected = if (kind == ANIMATION) triggerOf(effect.detail) else value
        return MapTestCaseCheck(kind, member, field, expected, named)
    }

    /** 화면에 붙어 있는 UI 요소. 지도의 경로가 곧 찾을 이름이다. */
    fun presence(path: String): MapTestCaseCheck = MapTestCaseCheck(PRESENCE, null, PRESENT, null, path)

    /**
     * 판독이 그 효과를 어느 칸에 싣나. SDK `LiveState` 의 칸 이름을 그대로 쓴다 — 글자는 `label`, 그림은
     * `sprite`, 켜짐은 `active`, 위치는 `world`, 애니메이터는 `state`.
     */
    private fun fieldOf(kind: String, target: String?): String = when (kind) {
        SCENE -> SCENE
        "active-state" -> "active"
        "ui-value" -> if (MapTestCaseReadings.isSprite(kind, target)) "sprite" else "label"
        "transform" -> "world"
        ANIMATION -> "state"
        "instantiate", "destroy" -> PRESENT
        else -> "value"
    }

    /**
     * 판독이 값을 싣는 멤버까지. `ChatWindowController.chatName.text` 의 `.text` 는 판독이 `label` 칸으로
     * 주는 것이고, `.transform.position` 은 `world` 칸이다. 떼어야 판독의 멤버 이름과 같아진다.
     */
    private fun memberOf(target: String): String =
        TAILS.fold(target) { name, tail -> name.removeSuffix(tail) }
            // `Item` 은 .NET 이 인덱서에 붙이는 이름이라 판독에 없다. 번호를 알면 `backgorunds[0]`, 모르면
            // (`Item[_]`) 목록 전체다 — 코드가 그 목록을 돌며 전부 건드리는 자리다.
            .replace(UNREAD_ITEM, "")
            .replace(LIST_ITEM, "[$1]")

    /** `SetTrigger("Death")` → `Death`. 판독은 상태 이름이 트리거와 같을 때만 그 이름을 싣는다. */
    private fun triggerOf(detail: String?): String? = detail?.let { QUOTED.find(it)?.groupValues?.get(1) }

    private val TAILS = listOf(
        ".text", ".sprite", ".transform.position", ".transform.localPosition", ".transform.localScale",
        ".transform.rotation", ".transform", ".gameObject",
    )

    private val UNREAD_ITEM = Regex("""\.Item\[_?\]""")

    private val LIST_ITEM = Regex("""\.Item\[(\d+)\]""")

    private val QUOTED = Regex(""""([^"]+)"""")

    private const val SCENE = "scene"
    private const val ANIMATION = "animation"
    private const val PRESENT = "present"
    private const val PRESENCE = "presence"
}
