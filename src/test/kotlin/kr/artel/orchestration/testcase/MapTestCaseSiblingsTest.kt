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

    /**
     * **이 기능이 거친 경로 위의 호출자에게서만 빌린다**(ARTEL-1209).
     *
     * 실측(1.4.1): `서버에 저장` 버튼 → `ShowStatus()` → `Localization.Translate()`. `Translate()` 는 대사 창도 불러서,
     * 경로를 안 보면 버튼이 대사 창 이름표 결과를 빌려 갔다.
     */
    @Test
    fun `거친 경로 밖의 호출자에게서는 빌리지 않는다`() {
        val edges = listOf(
            edge("ShowStatus", 1), // Translate — 버튼 경로
            edge("ShowStatus", 2), // 상태 문구 갱신
            edge("SetSpeaker", 1), // Translate — 대사 창 경로
            edge("SetSpeaker", 3), // 이름표 표시
        )
        val path = setOf(
            "System.Void Map.MapMove::ConfirmDownload()",
            "System.Void Map.MapMove::ShowStatus(System.String)",
            "System.String Map.MapMove::Translate(System.String)",
        ).map(MapTestCaseSiblings::pathMethod).toSet()

        assertThat(MapTestCaseSiblings.Index(edges).of(1, path).map { it.capabilityId }).containsExactly(2L)
        // 경로를 모르면 예전처럼 다 본다.
        assertThat(MapTestCaseSiblings.Index(edges).of(1).map { it.capabilityId }).containsExactly(2L, 3L)
    }

    @Test
    fun `경로의 칸을 호출 관계와 같은 이름으로 푼다`() {
        assertThat(MapTestCaseSiblings.pathMethod("System.Void Scenes.AccountPanel::ShowStatus(System.String)"))
            .isEqualTo(MapTestCaseSiblings.sourceMethod("Assembly-CSharp|Scenes.AccountPanel|ShowStatus|System.Void(System.String)"))
        assertThat(MapTestCaseSiblings.pathMethod("System.Boolean Story.StoryController/<StoryTelling>d__8::MoveNext()"))
            .isEqualTo(MapTestCaseSiblings.sourceMethod("Assembly-CSharp|Story.StoryController/<StoryTelling>d__8|MoveNext|System.Boolean()"))
    }

    /** 호출자가 생명주기뿐이면 빌릴 곳이 없다 — 없는 것을 지어내지 않는다. */
    @Test
    fun `호출자가 생명주기뿐이면 아무것도 안 낸다`() {
        val edges = listOf(edge("Start", 1), edge("Start", 2), edge("Awake", 3))

        assertThat(MapTestCaseSiblings.of(1, edges)).isEmpty()
    }

    /**
     * 미리 묶어 둔 색인(ARTEL-1187)이 예전처럼 **호출 관계의 원래 순서**로 내고, 같은 기능은 처음 것 하나만 남긴다.
     * 호출자가 둘인 기능은 두 호출자 아래 형제를 모두 빌려 온다. 코루틴 상태 머신 이름은 원래 메서드로 묶인다.
     */
    @Test
    fun `색인은 원래 순서와 첫 번째만 남기기를 지킨다`() {
        val edges = listOf(
            edge("Fire", 2),
            edge("Talk", 1),
            edge("Fire", 1),
            edge("Talk", 3),
            edge("Fire", 3),
            edge("Fire", 4),
            ContentMapCallEdge(
                callerMethodId = "Assembly-CSharp|Map.MapMove/<Talk>d__8|MoveNext|System.Boolean()",
                callerCondition = null,
                capabilityId = 5,
                conditionTree = null,
            ),
        )

        val index = MapTestCaseSiblings.Index(edges)

        assertThat(index.of(1).map { it.capabilityId }).containsExactly(2L, 3L, 4L, 5L)
        assertThat(index.of(1)).isEqualTo(MapTestCaseSiblings.of(1, edges))
        assertThat(index.of(99)).isEmpty()
    }
}
