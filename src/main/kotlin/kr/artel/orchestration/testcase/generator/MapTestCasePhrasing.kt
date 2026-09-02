package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.EffectCategory
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.ConditionPrune
import kr.artel.orchestration.contentmap.evidence.GroupKind

/**
 * 지도 한 줄을 **사람이 읽는 세 문장**으로 옮긴다(ARTEL-554). DB 도 Spring 도 모른다.
 *
 * ## 식별자를 말로 바꾸지 않는다
 *
 * 이 파일 전체를 떠받치는 규칙이다. `MapMove.position` 을 "캐릭터가 옆으로 이동"으로 옮기는 것이
 * **이 시스템에서 가장 비싼 거짓 명세**다(`CapabilityEntity.summary` 가 같은 자리에 적어 두었다).
 * 경로·타입·메서드·필드는 원문 그대로 쓰고 **사이만 말로 잇는다.** 백틱으로 감싸는 것은 어디까지가
 * 원문인지 읽는 사람이 알게 하려는 것이다.
 *
 * 뜻을 모르는 값은 지어내지 않고 그대로 보여 준다. QA 담당자가 코드 이름을 보는 것이, 코드가
 * 상상한 한국어를 보는 것보다 낫다.
 *
 * ## 입력은 사전조건이 아니라 스텝이다
 *
 * 조건 트리에는 `gesture` 노드가 섞여 있다(`key:Return (down)`). 그것은 **사람이 하는 일**이지
 * 화면이 이미 그런 상태여야 한다는 말이 아니다. 사전조건에 남기면 "Return 키가 눌린 상태에서
 * Return 키를 누른다"가 된다. 그래서 [precondition] 은 `gesture` 를 빼고, [step] 이 그것을 든다.
 */
object MapTestCasePhrasing {

    /**
     * **화면 + 조건.** 기존 케이스와 같은 모양이라 저작의 사전조건 파서가 그대로 읽는다 —
     * `Map_scene 화면인 상태 / MapMove.position == 0`.
     *
     * 조건이 없으면 화면만 남는다. 그것도 사전조건이다 — "그 화면이기만 하면 된다".
     */
    fun precondition(scene: String, condition: ConditionNode?, inputKey: String? = null): String {
        val state = "$scene 화면인 상태"
        // 갈래를 묶으면 같은 검사가 갈래마다 되풀이된다. 뜻이 같은 채로 읽을 것만 줄인다(ARTEL-624).
        val text = stateText(ConditionPrune.of(condition), scene)
            ?.let { if (inputKey == null) it else withoutInputCheck(it) }
            ?.ifBlank { null }
            ?: return state
        return "$state / $text"
    }

    /**
     * **행동이 이미 말하는 입력 판정을 뺀다.**
     *
     * 게임이 입력을 자기 메서드로 감싸면(`IsAdvanceKeyDown()` · `IsAnyKeyDown()`) 그것이 조건에
     * `test` 로 들어온다 — `gesture` 노드가 아니라 걸러지지 않는다. 그대로 두면
     * "`IsAdvanceKeyDown() != 0` 인 상태에서 아무 키나 누른다"가 되어 같은 말을 두 번 한다.
     *
     * **키를 든 조작에서만** 뺀다. 조작이 없는 자리에서는 그 판정이 진짜 사전조건일 수 있다.
     * 이름을 보지 않는다 — `Key`/`Button`/`Input` 을 낀 **호출**이라는 모양만 본다.
     */
    private fun withoutInputCheck(text: String): String = text
        .split(AND)
        .filterNot { part -> INPUT_CHECK.containsMatchIn(part) }
        .joinToString(AND)

    /**
     * 두 조건을 **함께 성립해야 하는 것**으로 잇는다(ARTEL-554).
     *
     * 다른 갈래에서 결과를 빌려 오면 그 결과가 나려면 양쪽 조건이 다 참이어야 한다. 한쪽이 없으면
     * 나머지만 낸다.
     */
    fun both(a: ConditionNode?, b: ConditionNode?): ConditionNode? = when {
        b == null -> a
        a == null -> b
        else -> ConditionNode.Group(GroupKind.EVERY, listOf(a, b))
    }

