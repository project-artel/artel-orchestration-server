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
     * [removed] 는 지운 비교들의 서명이다. **그것이 다르면 다른 TC 다** — 갈래는 실제로 다른 동작이라 따로 시험해야
     * 한다. 판독이 못 볼 뿐 게임 안에서 만들 수는 있는 상태(저장 데이터 유무, 튜토리얼을 끝냈는지)가 대부분이라,
     * 지운 비교는 [setup] 으로 메타데이터에 남긴다(사전조건 문장에는 싣지 않는다). 만들 수 있는지는 코드가 아니라 저작이 판단한다 —
     * 지도는 그런 상태를 호출 결과로만 알아서, 어느 조작이 그것을 바꾸는지 잇지 못한다(실측 83 종 중 1 종).
     */
    data class Readable(val condition: ConditionNode?, val removed: String, val setup: List<String>)

    fun readable(node: ConditionNode?): Readable {
        val removed = mutableListOf<ConditionNode.Test>()
        val kept = kept(node, removed)
        // **준비할 상태도 원래 구조를 지킨다.** 지운 비교만 남긴 트리를 같은 규칙으로 정리해 적는다 — 낱개로 나열하면
        // `또는` 의 양쪽 갈래였던 `x != 0` 과 `x == 0` 이 "둘 다 준비하라" 가 된다(실측 1.4.0).
        val setup = unreadableOnly(node)?.let { simplified(it).condition }
            ?.takeUnless { it == ConditionNode.Always }
        return Readable(
            kept,
            setup?.let(ConditionPrune::signature).orEmpty(),
            listOfNotNull(setup?.let { MapTestCasePhrasing.conditionText(it) }),
        )
    }

    /** 판독이 못 보는 비교만 남긴 트리. 구조(그리고 · 또는)는 그대로다. */
    private fun unreadableOnly(node: ConditionNode?): ConditionNode? = when (node) {
        null -> null
        is ConditionNode.Test -> if (node.readable) null else node.copy(readable = true)
        is ConditionNode.Group -> {
            val parts = node.parts.mapNotNull(::unreadableOnly)
            when (parts.size) {
                0 -> null
                1 -> parts.single()
                else -> ConditionNode.Group(node.kind, parts)
            }
        }
        else -> null
    }

    private fun kept(node: ConditionNode?, removed: MutableList<ConditionNode.Test>): ConditionNode? = when (node) {
        null -> null
        is ConditionNode.Test -> if (node.readable) node else null.also { removed += node }
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
     * **그 씬에 놓이지 않은 타입의 비교는 판독이 못 본다**(ARTEL-1209).
     *
     * 판독은 그 씬에 놓인 오브젝트의 멤버만 싣는다. 실측 1.4.0 에서 TitleScene 의 `Escape` 키 TC 에
     * `MapMove.movement == 0` 이 붙었는데, `MapMove` 는 MapScene 에만 있다 — 깊은 호출 사슬을 따라 다른 씬의
     * 코드 조건까지 모인 것이다. 정적 멤버(`context = static`)는 씬과 상관없이 실린다.
     *
     * **어느 씬에도 놓이지 않은 타입은 막지 않는다.** 코드가 실행 중에 만드는 것(튜토리얼 관리자 · 생성되는 적)은
     * 지도가 씬에 놓인 것으로 모른다. 1.0 판독 기록에서도 `DontDestroyOnLoad/TutorialController` 로 읽혔다.
     * 다른 씬에는 놓였는데 이 씬에는 없는 타입만 이 씬에서 못 본다.
     *
     * @param here 이 씬에 놓인 타입(마지막 마디). 씬을 넘어 살아남는 것도 든다
     * @param anywhere 어느 씬에든 놓인 타입
     */
    fun inScene(node: ConditionNode?, here: Set<String>, anywhere: Set<String>): ConditionNode? = when (node) {
        null -> null
        is ConditionNode.Test ->
            if (node.context == STATIC || (rooted(node.left, here, anywhere) && rooted(node.right, here, anywhere))) node
            // **이 씬에서는 갈 수 없는 길이다.** 그 오브젝트가 없으니 그 비교가 걸린 갈래는 돌지 않는다. 판독 못 하는
            // 비교(준비할 상태)와 다르다 — 준비해서 만들 수 있는 것이 아니다. [simplified] 가 `그리고` 면 갈래째,
            // `또는` 의 한쪽이면 그 쪽만 버린다.
            else DEAD
        is ConditionNode.Group -> node.copy(parts = node.parts.map { inScene(it, here, anywhere)!! })
        else -> node
    }

    /** 값이 리터럴이거나 자기 자신(`this`)이면 씬과 상관없다. 멤버 경로면 그 첫 마디가 이 씬에 있거나, 어디에도 안 놓였어야 한다. */
    private fun rooted(text: String, here: Set<String>, anywhere: Set<String>): Boolean {
        val value = text.trim()
        if (value.isEmpty() || LITERAL.matches(value) || !value.contains('.')) return true
        val root = value.removePrefix("(").substringBefore('.').trim()
        return root in SELF || root in here || root !in anywhere
    }

    private val SELF = setOf("this", "Component", "SceneManager")
    private const val STATIC = "static"

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
                val arms = node.parts.map { arm ->
                    val own = when (arm) {
                        is ConditionNode.Test -> listOf(arm)
                        is ConditionNode.Group -> if (arm.kind == GroupKind.EVERY) arm.parts.filterIsInstance<ConditionNode.Test>() else emptyList()
                        else -> emptyList()
                    }
                    if (clashes(context + own)) DEAD else pruned(arm, context + own)
                }
                // 늘 참인 갈래가 하나라도 있으면 `또는` 전체가 늘 참이다.
                if (arms.any { it == null }) null
                else arms.filterNotNull().filterNot { it == DEAD }.let { live ->
                    if (live.isEmpty()) DEAD else joined(GroupKind.EITHER, live)
                }
            }
        }
        is ConditionNode.Test -> when (constant(node)) {
            true -> null
            false -> DEAD
            null -> node
        }
        else -> node
    }

    /**
     * **양쪽이 다 리터럴인 비교의 값.** 리터럴이 아닌 쪽이 있으면 `null` 이다.
     *
     * 근거 문서는 경로마다 조건을 모으며 값을 이미 아는 자리를 리터럴로 적는다. 컴파일러가 접은 상수
     * (`TakeHit(-AttackDamage)` → `-4 > 0`), 호출자가 넘긴 `true` · `false`(`if (useDotween)` → `0 != 0`),
     * `||` 의 앞이 참인 경로(`narration` → `1 == 0`)가 그렇다. 거짓이면 그 경로는 실행되지 않고, 참이면 조건이 아니다
     * (실측 1.4.1: 실행할 수 없는 TC 24 건).
     */
    fun constant(test: ConditionNode.Test): Boolean? {
        val left = test.left.trim()
        val right = test.right.trim()
        if (!LITERAL.matches(left) || !LITERAL.matches(right)) return null
        val a = left.toDoubleOrNull()
        val b = right.toDoubleOrNull()
        if (a != null && b != null) {
            return when (test.operator) {
                "==" -> a == b
                "!=" -> a != b
                "<" -> a < b
                "<=" -> a <= b
                ">" -> a > b
                ">=" -> a >= b
                else -> null
            }
        }
        return when (test.operator) {
            "==" -> left == right
            "!=" -> left != right
            else -> null
        }
    }

    private fun joined(kind: GroupKind, parts: List<ConditionNode>): ConditionNode? {
        val unique = (if (kind == GroupKind.EITHER) complemented(parts) else parts).distinctBy(ConditionPrune::signature)
        return when (unique.size) {
            0 -> null
            1 -> unique.single()
            else -> ConditionNode.Group(kind, unique)
        }
    }

    /**
     * **`(A 그리고 C) 또는 (A 아님 그리고 C)` 는 `C` 다.** 두 갈래가 한 비교의 참 · 거짓만 다르고 나머지가 같으면
     * 그 비교는 결과를 가르지 않는다. 실측 1.4.0 에서 `(movement == 0 그리고 …) 또는 (movement != 0 그리고 …)` 가
     * 그 모양이었다. 더 줄지 않을 때까지 되풀이한다.
     */
    private fun complemented(arms: List<ConditionNode>): List<ConditionNode> {
        var current = arms
        while (true) {
            val sets = current.map { arm -> conjunctsOf(arm) }
            var merged: Pair<Int, Int>? = null
            var common: List<ConditionNode> = emptyList()
            loop@ for (i in sets.indices) for (j in i + 1 until sets.size) {
                val a = sets[i] ?: continue
                val b = sets[j] ?: continue
                if (a.size != b.size) continue
                val onlyA = a.filter { x -> b.none { ConditionPrune.signature(it) == ConditionPrune.signature(x) } }
                val onlyB = b.filter { x -> a.none { ConditionPrune.signature(it) == ConditionPrune.signature(x) } }
                if (onlyA.size == 1 && onlyB.size == 1 && opposite(onlyA.single(), onlyB.single())) {
                    merged = i to j
                    common = a - onlyA.toSet()
                    break@loop
                }
            }
            val (i, j) = merged ?: return current
            val replacement = when (common.size) {
                0 -> return listOf(ConditionNode.Always)
                1 -> common.single()
                else -> ConditionNode.Group(GroupKind.EVERY, common)
            }
            current = current.filterIndexed { k, _ -> k != i && k != j } + replacement
        }
    }

    /** 갈래가 비교들의 `그리고` 이면 그 비교들. 아니면 null. */
    private fun conjunctsOf(arm: ConditionNode): List<ConditionNode>? = when (arm) {
        is ConditionNode.Test -> listOf(arm)
        is ConditionNode.Group -> if (arm.kind == GroupKind.EVERY && arm.parts.all { it is ConditionNode.Test }) arm.parts else null
        else -> null
    }

    private fun opposite(a: ConditionNode, b: ConditionNode): Boolean {
        if (a !is ConditionNode.Test || b !is ConditionNode.Test) return false
        if (a.left.trim() != b.left.trim() || a.right.trim() != b.right.trim()) return false
        return setOf(a.operator, b.operator) == setOf("==", "!=")
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
     * 함께 참이어야 하는 비교들(`그리고` 로 이어진 것, `또는` 안은 보지 않는다) 사이에서 세 모양만 본다:
     *
     * - 같은 값을 두고 `== a` 와 `!= a`, 또는 `== a` 와 `== b` — 한 값이 둘일 수 없다
     * - 지금 씬이 이 TC 의 씬이 아니어야 하는 비교 — `GetActiveScene().name == "다른씬"` 이나 `!= "이씬"`
     * - 양쪽이 리터럴인데 거짓인 비교 — `1 == 0`([constant])
     *
     * 근거 문서가 호출 사슬을 따라 조건을 모으다 생긴 것이다. 그런 갈래는 실행할 수 없어 버린다.
     */
    fun impossible(node: ConditionNode?, scene: String): Boolean {
        val all = mutableListOf<ConditionNode.Test>()
        conjuncts(node, all)
        val equals = mutableMapOf<String, MutableSet<String>>()
        val differs = mutableMapOf<String, MutableSet<String>>()
        for (test in all) {
            if (constant(test) == false) return true
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
     * **읽는 쪽이 구별할 수 없는 TC 는 하나다.**
     *
     * 준비할 상태([MapTestCase.setup])만 다른 갈래는 문장 · 판정 구조가 같다. 준비할 상태는 판독이 못 읽어 문장에 싣지
     * 않으므로, 사람도 QA 도 둘을 가를 수 없다(실측 1.4.1: 26 묶음 57 건). 하나를 남기고 준비할 상태는 [eitherSetup] 으로
     * 잇는다. 앞 단계는 준비할 상태까지 보고 묶어 정보를 잃지 않고, 여기서 한 번만 접는다. 정체는 남는 TC 의 것이다.
     */
    fun sameToReader(cases: List<MapTestCase>): List<MapTestCase> =
        cases.groupBy { listOf(it.scene, it.step, it.precondition, it.expected, it.expectedChecks.toString(), it.watching.toString()) }
            .values.map { same ->
                if (same.size == 1) same.single()
                else same.first().copy(setup = eitherSetup(same.map { it.setup }), gaps = same.flatMap { it.gaps }.distinct())
            }

    /**
     * 갈래마다의 준비할 상태를 하나로 잇는다. 하나만 있으면 그대로이고, 여럿이면 `또는` 하나로 적는다 —
     * 목록의 항목은 함께 참이어야 하는 것이라([MapTestCasePhrasing] 이 `그리고` 로 잇는다) 갈래를 나란히 넣으면 뜻이 바뀐다.
     */
    fun eitherSetup(setups: List<List<String>>): List<String> {
        val arms = setups.distinct()
        if (arms.size == 1) return arms.single()
        if (arms.any { it.isEmpty() }) return emptyList()
        return listOf(arms.joinToString(" 또는 ") { arm -> "(${arm.joinToString(" 그리고 ")})" })
    }

    /**
     * **씬만 다른 같은 TC 를 하나로.**
     *
     * 모든 씬에 깔린 UI 는 씬마다 같은 시험을 낸다. 조작 · 사전조건 · 기대결과 · 판정 구조가 씬 이름을 빼고
     * 같으면 같은 시험이라, 하나를 남기고 그 씬들을 적는다("여러 씬: A · B"). QA 는 그 하나의 씬에서 확인한다.
     *
     * **남기는 씬은 입구 씬이 먼저다.** 가나다순으로 고르면 설정 메뉴 하나를 보려고 엔딩까지 가야 한다(실측
     * 1.4.0 에서 203 건이 `EndingScene` 에 몰렸다). 입구 씬은 게임이 처음 여는 씬이라 어느 TC 보다 가깝다.
     * 묶인 씬에 입구 씬이 없으면 처음 것을 남긴다.
     *
     * @param entryScene 지도가 아는 입구 씬(`scene.is_entry`). 모르면 null
     * @param allScenes 지도의 씬 전부. 묶인 씬이 이것과 같으면 이름을 늘어놓지 않고 "모든 씬" 이라 적는다
     */
    fun acrossScenes(cases: List<MapTestCase>, entryScene: String? = null, allScenes: Set<String> = emptySet()): List<MapTestCase> {
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
                step = "${first.step} " + same.map { it.scene }.distinct().let { here ->
                    if (allScenes.isNotEmpty() && here.toSet() == allScenes) ALL_SCENES
                    else here.sortedWith(compareBy({ it != first.scene }, { it })).joinToString(" · ", prefix = ANY_SCENE, postfix = ")")
                },
                setup = eitherSetup(same.map { it.setup }),
                identity = first.identity + IDENTITY_SEPARATOR + ANY_SCENE_KEY,
            )
        }
    }

    /** 사람이 읽는 표시의 앞머리. 이 TC 는 뒤에 적은 씬들에서 같은 시험이라 하나만 냈다는 뜻이다. 남긴 씬이 맨 앞이다. */
    const val ANY_SCENE = "(여러 씬: "

    /** 지도의 씬 전부에 같은 시험이 있을 때. */
    const val ALL_SCENES = "(모든 씬)"

    /** 판독으로 확인할 수 있는 기대가 하나도 안 남은 TC 의 기대결과. 조작이 되는지만 본다. */
    const val NO_READABLE_RESULT = "판독으로 확인할 수 있는 결과 없음 — 조작이 되는지만 본다"

    private const val ANY_SCENE_KEY = "any-scene"
    private const val SCENE_MARK = "\u0000scene\u0000"
    private const val IDENTITY_SEPARATOR = "\u001F"
    private const val ACTIVE_SCENE = "SceneManager.GetActiveScene().name"
    private const val SCENE = "scene"
    private const val PRESENCE = "presence"
    private val NAMES = Regex("""\s·\s""")
    private val LITERAL = Regex("""^(-?\d+(\.\d+)?|".*"|null|true|false)$""")
}
