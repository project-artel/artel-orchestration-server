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
    fun step(
        interaction: String,
        inputKey: String?,
        controlLabel: String?,
        controlPath: String?,
        repeatUntilDone: Boolean = false,
    ): String {
        val once = when {
            controlLabel != null -> "`$controlLabel` 을(를) 클릭한다"
            controlPath != null -> "`$controlPath` 을(를) 클릭한다"
            inputKey == ANY_KEY -> "아무 키나 누른다"
            inputKey != null -> "`$inputKey` 키를 ${verb(interaction)}"
            else -> "조작 미상($interaction)"
        }
        // 활용을 건드리지 않는다. "누른다"의 어간은 "누르-"라 어미만 떼면 "누른되"가 되고,
        // 조작 문구는 키·경로·클릭이 섞여 있어 규칙 하나로 활용할 수 없다. 문장을 그대로 두고
        // 줄표로 잇는다 — 저작 모델이 같은 자리에서 스스로 쓰는 모양이기도 하다.
        return if (repeatUntilDone) "$once — 더 진행되지 않을 때까지 되풀이한다" else once
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
        expectedWithSource(effects, emptyMap()).map { it.first }

    /**
     * [expectedEach] 와 같되 **어느 효과가 그 문장을 냈는지** 함께 낸다(ARTEL-614).
     *
     * 씬 전환 케이스가 "어느 화면으로 가나"를 구조적으로 들려면 필요하다 — 문장에서 다시 뽑으면
     * 그것이 곧 이 개편이 없애려는 산문 되읽기다.
     */
    fun expectedWithSource(
        effects: List<CapabilityEffectEntity>,
        refs: Map<Pair<String, String>, Set<String>> = emptyMap(),
    ): List<Pair<String, CapabilityEffectEntity>> {
        val seen = mutableSetOf<String>()
        return effects.filter { EffectCategory.from(it.category)?.assertable == true }
            .filterNot { it.kind in UNWATCHABLE }
            .mapNotNull { effect -> outcome(effect, refs)?.let { it to effect } }
            .filter { seen.add(it.first) }
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
    private fun outcome(
        effect: CapabilityEffectEntity,
        refs: Map<Pair<String, String>, Set<String>> = emptyMap(),
    ): String? {
        val target = effect.target?.takeIf { it.isNotBlank() }
            // 씬 전환의 대상은 화면 이름이라 오브젝트가 아니다. 되짚을 것이 없다.
            ?.let { if (effect.kind == "scene") it else MapTestCaseTargets.resolve(it, refs) }
            ?: return null
        // 값을 못 읽은 자리는 문서가 그렇게 적어 둔다(`(not a literal)` · `(not a simple receiver)`).
        // 그대로 내면 "표시 상태가 `(not a literal)`" 처럼 읽을 수 없는 문장이 된다 — 값을 빼고
        // "바뀐다"로 말한다. 무엇으로 바뀌는지는 모르지만 **바뀐다는 것은 안다.**
        val detail = readable(effect.detail)
        return when (effect.kind) {
            "scene" -> "`$target` 화면으로 전환된다"
            "active-state" -> "`$target` 의 표시 상태가 ${detail?.let { "`$it`" } ?: "바뀐다"}"
            "ui-value" -> "`$target` 표시가 ${detail?.let { "`$it` 로 " } ?: ""}갱신된다"
            "instantiate" -> "`$target` 이(가) 생성된다"
            "destroy" -> "`$target` 이(가) 사라진다"
            "animation" -> "`$target` 애니메이션이 실행된다"
            "audio" -> "`$target` 소리가 난다"
            "transform" -> "`$target` 의 위치/형태가 ${detail?.let { "`$it` 로 " } ?: ""}바뀐다"
            "saved" -> "`$target` 이(가) 저장된다"
            // 어휘를 모르면 원문을 그대로 붙여 낸다. 새 `kind` 가 생겼을 때 그 효과가 조용히
            // 사라지는 것보다, 어색해도 보이는 편이 낫다.
            else -> detail?.let { "`$target` 이(가) `$it` 이 된다" } ?: "`$target` 이(가) 바뀐다"
        }
    }

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
        // `_` 하나이거나 `_` 만 든 자리는 값이 아니다.
        return head.takeIf { it != PLACEHOLDER && !PLACEHOLDER_ONLY.matches(it) }
    }

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

    private fun verb(interaction: String): String =
        if (interaction == Interaction.PRESS.wire) "누른다" else "입력한다"

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