    /**
     * **무엇을 하나.** 조준 대상이 있으면 그것을, 없으면 입력 키를 부른다.
     *
     * `any` 키는 특별히 다룬다 — 그대로 쓰면 "`any` 키를 누른다"가 되어 무엇을 누르라는 것인지
     * 읽는 사람이 알 수 없다. 명세가 아무 키나 된다고 말한 것이므로 그렇게 적는다.
     */
    /**
     * **누를 것이 없는 자리의 기능 문구**(ARTEL-681).
     *
     * 게임이 스스로 하는 일을 무엇이 일으켰는가로 부른다. 재료는 `call_path` 첫 마디의 메서드
     * 이름인데, **유니티가 정한 것만** 쓴다 — `Start`·`Update` 는 엔진이 부르는 자리라 개발자가
     * 무엇을 어떻게 짓든 흔들리지 않는다. 개발자가 지은 메서드 이름으로 기능을 부르는 것과는
     * 다른 이야기다: 저것은 의도를 주장하고 이것은 호출 자리를 가리킨다.
     *
     * 구버전(specs_v2 `render.py::trigger_text`)이 같은 방식이었다. 거기서는 `scene_entry` ·
     * `continuous` 처럼 갈라 두었고, 그 분류가 지금 지도에는 `call_path` 뿌리로 남아 있다.
     *
     * 모르는 뿌리는 이름을 그대로 적는다. 지어내지 않는다.
     */
    fun watching(scene: String, triggerRoot: String?): Act = when (triggerRoot) {
        "Start", "Awake", "OnEnable" -> Act(scene, "에 진입해 관찰한다", "에 진입하면")
        "Update", "FixedUpdate", "LateUpdate" -> Act(scene, "에 머무르며 관찰한다", "에 머무르면")
        null -> Act(scene, "에서 관찰한다", "에서는")
        else -> Act(scene, "에서 `$triggerRoot` 이후 관찰한다", "에서 `$triggerRoot` 이후")
    }

    /**
     * 조작 한 마디를 **종결형과 연결형 두 벌로** 든다.
     *
     * 활용을 규칙으로 만들지 않는다. "누른다"의 어간은 "누르-"라 어미만 떼면 "누른되"가 되고,
     * 조작 문구는 키·경로·클릭이 섞여 있어 규칙 하나로 활용할 수 없다. 자리마다 두 벌을 적는다.
     *
     * 관측도 같은 것을 쓴다([watching]). 거기서는 사람이 하는 일이 "그 화면에 있는 것"뿐이라
     * [what] 이 씬이고 [joins] 가 `에 진입하면` 이다 — 이름의 모양은 조작과 하나로 둔다.
     *
     * @property what 무엇을 건드리나(관측이면 어느 화면인가). 없는 자리가 있다(조작 미상).
     * @property ends 문장을 끝내는 꼴 — `누른다`
     * @property joins 뒤에 결과를 잇는 꼴 — `눌러`. 없으면 결과를 줄표로 잇는다.
     */
    data class Act(val what: String?, val ends: String, val joins: String?)

    fun act(
        interaction: String,
        inputKey: String?,
        controlLabel: String?,
        controlPath: String?,
    ): Act = when {
        controlLabel != null -> Act("`$controlLabel` 을(를)", "클릭한다", "클릭해")
        controlPath != null -> Act("`$controlPath` 을(를)", "클릭한다", "클릭해")
        inputKey == ANY_KEY -> Act("아무 키나", "누른다", "눌러")
        inputKey != null && interaction == Interaction.PRESS.wire ->
            Act("`$inputKey` 키를", "누른다", "눌러")
        inputKey != null -> Act("`$inputKey` 키를", "입력한다", "입력해")
        else -> Act(null, "조작 미상($interaction)", null)
    }

