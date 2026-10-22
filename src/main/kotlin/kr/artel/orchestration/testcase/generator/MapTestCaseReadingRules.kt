package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.ConditionPrune
import kr.artel.orchestration.contentmap.evidence.GroupKind

/**
 * **판독 기준으로 TC 를 다시 묶는 규칙**(ARTEL-1209).
 *
 * 생성기는 word-venture 1.0 에서만 성립하던 가정 위에 서 있었다. 1.4.0 스캔에서 TC 가 84 건에서
 * 2,431 건이 되며 세 가정이 깨졌다:
 *
 * - 기능 하나는 씬 하나에 산다 — 모든 씬에 깔린 설정 메뉴가 씬마다 한 벌씩 나왔다
 * - 효과까지의 호출 사슬 사이의 조건은 테스터가 준비할 상태다 — 사슬이 10 단계를 넘는 기록이 1,342 건이었다
 * - 근거 문서는 성립할 수 없는 조건 조합을 내지 않는다 — 모순 313 건, 다른 씬이어야 성립하는 조건 195 건
 *
 * 여기 규칙은 게임 이름을 하나도 들지 않는다. 가르는 기준은 SDK 판독(`pulse`)이 무엇을 싣는가와 지도가
 * 무엇을 아는가 둘뿐이다.
 */
object MapTestCaseReadingRules {

    /**
     * **판독으로 확인되는 비교만 남긴 사전조건**과, 지운 것의 서명.
     *
     * 판독이 못 보는 비교(호출 · 지역 변수 · 엔진 값, [MapTestCaseReadings])는 지운다. QA 가 그것을 확인하려
     * 들면 없는 값을 찾는다. `또는` 의 한쪽을 지우면 전제가 좁아질 뿐 여전히 그 TC 에 닿는 길이라 남은 쪽을 둔다.
     *
     * [removed] 는 지운 비교들의 서명이다. 그것만 다르고 결과가 다른 갈래끼리는 QA 가 어느 쪽인지 알 수 없어
     * "다음 중 하나" 로 합친다([alternatives]).
     */
    data class Readable(val condition: ConditionNode?, val removed: String)

    fun readable(node: ConditionNode?): Readable {
        val removed = mutableListOf<String>()
        return Readable(kept(node, removed), removed.sorted().joinToString(","))
    }

