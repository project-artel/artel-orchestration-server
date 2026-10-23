package kr.artel.orchestration.testcase.generator

import kr.artel.orchestration.contentmap.dto.ContentMapCallEdge

/**
 * **공통 호출자로 조작과 결과를 잇는다**(ARTEL-554).
 *
 * 코루틴·상태 머신에서는 입력을 받는 갈래와 결과를 내는 갈래가 **다른 기능 행**이다. 실측
 * (word-venture, StoryScene · EndingScene):
 *
 * ```
 * StoryController.StoryTelling()            ← 공통 호출자
 *   ├─ 부른다 → IsAdvanceKeyDown()          입력 있음 · 효과 0     (조작 갈래)
 *   ├─ 부른다 → UpdateChatStream()          효과 있음 · 입력 0     (결과 갈래)
 *   ├─ 부른다 → SetAnyKeyPromptVisible()    효과 있음
 *   └─ 부른다 → LoadMapScene()              효과 있음 (화면 전환)
 * ```
 *
 * 그래서 두 씬은 TC 가 **0건**이었다. 조작 갈래에 효과가 없어서다.
 *
 * ## 진입점으로 이으면 안 된다
 *
 * `entry_id` 공유로 이어 봤더니 거짓 케이스가 나왔다 — `Map_scene` 에서 "`RightArrow` 를 누르면
 * 배경이 바뀐다". 배경은 씬 진입 때 `StageManager.SetBackground()` 가 정하는 것이고, 같은 진입점
 * 아래 있을 뿐 그 조작이 부른 것이 아니다. **진입점은 갈래의 출처이지 인과가 아니다.**
 *
 * 호출 엣지는 실제로 부른 것만 잇는다.
 */
object MapTestCaseSiblings {

    /**
     * 유니티가 매 프레임·진입마다 스스로 부르는 메서드들(ARTEL-680).
     *
     * 이것들이 부른 것끼리는 **서로의 원인이 아니다.** 같은 루프에 있을 뿐이다.
     */
    private val LIFECYCLE = setOf(
        "Update", "FixedUpdate", "LateUpdate", "Start", "Awake",
        "OnEnable", "OnDisable", "OnDestroy", "OnGUI",
    )

    /**
     * 어떤 기능의 결과를 [capabilityId] 가 빌려 올 수 있나.
     *
     * 자기를 부른 **메서드**를 찾고, 그 메서드가 부른 다른 것들을 낸다. 기능 행이 아니라 메서드로
     * 묶는 것이 요점이다 — 코루틴 하나가 갈래 16개로 쪼개지고 각 갈래가 호출을 하나씩만 든다.
     *
     * 한 단계만 본다. 더 타고 올라가면 `Update()` 같은 넓은 호출자에 닿아 무관한 것까지 딸려 온다.
     *
     * 값은 (빌려 올 기능, 그 호출이 일어나는 조건들). 조건은 **호출자의 것과 불린 쪽의 것 둘 다**다 —
     * 그 결과가 나려면 부르는 조건도 참이어야 한다.
     */
    fun of(capabilityId: Long, edges: List<ContentMapCallEdge>): List<Borrowed> = Index(edges).of(capabilityId)

    /**
     * **간선을 한 번만 색인한다**(ARTEL-1209).
     *
     * 앞서는 기능마다 간선 전체를 처음부터 훑고 간선마다 메서드 이름을 정규식으로 다시 풀었다. 기능 수 × 간선 수만큼
     * 일을 해서, 1.4.0(기능 7,863 개)에서 TC 생성의 약 95% 가 [sourceMethod] 에 묶였다(생성 5 분 남짓). 지도 하나를
     * 생성하는 동안 간선은 그대로라 한 번 색인하면 된다. 결과는 원래 간선 순서를 지킨다 — 순서가 바뀌면 기대결과
     * 항목의 순서가 바뀐다.
     */
    class Index(edges: List<ContentMapCallEdge>) {
        private val indexed = edges.withIndex().toList()
        private val byCallee = indexed.groupBy { it.value.capabilityId }
        private val sources = HashMap<String, String>()
        private val bySource = indexed.groupBy { source(it.value.callerMethodId) }

        private fun source(methodId: String): String = sources.getOrPut(methodId) { sourceMethod(methodId) }