    /**
     * **케이스 이름은 조작이 아니라 시험이다**(ARTEL-662 뒤).
     *
     * 앞서 이름은 조작뿐이었다 — `아무 키나 누른다`. 그것은 **무엇이 되는지를 말하지 않아서**,
     * 읽는 사람이 표만 보고는 무엇을 검증하라는 것인지 알 수 없다. 실측 33건 중
     * `아무 키나 누른다` 가 여덟 줄, `` `Combine` 을(를) 클릭한다 `` 가 두 줄이었다.
     *
     * 결과를 지어내지 않는다. 동사는 `capability_effect.kind` 가 그대로 준다([doing]) —
     * `scene` 이면 넘어가고 `instantiate` 면 만든다. 우리가 뜻을 붙이는 자리가 없다.
     *
     * ```
     * 아무 키나 누른다              →  아무 키나 눌러 `Map_scene` 화면으로 넘어간다
     * `Combine` 을(를) 클릭한다     →  `Combine` 을(를) 클릭해 `CombineZone/Button` 을(를) 켠다
     * ```
     *
     * ## 결과가 여럿이면
     *
     * 앞의 하나만 부르고 `외 N건` 을 붙이던 자리다. 그것이 **무엇을 검증하라는 것인지 말하지
     * 않는다** — 실측에서 `` 아무 키나 눌러 `AnyKeyPrompt` 의 표시 상태를 바꾼다 외 4건 `` 이
     * 나왔고, 읽는 사람은 나머지 넷이 무엇인지 이름만 보고는 모른다.
     *
     * 대표를 고르지 않는다. **몇 가지를 보는지 센다:**
     *
     * ```
     * 둘   `TypeCard` 을(를) 만들고 `…text` 표시를 `Word.name` 로 갱신한다
     * 셋+  표시 상태 3곳을 바꾸고 글자 2곳을 갱신한다
     * ```
     *
     * 세는 말도 지어내지 않는다 — `capability_effect.kind` 가 그대로 준다([Doing]). 셋 이상은
     * 실측 6건뿐이고 종류가 최대 셋이라 문장이 길어지지 않는다. 전부는 기대결과 칸에 있다.
     */
    fun trial(act: Act, repeatUntilDone: Boolean, does: List<Doing> = emptyList()): String {
        val lead = listOfNotNull(act.what, REPEATEDLY.takeIf { repeatUntilDone })
        val tail = tailOf(does)
        return when {
            tail == null -> (lead + act.ends).joinToString(" ")
            // 이을 꼴이 없으면 문장을 끝내고 줄표로 잇는다. 억지로 활용하지 않는다.
            act.joins == null -> (lead + act.ends).joinToString(" ") + " — $tail"
            else -> (lead + act.joins).joinToString(" ") + " " + tail
        }
    }

    /**
     * 무엇이 일어나는지를 한 마디로.
     *
     * **종류가 같은 것끼리 센다.** 같은 종류를 둘 이름으로 부르면 문장이 두 배가 되면서
     * 말하는 것은 늘지 않는다 — 실측에서
     * `` `chatName.text` 표시를 갱신하고 `chatText.text` 표시를 `streamingText.Substring(0, i)` 로
     * 갱신한다 `` 가 나왔고, `글자 2곳을 갱신한다` 가 같은 말이면서 읽힌다.
     *
     * **둘뿐이고 종류가 다르면 둘 다 이름으로 부른다.** 그때 세면 `오브젝트 1개를 만들고 글자
     * 1곳을 갱신한다` 가 되어 무엇을 보라는 것인지 오히려 흐려진다.
     *
     * 순서는 지도가 실은 순서를 지킨다 — 우리가 매기면 그것이 곧 순위가 된다.
     */
    private fun tailOf(does: List<Doing>): String? {
        if (does.isEmpty()) return null
        if (does.size == 1) return does.single().let { "${it.what} ${it.ends}" }
        val groups = does.groupBy { it.kind }.values.toList()
        val byName = does.size == 2 && groups.size == 2
        return groups.mapIndexed { at, group ->
            val last = at == groups.lastIndex
            if (byName) {
                group.single().let { "${it.what} ${if (last) it.ends else it.joins}" }
            } else {
                val counted = group.first().counted
                "${counted.noun} ${group.size}${counted.unit} ${if (last) counted.ends else counted.joins}"
            }
        }.joinToString(" ")
    }

    /**
     * **무엇이 일어나나.** 확인할 수 있는 효과 **하나마다 한 줄**을 낸다.
     *
     * ## 왜 합치지 않나
     *
     * 처음에는 쉼표로 이어 한 줄로 냈다. 실측(적재기 지도의 `Map.MapMove.CharacterMove`)에서 그
     * 결과가 이렇게 나온다:
     *
     * ```
     * 행동     : `RightArrow` 키를 누른다
     * 기대결과 : …battle1 로 바뀐다, …battle2 로 바뀐다, …boss 로 바뀐다
     * ```
     *
     * **실행하는 사람이 무엇을 볼지 알 수 없다.** 셋 다 확인하라는 것인지 하나만인지 문장이 말하지
     * 않는다. 구버전 생성기는 같은 기능에서 케이스 **9개**를 냈고 각각이 하나씩 확인한다 — 같은 키를
     * 눌러도 지금 위치에 따라 결과가 다르니 각각 확인해야 하는 것이 맞다.
     *
     * ## `state` 는 뺀다
     *
     * 값이 바뀌는 것은 사실이지만 **화면에서 확인할 수 없다.** `EffectCategory.assertable` 이 그
     * 판정을 이미 들고 있고 `v_spec_gap` 의 `then-missing` 도 같은 규칙으로 센다 — 여기서 따로
     * 정하면 두 곳이 갈린다.
     *
     * 쓸 수 있는 효과가 하나도 없으면 빈 목록이다. **그 기능은 케이스가 되지 못한다** — 확인할 것이
     * 없는 케이스는 실행해도 통과·실패를 말할 수 없다.
     */
    fun expectedEach(effects: List<CapabilityEffectEntity>): List<String> =
        expectedWithSource(effects, emptyMap()).map { it.expected }