    private fun kept(node: ConditionNode?, removed: MutableList<String>): ConditionNode? = when (node) {
        null -> null
        is ConditionNode.Test -> if (node.readable) node else null.also { removed += ConditionPrune.signature(node) }
        is ConditionNode.Group -> {
            val parts = node.parts.mapNotNull { kept(it, removed) }
            when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> ConditionNode.Group(node.kind, parts)
            }
        }
        else -> node
    }

    /**
     * **사전조건을 뜻을 바꾸지 않고 정리한 것.** [impossible] 이면 갈래가 하나도 안 남았다.
     *
     * 두 가지를 한다(실측 1.4.0 에서 비교 5 개 이상인 사전조건 52 건 가운데):
     *
     * - **바깥 조건과 부딪히는 `또는` 갈래를 뺀다**(18 건). 근거 문서는 `flag == 0` 아래에서 부른 메서드의 조건
     *   트리를 통째로 붙여서, 그 안의 `flag != 0` 갈래까지 들어온다. 그 갈래는 값을 쓰기 전이라 갈 수 없는 길이거나,
     *   값을 쓴 뒤의 일이라 "조작하기 전 상태" 가 아니다. 어느 쪽이든 사전조건이 아니다
     * - **같은 덩어리는 한 번만 쓴다**(16 건)
     */
    data class Simplified(val condition: ConditionNode?, val impossible: Boolean)

    fun simplified(node: ConditionNode?): Simplified {
        if (node == null) return Simplified(null, false)
        val out = pruned(node, emptyList())
        return if (out == DEAD) Simplified(null, true) else Simplified(out, false)
    }

    private val DEAD = ConditionNode.Unknown("dead", null)

    private fun pruned(node: ConditionNode, context: List<ConditionNode.Test>): ConditionNode? = when (node) {
        is ConditionNode.Group -> when (node.kind) {
            GroupKind.EVERY -> {
                val here = context + node.parts.filterIsInstance<ConditionNode.Test>()
                if (clashes(here)) DEAD
                else {
                    val parts = node.parts.map { pruned(it, here) }
                    if (parts.any { it == DEAD }) DEAD else joined(GroupKind.EVERY, parts.filterNotNull())
                }
            }
            GroupKind.EITHER -> {
                val arms = node.parts.mapNotNull { arm ->
                    val own = when (arm) {
                        is ConditionNode.Test -> listOf(arm)
                        is ConditionNode.Group -> if (arm.kind == GroupKind.EVERY) arm.parts.filterIsInstance<ConditionNode.Test>() else emptyList()
                        else -> emptyList()
                    }
                    if (clashes(context + own)) null else pruned(arm, context + own).takeUnless { it == DEAD }
                }
                if (arms.isEmpty()) DEAD else joined(GroupKind.EITHER, arms)
            }
        }
        else -> node
    }

    private fun joined(kind: GroupKind, parts: List<ConditionNode>): ConditionNode? {
        val unique = parts.distinctBy(ConditionPrune::signature)
        return when (unique.size) {
            0 -> null
            1 -> unique.single()
            else -> ConditionNode.Group(kind, unique)
        }
    }

    /** 함께 참이어야 하는 비교들 사이에 한 값이 둘인 것이 있나. 씬 비교는 [impossible] 이 따로 본다. */
    private fun clashes(tests: List<ConditionNode.Test>): Boolean {
        val equals = mutableMapOf<String, MutableSet<String>>()
        val differs = mutableMapOf<String, MutableSet<String>>()
        for (test in tests) {
            val right = test.right.trim()
            if (!LITERAL.matches(right)) continue
            when (test.operator) {
                "==" -> equals.getOrPut(test.left.trim()) { mutableSetOf() } += right
                "!=" -> differs.getOrPut(test.left.trim()) { mutableSetOf() } += right
            }
        }
        return equals.any { (left, values) -> values.size > 1 || values.any { it in differs[left].orEmpty() } }
    }

    /**
     * **이 사전조건이 읽는 토글들.** 토글은 지도에서 어떤 효과가 쓰고 어떤 조건이 읽는 값이다 — 조작하면 바뀌는
     * 상태라, 같은 화면에서 `flag == 0`(첫 번째 키) 과 `flag != 0`(두 번째 키) 을 차례로 거친다. 시나리오가 순서를
     * 세울 때 쓰도록 남긴다.
     *
     * @param written 지도에서 효과가 쓰는 값들
     */
    fun toggles(node: ConditionNode?, written: Set<String>): List<String> {
        val all = mutableListOf<String>()
        fun walk(n: ConditionNode?) {
            when (n) {
                is ConditionNode.Test -> if (n.left.trim() in written) all += n.left.trim()
                is ConditionNode.Group -> n.parts.forEach(::walk)
                else -> Unit
            }
        }
        walk(node)
        return all.distinct().sorted()
    }

    /**
     * **이 사전조건은 성립할 수 없는가.**
     *
     * 함께 참이어야 하는 비교들(`그리고` 로 이어진 것, `또는` 안은 보지 않는다) 사이에서 두 모양만 본다:
     *
     * - 같은 값을 두고 `== a` 와 `!= a`, 또는 `== a` 와 `== b` — 한 값이 둘일 수 없다
     * - 지금 씬이 이 TC 의 씬이 아니어야 하는 비교 — `GetActiveScene().name == "다른씬"` 이나 `!= "이씬"`
     *
     * 근거 문서가 호출 사슬을 따라 조건을 모으다 생긴 것이다. 그런 갈래는 실행할 수 없어 버린다.
     */
    fun impossible(node: ConditionNode?, scene: String): Boolean {
        val all = mutableListOf<ConditionNode.Test>()
        conjuncts(node, all)
        val equals = mutableMapOf<String, MutableSet<String>>()
        val differs = mutableMapOf<String, MutableSet<String>>()
        for (test in all) {
            val left = test.left.trim()
            val right = test.right.trim()
            if (left == ACTIVE_SCENE) {
                val named = right.trim('"')
                if (test.operator == "==" && named != scene) return true
                if (test.operator == "!=" && named == scene) return true
            }
            if (!LITERAL.matches(right)) continue
            when (test.operator) {
                "==" -> equals.getOrPut(left) { mutableSetOf() } += right
                "!=" -> differs.getOrPut(left) { mutableSetOf() } += right
            }
        }
        return equals.any { (left, values) ->
            values.size > 1 || values.any { it in differs[left].orEmpty() }
        }
    }

    private fun conjuncts(node: ConditionNode?, out: MutableList<ConditionNode.Test>) {
        when (node) {
            is ConditionNode.Test -> out += node
            is ConditionNode.Group -> if (node.kind == GroupKind.EVERY) node.parts.forEach { conjuncts(it, out) }
            else -> Unit
        }
    }

    /**
     * **이 기대 항목을 판독으로 확인할 수 있는가.**
     *
     * 판독이 싣는 것은 셋이다: 지금 씬, 근거 문서가 이름 댄 게임 타입의 멤버(SDK 가 감시 목록을 근거 문서에서
     * 만든다), 그리고 오브젝트의 경로 · 켜짐 · 위치 · 글자 · 그림. 그래서 확인되는 항목은
     *
     * - 씬 전환
     * - 판정 구조가 게임 타입 멤버(`look_at`)를 든 것
     * - 대상이 지도가 아는 그 씬의 오브젝트 경로인 것. 여럿을 적은 자리(`` `A` · `B` ``)는 모두 알아야 한다
     * - 화면에 붙어 있는지 확인하는 것 — 그 경로를 SDK 가 스캔에서 직접 보았다
     *
     * 타입 이름이 대상인 것(`DraggableCard` 의 위치)이나 호출식(`GameObject.GetComponentInChildren().text`)은
     * 어느 것에도 들지 않는다.
     */
    fun collectable(check: MapTestCaseCheck?, sceneNames: Set<String>): Boolean {
        if (check == null) return false
        if (check.kind == SCENE || check.kind == PRESENCE || check.lookAt != null) return true
        val names = check.name.split(NAMES).map { it.trim().trim('`') }.filter { it.isNotEmpty() }
        return names.isNotEmpty() && names.all { name -> sceneNames.any { it == name || it.endsWith("/$name") } }
    }

    /**
     * **씬만 다른 같은 TC 를 하나로.**
     *
     * 모든 씬에 깔린 UI 는 씬마다 같은 시험을 낸다. 조작 · 사전조건 · 기대결과 · 판정 구조가 씬 이름을 빼고
     * 같으면 같은 시험이라, 하나를 남기고 "어느 씬에서나" 를 적는다. QA 는 그 하나의 씬에서 확인한다.
     *
     * **남기는 씬은 입구 씬이 먼저다.** 가나다순으로 고르면 설정 메뉴 하나를 보려고 엔딩까지 가야 한다(실측
     * 1.4.0 에서 203 건이 `EndingScene` 에 몰렸다). 입구 씬은 게임이 처음 여는 씬이라 어느 TC 보다 가깝다.
     * 묶인 씬에 입구 씬이 없으면 처음 것을 남긴다.
     *
     * @param entryScene 지도가 아는 입구 씬(`scene.is_entry`). 모르면 null
     */
    fun acrossScenes(cases: List<MapTestCase>, entryScene: String? = null): List<MapTestCase> {
        val groups = cases.groupBy { case ->
            listOf(
                case.step.replace(case.scene, SCENE_MARK),
                case.precondition.removePrefix("${case.scene} 화면인 상태"),
                case.expected.replace(case.scene, SCENE_MARK),
                case.expectedChecks.toString(),
                case.watching.toString(),
            )
        }
        return groups.values.map { same ->
            val first = same.firstOrNull { it.scene == entryScene } ?: same.first()
            if (same.map { it.scene }.distinct().size < 2) return@map first
            first.copy(
                step = "${first.step} $ANY_SCENE",
                identity = first.identity + IDENTITY_SEPARATOR + ANY_SCENE_KEY,
            )
        }
    }

    /** 사람이 읽는 표시. 이 TC 는 여러 씬에서 같은 시험이라 하나만 냈다는 뜻이다. */
    const val ANY_SCENE = "(어느 씬에서나)"

    /** 판독으로 확인할 수 있는 기대가 하나도 안 남은 TC 의 기대결과. 조작이 되는지만 본다. */
    const val NO_READABLE_RESULT = "판독으로 확인할 수 있는 결과 없음 — 조작이 되는지만 본다"

    /** 기대결과 앞에 붙는 말. 항목 중 하나가 나오면 된다. */
    const val ONE_OF = "다음 중 하나: "

    private const val ANY_SCENE_KEY = "any-scene"
    private const val SCENE_MARK = "\u0000scene\u0000"
    private const val IDENTITY_SEPARATOR = "\u001F"
    private const val ACTIVE_SCENE = "SceneManager.GetActiveScene().name"
    private const val SCENE = "scene"
    private const val PRESENCE = "presence"
    private val NAMES = Regex("""\s·\s""")
    private val LITERAL = Regex("""^(-?\d+(\.\d+)?|".*"|null|true|false)$""")
}
