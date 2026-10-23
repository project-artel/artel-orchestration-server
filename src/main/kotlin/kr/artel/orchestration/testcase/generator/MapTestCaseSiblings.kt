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
     * 지도 하나치 호출 관계를 **한 번만** 풀어 둔 것(ARTEL-1187).
     *
     * 예전에는 기능 행마다 [of] 가 호출 관계 전부를 처음부터 훑고, 훑을 때마다 [sourceMethod] 로 메서드 이름을
     * 다시 풀었다. 실측 project 1 · 1.4.0 은 기능 7,863개에 호출 관계 404,131개라, TC 생성이 8분 넘게 이
     * 계산에 묶였다. 여기서는 이름을 관계마다 한 번 풀고, 기능별 · 메서드별로 묶어 둔다.
     *
     * 결과는 [of] 와 같다 — 순서는 호출 관계의 원래 순서이고, 같은 기능은 처음 나온 것 하나만 남긴다.
     */
    class Index(private val edges: List<ContentMapCallEdge>) {
        private val sources: List<String> = edges.map { sourceMethod(it.callerMethodId) }
        private val byCapability: Map<Long, List<Int>> = edges.indices.groupBy { edges[it].capabilityId }
        private val bySource: Map<String, List<Int>> = edges.indices.groupBy { sources[it] }

        /**
         * @param path 이 기능이 실제로 거친 메서드들([pathMethod] 꼴). 주면 **그 경로 위의 호출자만** 공통 호출자로 본다.
         */
        fun of(capabilityId: Long, path: Set<String>? = null): List<Borrowed> {
            val callers = byCapability[capabilityId] ?: return emptyList()
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
            //
            // **이 기능이 거친 경로 위의 호출자만 본다**(ARTEL-1209). 여러 곳에서 불리는 함수는 호출자도 여럿인데, 그중
            // 이 조작이 지나온 것은 하나다. 다 보면 남의 호출자가 부른 결과까지 빌려 온다 — 실측(1.4.1): `서버에 저장`
            // 버튼이 부른 `Localization.Translate()` 가, 대사 창이 같은 함수를 부른다는 이유로 대사 창 이름표 결과를
            // 빌려 갔다(이름표만 확인하는 TC 141 건). 경로는 근거 문서가 기능마다 싣는 `callPath` 다.
            val callerMethods = callers.map { sources[it] }
                .filterNot { it.substringAfterLast('|') in LIFECYCLE }
                .filter { path.isNullOrEmpty() || it in path }
                .toSet()
            if (callerMethods.isEmpty()) return emptyList()
            return callerMethods.flatMap { bySource[it].orEmpty() }
                .sorted()
                .filter { edges[it].capabilityId != capabilityId }
                .map { Borrowed(edges[it].capabilityId, edges[it].callerCondition, edges[it].conditionTree) }
                .distinctBy { it.capabilityId }
        }
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

    /**
     * 호출 경로의 한 칸(`System.Void Scenes.AccountPanel::ShowStatus(System.String)`)을 [sourceMethod] 와 같은 꼴로.
     * 컴파일러가 만든 이름도 같은 방법으로 되돌린다.
     */
    fun pathMethod(signature: String): String {
        val head = signature.substringBefore('(')
        val type = head.substringBeforeLast("::").substringAfterLast(' ')
        val name = head.substringAfterLast("::")
        val generated = GENERATED.find(type)?.groupValues?.get(1) ?: GENERATED.find(name)?.groupValues?.get(1)
        return type.substringBefore('/') + "|" + (generated ?: name)
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
