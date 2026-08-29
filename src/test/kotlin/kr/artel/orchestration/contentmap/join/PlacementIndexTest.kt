package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 자리 인덱스가 실측 문서에서 무엇을 세는지 못박는다.
 *
 * 자리는 후보 행의 씬 축을 정한다 — 여기서 하나가 새거나 겹치면 기능이 없는 씬에 나타나거나 같은
 * 기능이 두 벌이 된다. 그래서 "몇 개"와 "어디"를 문서 실측값으로 고정한다. 스프링 컨텍스트는 쓰지
 * 않는다. 인덱스는 파싱된 모델만 먹는 순수 계산이고, 컨텍스트를 띄우면 이 계산이 DB·설정과 함께
 * 깨지고 함께 고쳐야 하는 것이 된다.
 */
class PlacementIndexTest {

    companion object {
        /** 1.4 MB 문서라 클래스당 한 번만 읽는다. */
        private val document: EvidenceDocumentModel = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())

        private val index = PlacementIndex.build(document, PersistentSceneAttribution(document))
    }

    /**
     * 씬 오브젝트 27개가 컴포넌트 35개를 이고 있다는 것이 이 인덱스의 입력 크기다. 문서를 다시 구웠을
     * 때 이 수가 움직이면 아래 자리 단언들의 전제가 먼저 바뀐 것이다.
     */
    @Test
    fun `실측 문서는 씬 오브젝트 27개와 컴포넌트 35개를 담는다`() {
        assertThat(document.allObjects).hasSize(27)
        assertThat(document.allObjects.sumOf { it.components.size }).isEqualTo(35)
    }

    /**
     * 자리 33개는 컴포넌트 35개가 아니다. 같은 오브젝트에 같은 타입이 두 번 붙은 2건을 접은 수다 —
     * 접지 않으면 그 타입이 두 군데 놓인 것으로 읽혀 후보가 두 벌이 된다.
     */
    @Test
    fun `자리는 오브젝트와 타입 단위라 컴포넌트 35개가 33자리가 된다`() {
        assertThat(index.placementCount).isEqualTo(33)
        assertThat(index.placedTypes).hasSize(22)
    }

    /**
     * 오브젝트 이름과 타입 이름이 다르다는 것이 이 인덱스가 필요한 이유다 —
     * `Scenes.TitleSceneManager` 는 `TitleSceneController` 라는 오브젝트 위에 산다. 이름으로 자리를
     * 짐작하면 틀린다.
     */
    @Test
    fun `TitleSceneManager 는 TitleScene 의 TitleSceneController 에 놓여 있다`() {
        assertThat(index.placementsOf("Scenes.TitleSceneManager")).containsExactly(
            ScenePlacement(
                scene = "TitleScene",
                path = "TitleSceneController",
                selector = "TitleSceneController[4]",
                label = null,
            ),
        )
    }

    /**
     * 실측에서 `CombineZone/Zone1` 과 `Zone2` 는 각각 `Combat.UI.DropZone` 을 둘씩 인다. 자리는
     * 오브젝트 단위 값이라 컴포넌트마다 담으면 완전히 같은 값이 두 번 들어간다.
     */
    @Test
    fun `한 오브젝트에 같은 타입이 두 번 붙어도 자리는 하나다`() {
        val placements = index.placementsOf("Combat.UI.DropZone")

        assertThat(placements).hasSize(2)
        assertThat(placements.map { it.path })
            .containsExactly("CombineSystem/CombineZone/Zone1", "CombineSystem/CombineZone/Zone2")
    }

    /**
     * 씬은 후보의 축이라 중복이 곧 행 중복이다. 실측 `UnityEngine.UI.Button` 은 자리 7개가 씬 3개에
     * 몰려 있고, 씬은 문서에 처음 나온 순서로 한 번씩만 나와야 한다(재적재 결정론).
     */
    @Test
    fun `여러 자리가 한 씬에 몰려도 씬은 한 번씩만 낸다`() {
        assertThat(index.placementsOf("UnityEngine.UI.Button")).hasSize(7)
        assertThat(index.scenesOf("UnityEngine.UI.Button"))
            .containsExactly("TitleScene", "Map_scene", "TurnBattleScene")
    }

    /**
     * `unplaced` 의 타입은 프리팹 위에만 살아 씬 오브젝트의 컴포넌트 목록에 없다. 여기서 자리를
     * 지어내면 스폰 귀속이 해야 할 판정을 인덱스가 몰래 대신하게 된다.
     */
    @Test
    fun `프리팹 위에만 사는 타입은 자리가 없다`() {
        assertThat(document.unplaced).containsKey("Combat.Enemies.Enemy")
        assertThat(index.placementsOf("Combat.Enemies.Enemy")).isEmpty()
        assertThat(index.scenesOf("Combat.Enemies.Enemy")).isEmpty()
    }

    /**
     * 자리는 경로만이 아니라 조준 경로와 라벨까지 들고 다녀야 한다 — 뒤 단계가 오브젝트를 다시 찾아가
     * 라벨을 캐면 같은 순회가 후보 수만큼 반복된다.
     */
    @Test
    fun `자리는 조준 경로와 라벨을 함께 든다`() {
        assertThat(index.placementsOf("UnityEngine.UI.Button")).contains(
            ScenePlacement(
                scene = "TurnBattleScene",
                path = "CombineSystem/CombineZone/Button",
                selector = "CombineSystem[7]/CombineZone[1]/Button[2]",
                label = "Combine",
            ),
        )
    }
}