    /**
     * 효과 하나가 낸 **세 가지 모양**.
     *
     * @property expected 기대결과 칸에 앉는 문장 — `\`Map_scene\` 화면으로 전환된다`
     * @property doing 케이스 이름에 붙는 문장 — `\`Map_scene\` 화면으로 넘어간다`
     * @property effect 그것을 낸 효과. 도착 화면처럼 구조로 읽을 것이 있다.
     */
    data class Told(val expected: String, val doing: Doing, val effect: CapabilityEffectEntity)

    /**
     * [expectedEach] 와 같되 **어느 효과가 그 문장을 냈는지** 함께 낸다(ARTEL-614).
     *
     * 씬 전환 케이스가 "어느 화면으로 가나"를 구조적으로 들려면 필요하다 — 문장에서 다시 뽑으면
     * 그것이 곧 이 개편이 없애려는 산문 되읽기다. 이름에 쓰는 [Told.doing] 도 같은 이유로 여기서
     * 함께 낸다 — 기대결과 문장을 되읽어 능동으로 바꾸면 그 되읽기를 한 번 더 하는 것이다.
     */
    fun expectedWithSource(
        effects: List<CapabilityEffectEntity>,
        refs: Map<Pair<String, String>, Set<String>> = emptyMap(),
        methodId: String? = null,
        sceneNames: Set<String> = emptySet(),
    ): List<Told> {
        val seen = mutableSetOf<String>()
        return effects.filter { EffectCategory.from(it.category)?.assertable == true }
            .filterNot { it.kind in UNWATCHABLE }
            .mapNotNull { effect ->
                val target = watchableTarget(effect, refs, methodId, sceneNames) ?: return@mapNotNull null
                // 값을 못 읽은 자리는 문서가 그렇게 적어 둔다(`(not a literal)` · `(not a simple
                // receiver)`). 그대로 내면 "표시 상태가 `(not a literal)`" 처럼 읽을 수 없는 문장이
                // 된다 — 값을 빼고 "바뀐다"로 말한다. 무엇으로 바뀌는지는 모르지만 **바뀐다는 것은
                // 안다.** **값 쪽도 씬이 부르는 이름으로**(ARTEL-682): 대상만 풀고 값을 안 풀면 한 줄
                // 안에서 두 말이 섞인다 — 실측에서 `wordHead 의 위치가
                // MapMove.battle1.transform.position 로 바뀐다` 가 나왔다.
                val detail = readable(effect.detail)?.let { MapTestCaseTargets.resolve(it, refs) }
                Told(outcome(effect.kind, target, detail), doing(effect.kind, target, detail), effect)
            }
            .filter { seen.add(it.expected) }
    }

    // --- 조각 ------------------------------------------------------------------------

    /**
     * **문서가 관측 가능하다고 말해도 QA 실행이 못 보는 것**(ARTEL-616).
     *
     * 소리가 그렇다. 문서는 `category='observable'` 로 싣고 그 말이 틀리지도 않았다 — 사람은 들을
     * 수 있다. 그러나 이 표를 실행하는 것은 SDK 이고, **SDK 가 소리를 못 읽는다.** 기대결과에 적으면
     * 실행이 통과·실패를 말할 수 없는 줄이 되고, 그것이 곧 지킬 수 없는 명세다. 예전에 빼기로
     * 정한 것이 이 자리다.
     *
     * **`v_spec_gap` 과 일부러 갈린다.** 저쪽은 "이 기능이 명세가 될 수 있나"(개발 우선순위)를 묻고
     * 여기는 "QA 가 검증할 수 있나"를 묻는다. 소리는 앞의 답이 예이고 뒤의 답이 아니오다 — 두 값이
     * 같아야 한다고 맞추면 둘 중 하나가 거짓말을 하게 된다.
     *
     * 지도가 `capability_effect.watchable` 로 이 판정을 들 자리를 이미 만들어 두었지만 적재가 아직
     * 안 채운다. 채워지면 이 목록 대신 그것을 본다.
     */
    private val UNWATCHABLE = setOf("audio")

