package kr.artel.orchestration.testcase

import kr.artel.orchestration.contentmap.dto.ContentMapCallEdge
import kr.artel.orchestration.testcase.generator.MapTestCaseSiblings
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **누가 누구의 원인인가**를 못 박는다(ARTEL-680).
 *
 * 이 파일이 있는 이유는 하나다 — 같은 사고가 두 번 났다. 처음에는 진입점을 공유한다고 이어서
 * "`RightArrow` 를 누르면 배경이 바뀐다"가 나왔고, 호출 엣지로 바꾼 뒤에도 **호출자가
 * `Update()` 이면** 같은 것이 나왔다.
 */
class MapTestCaseSiblingsTest {

    private fun edge(caller: String, capabilityId: Long) =
        ContentMapCallEdge(
            callerMethodId = "Assembly-CSharp|Map.MapMove|$caller|System.Void()",
            callerCondition = null,
            capabilityId = capabilityId,
            conditionTree = null,
        )

    /**
     * **생명주기 메서드 아래의 것끼리는 형제일 뿐 인과가 아니다.**
     *
     * 실측(지도 31, Map_scene): `CharacterMove()`(Return 입력)와 `ShowBattle()`(배경 갱신)이
     * 둘 다 `Update()` 아래 있다. 이어 주면 Return 케이스가 배경 갱신을 자기 결과로 들고,
     * 조건 갈래마다 갈려 조작 하나에 케이스 열둘이 매달린다 — 저작이 "첫 스테이지"에
     * 5스테이지 케이스를 고른 원인이다(런 265).
     */
    @Test
    fun `Update 아래의 형제에게서는 결과를 빌려 오지 않는다`() {
        val edges = listOf(
            edge("Update", 9876), // CharacterMove — Return 입력
            edge("Update", 9857), // ShowBattle — 배경 갱신
        )

        assertThat(MapTestCaseSiblings.of(9876, edges)).isEmpty()
    }

    /**
     * **사람이 부른 것 아래에서는 그대로 빌려 온다.** 이 파일이 처음 생긴 이유(ARTEL-554)이고,
     * 코루틴·상태 머신에서 입력 갈래와 결과 갈래가 갈리는 자리다.
     */
    @Test
    fun `보통 메서드 아래의 형제에게서는 결과를 빌려 온다`() {
        val edges = listOf(
            edge("StoryTelling", 100), // IsAdvanceKeyDown — 입력만
            edge("StoryTelling", 200), // LoadMapScene — 결과만
        )

        assertThat(MapTestCaseSiblings.of(100, edges).map { it.capabilityId }).containsExactly(200L)
    }

    /** 호출자가 생명주기뿐이면 빌릴 곳이 없다 — 없는 것을 지어내지 않는다. */
    @Test
    fun `호출자가 생명주기뿐이면 아무것도 안 낸다`() {
        val edges = listOf(edge("Start", 1), edge("Start", 2), edge("Awake", 3))

        assertThat(MapTestCaseSiblings.of(1, edges)).isEmpty()
    }
}