        fun of(capabilityId: Long): List<Borrowed> {
            val callers = byCallee[capabilityId].orEmpty()
            if (callers.isEmpty()) return emptyList()
            val callerMethods = callers.map { source(it.value.callerMethodId) }
                .filterNot { it.substringAfterLast('|') in LIFECYCLE }
                .toSet()
            if (callerMethods.isEmpty()) return emptyList()
            return callerMethods.flatMap { bySource[it].orEmpty() }
                .filter { it.value.capabilityId != capabilityId }
                .sortedBy { it.index }
                .map { Borrowed(it.value.capabilityId, it.value.callerCondition, it.value.conditionTree) }
                .distinctBy { it.capabilityId }
        }
    }

    /** 색인 없이 한 번 묻는 옛 경로. 테스트가 쓴다. 생성기는 [Index] 를 쓴다. */
    fun scan(capabilityId: Long, edges: List<ContentMapCallEdge>): List<Borrowed> {
        val callers = edges.filter { it.capabilityId == capabilityId }
        if (callers.isEmpty()) return emptyList()
        // **생명주기 메서드는 공통 호출자가 아니다**(ARTEL-680). 위 주석이 걱정한 `Update()` 를
        // 한 단계 제한으로는 못 막는다 — 직접 호출자가 이미 `Update()` 인 경우가 있다.
        //
        // 실측(지도 31, Map_scene): `CharacterMove()`(Return 입력)와 `ShowBattle()`(배경 갱신)이
        // 둘 다 `Update()` 아래 있다. 그래서 Return 케이스가 배경 갱신 결과를 빌려 갔고, 조작
        // 하나에 케이스 열둘이 매달렸다. 저작이 "첫 스테이지"에 5스테이지 케이스를 고른 원인이다.
        //
        // 같은 프레임 루프에서 도는 것은 **형제일 뿐 인과가 아니다.** 이 파일이 이미 진입점 공유로
        // 겪은 일과 같은 것이고(위의 "RightArrow 를 누르면 배경이 바뀐다"), 유니티가 정한 이름이라
        // 게임에 붙는 규칙이 아니다.
        val callerMethods = callers.map { sourceMethod(it.callerMethodId) }
            .filterNot { it.substringAfterLast('|') in LIFECYCLE }
            .toSet()
        if (callerMethods.isEmpty()) return emptyList()
        return edges
            .filter { sourceMethod(it.callerMethodId) in callerMethods && it.capabilityId != capabilityId }
            .map { Borrowed(it.capabilityId, it.callerCondition, it.conditionTree) }
            .distinctBy { it.capabilityId }
    }

    /**
     * 컴파일러가 만든 이름을 **원래 메서드**로 되돌린다.
     *
     * C# 컴파일러는 코루틴과 람다를 별도 타입·메서드로 쪼갠다. 한 메서드가 둘로 갈리고, 그러면
     * 같은 소스 메서드가 부르는 것들이 서로 다른 `method_id` 아래 흩어진다. 실측
     * (`StoryController.StoryTelling()`):
     *
     * ```
     * Story.StoryController|<StoryTelling>b__8_0          ← 람다. IsAdvanceKeyDown 을 부른다
     * Story.StoryController/<StoryTelling>d__8|MoveNext   ← 상태 머신. LoadMapScene 을 부른다
     * ```
     *
     * 둘을 안 묶으면 **입력과 화면 전환이 영영 안 만난다.** StoryScene · EndingScene 의 케이스가
     * "어느 화면으로 가는지"를 말하지 못한 것이 이 때문이다.
     *
     * `<이름>` 안의 것이 원래 메서드 이름이다. 타입 쪽(`Owner/<Name>d__8`)에 있을 수도 있고
     * 메서드 쪽(`<Name>b__8_0`)에 있을 수도 있다. 게임에 붙는 규칙이 아니라 **컴파일러 관용구**다.
     */
    fun sourceMethod(methodId: String): String {
        val parts = methodId.split("|")
        if (parts.size < 3) return methodId
        val owner = parts[1].substringBefore('/')
        val generated = GENERATED.find(parts[1])?.groupValues?.get(1)
            ?: GENERATED.find(parts[2])?.groupValues?.get(1)
        return owner + "|" + (generated ?: parts[2])
    }

    /** `<StoryTelling>d__8` · `<StoryTelling>b__8_0` 에서 원래 이름을 꺼낸다. */
    private val GENERATED = Regex("""<([^>]+)>""")

    /**
     * @property capabilityId 결과를 든 기능.
     * @property callerCondition 그 호출이 일어나는 조건. 없으면 언제나 부른다.
     * @property ownCondition 불린 쪽 자신의 조건.
     */
    data class Borrowed(
        val capabilityId: Long,
        val callerCondition: io.r2dbc.postgresql.codec.Json?,
        val ownCondition: io.r2dbc.postgresql.codec.Json?,
    )
}