    /**
     * 효과 하나를 한 마디로.
     *
     * `kind` 는 문자열이다 — 어휘를 enum 으로 못 박은 자리가 아직 없고, 적재기가 문서의 값을 그대로
     * 싣는다. 실측(적재기 지도 486건)에서 나온 열 가지를 다룬다.
     */
    /**
     * 이름을 코드 원문으로 감싼다. **이미 감싸 온 것은 다시 감싸지 않는다** — 목록이 가리키는
     * 것을 여럿 적은 자리가 그렇다(`` `Background1` · `Background2` ``). 한 겹 더 두르면
     * 어디까지가 한 이름인지 읽는 사람이 알 수 없다.
     */
    private fun code(target: String): String = if (target.startsWith("`")) target else "`$target`"

    private fun outcome(kind: String, target: String, detail: String?): String = when (kind) {
        "scene" -> "${code(target)} 화면으로 전환된다"
        "active-state" -> "${code(target)} 의 표시 상태가 ${detail?.let { "`$it`" } ?: "바뀐다"}"
        "ui-value" -> "${code(target)} 표시가 ${detail?.let { "`$it` 로 " } ?: ""}갱신된다"
        "instantiate" -> "${code(target)} 이(가) 생성된다"
        "destroy" -> "${code(target)} 이(가) 사라진다"
        "animation" -> "${code(target)} 애니메이션이 실행된다"
        "audio" -> "${code(target)} 소리가 난다"
        "transform" -> "${code(target)} 의 위치/형태가 ${detail?.let { "`$it` 로 " } ?: ""}바뀐다"
        "saved" -> "${code(target)} 이(가) 저장된다"
        // 어휘를 모르면 원문을 그대로 붙여 낸다. 새 `kind` 가 생겼을 때 그 효과가 조용히
        // 사라지는 것보다, 어색해도 보이는 편이 낫다.
        else -> detail?.let { "${code(target)} 이(가) `$it` 이 된다" } ?: "${code(target)} 이(가) 바뀐다"
    }

    /**
     * 효과 하나를 **이름에 쓸 능동꼴로.** [outcome] 과 한 쌍이고 같은 `kind` 를 본다.
     *
     * 뜻을 더하지 않는다 — `active-state` 의 `true`/`false` 를 켠다/끈다로 부르는 것까지가
     * `kind` 와 값이 말한 것이고, 그 너머(무엇을 위해 켜는가)는 적지 않는다.
     *
     * @property what 무엇을 — `` `Congratulation` 을(를) ``
     * @property ends 문장을 끝내는 꼴 — `켠다`
     * @property joins 뒤에 하나 더 잇는 꼴 — `켜고`
     * @property counted 여럿일 때 세어 부르는 말
     */
    data class Doing(
        val kind: String,
        val what: String,
        val ends: String,
        val joins: String,
        val counted: Counted,
    )

    /**
     * 같은 종류를 여럿 셀 때 쓰는 말.
     *
     * 동사가 [Doing.ends] 와 **다를 수 있다.** `active-state` 셋이 켜기 둘 · 끄기 하나일 수 있으니
     * "표시 상태 3곳을 켠다"는 거짓이다. 종류가 말하는 데까지만 — "바꾼다"다.
     */
    data class Counted(val noun: String, val unit: String, val ends: String, val joins: String)

    /** 세는 단위는 조사까지 함께 든다 — `곳` 뒤는 `을` 이고 `개` 뒤는 `를` 이라 규칙 하나로 못 붙인다. */
    private const val PLACES = "곳을"

    private const val THINGS = "개를"

    private fun doing(kind: String, target: String, detail: String?): Doing {
        val value = detail?.let { "`$it` 로 " } ?: ""
        fun of(what: String, ends: String, joins: String, counted: Counted) =
            Doing(kind, what.trim(), ends, joins, counted)
        return when (kind) {
            "scene" -> of("${code(target)} 화면으로", "넘어간다", "넘어가고", Counted("화면", PLACES, "넘어간다", "넘어가고"))
            "active-state" -> {
                val counted = Counted("표시 상태", PLACES, "바꾼다", "바꾸고")
                when (detail) {
                    "true" -> of("${code(target)} 을(를)", "켠다", "켜고", counted)
                    "false" -> of("${code(target)} 을(를)", "끈다", "끄고", counted)
                    else -> of("${code(target)} 의 표시 상태를", "바꾼다", "바꾸고", counted)
                }
            }
            "ui-value" -> of("${code(target)} 표시를 $value", "갱신한다", "갱신하고", Counted("글자", PLACES, "갱신한다", "갱신하고"))
            "instantiate" -> of("${code(target)} 을(를)", "만든다", "만들고", Counted("오브젝트", THINGS, "만든다", "만들고"))
            "destroy" -> of("${code(target)} 을(를)", "없앤다", "없애고", Counted("오브젝트", THINGS, "없앤다", "없애고"))
            "animation" -> of("${code(target)} 애니메이션을", "재생한다", "재생하고", Counted("애니메이션", THINGS, "재생한다", "재생하고"))
            "audio" -> of("${code(target)} 소리를", "낸다", "내고", Counted("소리", "가지를", "낸다", "내고"))
            "transform" -> of("${code(target)} 을(를) $value", "옮긴다", "옮기고", Counted("위치", PLACES, "옮긴다", "옮기고"))
            "saved" -> of("${code(target)} 을(를)", "저장한다", "저장하고", Counted("값", THINGS, "저장한다", "저장하고"))
            else -> of("${code(target)} 을(를) $value", "바꾼다", "바꾸고", Counted("자리", PLACES, "바꾼다", "바꾸고"))
        }
    }

