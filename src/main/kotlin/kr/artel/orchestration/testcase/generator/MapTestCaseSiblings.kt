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
    fun of(capabilityId: Long, edges: List<ContentMapCallEdge>): List<Borrowed> {
        val callers = edges.filter { it.capabilityId == capabilityId }
        if (callers.isEmpty()) return emptyList()
        val callerMethods = callers.map { it.callerMethodId }.toSet()
        return edges
            .filter { it.callerMethodId in callerMethods && it.capabilityId != capabilityId }
            .map { Borrowed(it.capabilityId, it.callerCondition, it.conditionTree) }
            .distinctBy { it.capabilityId }
    }

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