    /**
     * 효과가 가리키는 것을 **실행하는 사람이 찾을 수 있는 이름으로.** 못 찾을 이름이면 null 이고,
     * 그 효과는 케이스에 안 실린다([named] 에 이유가 있다).
     */
    private fun watchableTarget(
        effect: CapabilityEffectEntity,
        refs: Map<Pair<String, String>, Set<String>>,
        methodId: String?,
        sceneNames: Set<String>,
    ): String? = effect.target?.takeIf { it.isNotBlank() }
        // 씬 전환의 대상은 화면 이름이라 오브젝트가 아니다. 되짚을 것이 없다.
        ?.let {
            if (effect.kind == "scene") it
            // 자기 자신을 가리키는 자리를 먼저 되돌린다 — 그래야 씬이 답할 이름이 된다.
            // 씬이 이름으로 찾는 자리도 여기서 맞춰 본다.
            else MapTestCaseTargets.resolve(
                MapTestCaseTargets.ofScene(MapTestCaseTargets.ofOwner(it, methodId), sceneNames), refs,
            )
        }
        ?.takeIf(::named)

    /**
     * **가리키는 것에 이름이 있나.**
     *
     * 문서는 수신자를 못 읽은 자리도 값과 같은 모양으로 적어 둔다 — `(not a simple receiver)`.
     * 값이 그러면 [readable] 이 값을 빼고 "바뀐다"로 말하면 되지만, **대상이 그러면 말할 것이
     * 없다**: `(not a simple receiver) 의 표시 상태가 false` 는 실행하는 사람에게 무엇을 보라는
     * 것인지 하나도 말하지 않는다. 값을 모르는 것과 대상을 모르는 것은 다르다.
     *
     * 그래서 그 효과는 낸다 대신 뺀다. 한 기능의 효과가 전부 이러면 케이스 자체가 안 나가고,
     * 그것이 맞다 — 확인할 것이 있어야 케이스다.
     *
     * 뒤가 붙어 있어도 마찬가지다(`(not a simple receiver).sprite`). 앞이 이름이 아니면 뒤를
     * 붙여도 찾을 수 없다.
     */
    private fun named(target: String): Boolean = !target.startsWith("(")

    /**
     * 값으로 적을 수 있는 것만 남긴다.
     *
     * 문서가 못 읽은 자리를 세 가지 모양으로 적는다:
     *
     * - `(not a literal)` · `(not a simple receiver)` — 괄호로 시작한다
     * - `_` — 자리만 있고 값이 없다. `표시가 \`_, true\` 로 갱신된다` 가 그렇게 나왔다
     * - `값, true` — `SetText(값, 플래그)` 의 **둘째 인자가 새어 나온 것**이다. 화면에서 볼 수
     *   있는 것은 앞의 값뿐이고, 뒤의 불린은 코드가 자기에게 하는 말이다
     *
     * 값을 빼고 "바뀐다"로 말한다. 무엇으로 바뀌는지는 몰라도 **바뀐다는 것은 안다**(ARTEL-602).
     */
    private fun readable(detail: String?): String? {
        val head = detail?.replace(FLAG_TAIL, "")?.trim()?.takeIf { it.isNotBlank() } ?: return null
        if (head.startsWith("(")) return null
        if (saysNothing(head)) return null
        // `_` 하나이거나 `_` 만 든 자리는 값이 아니다.
        return head.takeIf { it != PLACEHOLDER && !PLACEHOLDER_ONLY.matches(it) }
    }

    /**
     * **호출은 방법이지 값이 아니다**(실측 7건).
     *
     * ```
     * `Player.HpText.text` 표시가 `Int32.ToString()` 로 갱신된다
     * ```
     *
     * `Int32.ToString()` 은 "그 수를 글자로" 라는 **방법**을 말한다. 화면에 뜨는 글자가 아니라서
     * 실행하는 사람이 그것을 찾으면 없다. 값을 못 읽은 자리(`(not a literal)`)와 같은 처지이므로
     * 같이 다룬다 — 값을 빼고 "갱신된다"로 말한다. **무엇으로 바뀌는지는 몰라도 바뀐다는 것은
     * 안다**(ARTEL-602).
     *
     * **인자에 진짜 값이 하나라도 있으면 남긴다.** `SetTrigger("Death")` 와
     * `String.Concat("Stage : ", …)` 는 화면에서 찾을 것을 말한다.
     *
     * `op_` 로 시작하는 이름은 **.NET 이 연산자에 붙이는 이름**이다(`*` 가 `op_Multiply`). 게임이
     * 지은 이름이 아니고 사람이 쓰는 이름도 아니라, 인자에 수가 있어도 읽을 수 없다.
     */
    private fun saysNothing(value: String): Boolean = when {
        OPERATOR_NAME.containsMatchIn(value) -> true
        // 호출이 아니면 값이다 — `Vector3.zero` 는 게임이 그 이름으로 아는 자리다.
        !value.contains("(") -> false
        QUOTED.containsMatchIn(value) -> false
        NUMBER_ARG.containsMatchIn(value) -> false
        else -> true
    }

    /** .NET 이 연산자에 붙이는 메서드 이름. `a * b` 가 `op_Multiply(a, b)` 로 적힌다. */
    private val OPERATOR_NAME = Regex("""\bop_[A-Z]\w*\s*\(""")

    /** 따옴표로 묶인 글자. 화면에서 찾을 수 있는 값이다. */
    private val QUOTED = Regex(""""[^"]*"""")

    /**
     * 괄호 안에 든 수. 이름의 일부인 수는 세지 않는다 — `Item[0]` 의 색인은 대괄호 안이라 안 걸리고,
     * `Vector3` 의 `3` 은 앞에 글자가 붙어 있어 안 걸린다.
     */
    private val NUMBER_ARG = Regex("""\([^)]*(?<![\w.])-?\d+(?:\.\d+)?[^)]*\)""")

    /** `SetText(값, true)` 처럼 뒤에 붙는 불린 플래그. 화면에서 볼 수 있는 것이 아니다. */
    private val FLAG_TAIL = Regex(""",\s*(true|false)\s*$""")

    private const val PLACEHOLDER = "_"

    /** `_` 와 구두점뿐인 값. 무엇으로 바뀌는지 하나도 말하지 않는다. */
    private val PLACEHOLDER_ONLY = Regex("""[_,\s]+""")

    /**
     * 조건 트리에서 **상태만** 한 줄로. `gesture` 는 뺀다([step] 이 든다).
     *
     * `either` 는 `또는` 으로 잇는다. 여기서 교집합을 취하지 않는 이유는 이 문장이 **사람이 읽는
     * 것**이기 때문이다 — 코드가 근거로 쓸 때는 좁혀야 하지만(`ScenarioConditionTree.guards`),
     * 사람에게는 명세가 말한 대로 보여 주는 것이 맞다.
     */
    private fun stateText(node: ConditionNode?, scene: String): String? = when (node) {
        null, is ConditionNode.Always, is ConditionNode.Gesture -> null
        // 못 읽은 조건을 사전조건에 적으면 실행하는 사람이 만들 수 없는 상태를 요구받는다.
        is ConditionNode.Unknown -> null
        // **"그 화면인 상태"를 두 번 말하지 않는다.** 코드가 `SceneManager.GetActiveScene().name`
        // 으로 자기 화면을 확인하는 것은 흔하고, 그것을 그대로 실으면 사전조건 첫 줄이 화면 이름을
        // 두 번 부른다. 구버전도 같은 자리를 흡수한다(`absorb_active_scene_condition`).
        is ConditionNode.Test ->
            // **코드가 자기 참조를 확인하는 자리는 전제가 아니다**(ARTEL-623). `inputBlocker != null`
            // 은 씬이 로드됐으면 늘 참이고, 테스터가 만들 상태가 아니라 개발자가 빠뜨린 연결을 막는
            // 방어다. 전제에 실으면 실행하는 사람이 만들 수 없는 것을 요구받는다 — 실측(QA 런)에서
            // 그런 줄이 통째로 `SETUP-FAILED` 로 끝났다.
            if (referenceCheck(node)) {
                null
            } else if (node.operator == "==" && ACTIVE_SCENE.matches(node.left) &&
                node.right.trim('"') == scene
            ) {
                null
            } else {
                "${node.left} ${node.operator} ${node.right}".trim().ifBlank { null }
            }
        is ConditionNode.Group -> {
            val joiner = if (node.kind == GroupKind.EVERY) AND else OR
            val nested = if (node.kind == GroupKind.EVERY) OR else AND
            val parts = node.parts.mapNotNull { stateText(it, scene) }
                // 같은 갈래끼리는 편다. 갈래를 이어 붙이면 같은 비교가 여러 번 들어오고(호출자
                // 조건과 불린 쪽 조건이 겹친다), 사람이 읽는 글이라 같은 말을 두 번 하지 않는다.
                .flatMap { if (it.contains(nested)) listOf(it) else it.split(joiner) }
                .map { it.trim() }.filter { it.isNotBlank() }.distinct()
            when (parts.size) {
                0 -> null
                // **혼자 남으면 괄호를 안 친다.** 형제가 흡수되어 사라지는 일이 흔하다(씬 확인·입력
                // 판정·못 읽은 조건). 그때까지 괄호를 채우면 문장 전체가 괄호에 싸인다.
                1 -> parts.single()
                // **다른 갈래를 품으면 괄호를 친다**(ARTEL-602). `A 그리고 (B 또는 C)` 를 괄호 없이
                // 적으면 `(A 그리고 B) 또는 C` 로도 읽혀 실행하는 사람이 다른 상태를 준비한다.
                // 실측에서 10건이 그 모양이었다.
                else -> parts.joinToString(joiner) { if (it.contains(nested)) "($it)" else it }
            }
        }
    }

    /**
     * 같은 자리에서 바꿔 쓸 수 있는 조작들을 한 문장으로(ARTEL-602).
     *
     * `\`RightArrow\` 키를 누른다` 와 `\`UpArrow\` 키를 누른다` 는 뒤가 같다. 그 공통 꼬리를 한 번만
     * 적고 앞의 것만 `또는` 으로 잇는다 — "`RightArrow` 또는 `UpArrow` 키를 누른다".
     *
     * 꼬리가 다르면(키와 클릭이 섞이면) 문장을 통째로 잇는다. 억지로 한 문장을 만들면 무엇을
     * 하라는 것인지 알 수 없어진다.
     */
    fun eitherStep(steps: List<String>): String {
        val distinct = steps.distinct()
        if (distinct.size == 1) return distinct.single()
        val tail = distinct.map { it.substringAfter("` ", "") }.distinct().singleOrNull()
        if (tail.isNullOrBlank()) return distinct.joinToString(OR)
        return distinct.joinToString(OR) { it.substringBefore("` ") + "`" } + " " + tail
    }

    /** 되돌아가는 갈래를 다 돌고 나온 자리(ARTEL-613). 몇 번인지는 지도가 말하지 않는다. */
    private const val REPEATEDLY = "더 진행되지 않을 때까지"

    private const val AND = " 그리고 "

    private const val OR = " 또는 "

    /**
     * 입력을 묻는 **호출**. `IsAdvanceKeyDown()` · `IsAnyKeyDown()` · `GetButtonDown()` 같은 모양이다.
     * 특정 이름을 넣지 않는다 — 키·버튼·입력을 낀 판정 호출이라는 구조만 본다.
     */
    private val INPUT_CHECK = Regex("""\b\w*(Key|Button|Input)\w*\s*\(\s*\)""")

    /**
     * 오브젝트가 **거기 있나**를 묻는 자리(ARTEL-623).
     *
     * `X != null` 하나뿐이다. 씬이 로드됐으면 늘 참이고, 아니면 개발자가 인스펙터 연결을 빠뜨린
     * 것이라 QA 가 만들 수 있는 상태가 아니다.
     *
     * `== null` 은 뺴지 않는다 — 그것은 "아직 없는 상태"를 진짜로 요구하는 갈래일 수 있다.
     */
    private fun referenceCheck(node: ConditionNode.Test): Boolean =
        node.operator == "!=" && node.right.trim() == "null"

    /** 지금 화면을 코드가 스스로 확인하는 자리. 사전조건이 이미 그 화면을 말했다. */
    private val ACTIVE_SCENE = Regex("""SceneManager\.GetActiveScene\(\)\.name""")

    /** 명세가 "아무 키나"라고 말한 자리. 키 이름이 아니다. */
    private const val ANY_KEY = "any"
}
