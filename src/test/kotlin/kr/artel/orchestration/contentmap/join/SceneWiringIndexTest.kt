package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.io.File

/**
 * 배선 조인의 세 길이 **각각 값을 한다**는 것을 실측 문서로 못박는다.
 *
 * 이 파일이 지키는 것은 개수가 아니라 개수의 이유다. 길 하나를 지우면 어느 배선이 사라지는지를 길마다
 * 따로 단언해, 나중에 "ENTRY 하나면 충분해 보인다"는 판단으로 길이 지워지면 여기서 먼저 깨지게 한다.
 * 실측: 씬이 든 배선 7건, 합집합 7쌍, ENTRY 6쌍, ARRIVAL 2쌍, HANDLE 1쌍.
 *
 * 스프링 컨텍스트는 쓰지 않는다 — 조인은 파싱된 모델만 먹는 순수 계산이다.
 */
class SceneWiringIndexTest {

    companion object {
        /** 1.4 MB 문서라 클래스당 한 번만 읽는다. */
        private val document: EvidenceDocumentModel = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-latest.json").readText())

        private val index = SceneWiringIndex.build(document)

        private val MAP_SCENE_BUTTON = ScenePlacement(
            scene = "TitleScene",
            path = "Canvas/MapSceneButton",
            selector = "Canvas[2]/MapSceneButton[1]",
            label = null,
        )
        private val CONTINUE_BUTTON = ScenePlacement(
            scene = "TitleScene",
            path = "Canvas/continue",
            selector = "Canvas[2]/continue[2]",
            label = null,
        )
        private val EXIT_BUTTON = ScenePlacement(
            scene = "TitleScene",
            path = "Canvas/ExitButton",
            selector = "Canvas[2]/ExitButton[3]",
            label = null,
        )
        private val BACK_BUTTON = ScenePlacement(
            scene = "Map_scene",
            path = "Canvas/Button (Legacy)",
            selector = "Canvas[7]/Button (Legacy)[0]",
            label = null,
        )
        private val COMBINE_ZONE_BUTTON = ScenePlacement(
            scene = "TurnBattleScene",
            path = "CombineSystem/CombineZone/Button",
            selector = "CombineSystem[7]/CombineZone[1]/Button[2]",
            label = "Combine",
        )

        private val LOAD_STORY_SCENE = "Scenes.TitleSceneManager" to "LoadStoryScene"
        private val COMBINE_ZONE_CLICK = "Combat.UI.CombineZone" to "OnButtonClick"
    }

    /**
     * 씬 절반이 문서 전체의 실배선 전부다(`triggerKind: unity-event` 레코드 112건과 헷갈리면 안 된다).
     * 세 길을 다 걸었을 때 그 7건이 하나도 빠지지 않고 코드 절반에 닿는다는 것이 이 조인의 목표다.
     */
    @Test
    fun `세 길을 다 걸어야 배선 7건이 모두 걸린다`() {
        assertThat(index.wiredControlCount).isEqualTo(7)
        assertThat(index.matchedPairs()).containsExactlyInAnyOrder(
            "Scenes.TitleSceneManager" to "InitPlayerData",
            "Scenes.TitleSceneManager" to "LoadStoryScene",
            "Scenes.TitleSceneManager" to "QuitGame",
            "Cards.BackButton" to "BackToMain",
            "Battle.Turns.TurnBattleSystem" to "TurnEndButton",
            "Combat.UI.CombineButton" to "OnButtonClick",
            "Combat.UI.CombineZone" to "OnButtonClick",
        )
    }

    /**
     * 조인의 가장 단순한 규칙 — "레코드의 진입점이 곧 컨트롤이 부르는 메서드" — 은 7건 중 5건에서
     * 멈춘다. 나머지 둘은 진입점에 그 이름이 아예 없다. 이 수가 [WiringPath.ENTRY] 에 둘째 절이 붙어
     * 있는 이유이므로, 문서가 바뀌어 5가 6이 되면 규칙을 다시 재야 한다.
     */
    @Test
    fun `entryId 비교만으로는 배선 5건에서 멈춘다`() {
        val wired = index.matchedPairs()
        val byEntryId = document.types.values.flatten()
            .mapNotNull { stableIdTarget(it.entryId) }
            .filter { it in wired }
            .toSet()

        assertThat(byEntryId).hasSize(5)
        assertThat(byEntryId).doesNotContain(LOAD_STORY_SCENE, COMBINE_ZONE_CLICK)
    }

    /**
     * `Canvas/continue` 가 부르는 `LoadStoryScene` 은 어느 레코드의 진입점도 아니다 — 그 레코드들의
     * 진입점은 `InitPlayerData()` 이고 `LoadStoryScene` 은 `methodId` 쪽에 있다. `owner` 와 `methodId`
     * 의 메서드 이름까지 봐야 걸리고, 그 절 덕에 [WiringPath.ENTRY] 가 6쌍을 덮는다.
     */
    @Test
    fun `ENTRY 는 owner 와 methodId 까지 봐야 LoadStoryScene 을 건진다`() {
        assertThat(index.matchedPairs(WiringPath.ENTRY)).hasSize(6)
        assertThat(index.matchedPairs(WiringPath.ENTRY)).contains(LOAD_STORY_SCENE)
    }

    /**
     * `Combat.UI.CombineZone::OnButtonClick` 은 코드가 런타임에 `AddListener` 로 매단 것이라
     * 인스펙터 쪽 진입점이 없다 — 이 길을 지우면 합집합이 7에서 6으로 떨어진다.
     */
    @Test
    fun `HANDLE 을 빼면 CombineZone 배선이 통째로 사라진다`() {
        assertThat(index.matchedPairs(WiringPath.HANDLE)).containsExactly(COMBINE_ZONE_CLICK)

        val withoutHandle = index.matchedPairs(WiringPath.ENTRY) + index.matchedPairs(WiringPath.ARRIVAL)
        assertThat(withoutHandle).hasSize(6)
        assertThat(withoutHandle).doesNotContain(COMBINE_ZONE_CLICK)
    }

    /**
     * ARRIVAL 이 여는 것은 **새 쌍이 아니라 새 레코드**다. `Core.SaveLoadController` 의 세이브 레코드는
     * 진입점이 `InitPlayerData()` 라 `Canvas/MapSceneButton` 에는 바로 닿지만, 같은 사실에 이르는 둘째
     * 길(`alsoReachedBy` 의 `LoadStoryScene`)을 펴지 않으면 `Canvas/continue` 를 눌렀을 때 세이브가
     * 일어난다는 사실이 사라진다. 실측에서 이 길로만 생기는 배선이 3건이다.
     */
    @Test
    fun `ARRIVAL 을 빼면 SaveLoadController 가 Canvas continue 를 잃는다`() {
        val saveOnInit = document.types.getValue("Core.SaveLoadController")
            .first { it.entry.contains("InitPlayerData") && it.source.contains("SavePlayData") }

        assertThat(index.bindingsFor(saveOnInit)).containsExactly(
            ControlBinding(MAP_SCENE_BUTTON, event = "m_OnClick", via = WiringPath.ENTRY),
            ControlBinding(CONTINUE_BUTTON, event = "m_OnClick", via = WiringPath.ARRIVAL),
        )
    }

    /**
     * 쌍만 세면 ARRIVAL 이 여는 2쌍은 ENTRY 가 이미 덮는다 — 그래서 "합집합 7"만으로는 이 길이 값을
     * 하는지 증명되지 않는다. 위의 레코드 단위 단언이 그 증명이고, 이 테스트는 겹침 자체를 기록해
     * 다음 사람이 같은 착각을 반복하지 않게 한다.
     */
    @Test
    fun `ARRIVAL 이 여는 쌍은 ENTRY 와 겹친다`() {
        val arrival = index.matchedPairs(WiringPath.ARRIVAL)

        assertThat(arrival).containsExactlyInAnyOrder("Cards.BackButton" to "BackToMain", LOAD_STORY_SCENE)
        assertThat(index.matchedPairs(WiringPath.ENTRY)).containsAll(arrival)
    }

    /**
     * HANDLE 로 걸린 배선의 이벤트 이름은 인스펙터의 `m_OnClick` 이 아니라 코드가 매단 채널이다.
     * 컨트롤 쪽 이벤트를 그대로 쓰면 "버튼의 onClick" 이라는, 문서가 하지 않은 말이 된다.
     */
    @Test
    fun `HANDLE 로 걸린 배선의 이벤트는 handles 의 채널이다`() {
        val addCard = document.types.getValue("Combat.UI.CombineZone")
            .first { it.source.contains("AddCard") }

        assertThat(index.bindingsFor(addCard)).containsExactly(
            ControlBinding(
                COMBINE_ZONE_BUTTON,
                event = "CombineZone.activateButton.onClick",
                via = WiringPath.HANDLE,
            ),
        )
    }

    /**
     * `LoadStoryScene` 레코드는 `Canvas/continue` 에 ENTRY(둘째 절)로도, ARRIVAL 로도 닿는다. 배선을
     * 길마다 한 줄씩 내면 세는 쪽이 조작이 둘이라고 읽으므로, 가장 곧은 길 하나만 남긴다.
     */
    @Test
    fun `한 컨트롤이 두 길로 걸려도 배선은 하나다`() {
        val loadStoryScene = document.types.getValue("Scenes.TitleSceneManager")
            .first { it.source.contains("LoadStoryScene") }

        assertThat(index.bindingsFor(loadStoryScene)).containsExactly(
            ControlBinding(MAP_SCENE_BUTTON, event = "m_OnClick", via = WiringPath.ENTRY),
            ControlBinding(CONTINUE_BUTTON, event = "m_OnClick", via = WiringPath.ENTRY),
        )
    }

    /**
     * 배선이 없는 것은 실패가 아니라 문서가 말하는 사실이다(실측 318건 중 대부분). 여기서 무엇이든
     * 지어내면 눌러 볼 수 없는 기능이 명세에 실린다.
     */
    @Test
    fun `배선에 닿지 않는 레코드는 빈 목록을 돌려준다`() {
        val notWired = document.types.getValue("Combat.Enemies.BattleWaveController").first()

        assertThat(index.bindingsFor(notWired)).isEmpty()
    }

    /**
     * 배선이 어긋나면 이 키 파싱이 첫 용의자다. 특히 컴파일러가 만든 중첩 타입을 접지 않으면
     * 코루틴을 쓰는 레코드는 어떤 컨트롤에도 닿지 못한다(실측 methodId 318건 중 31건이 그 모양이다).
     */
    @Test
    fun `안정 키에서 바깥 타입과 메서드 이름을 뽑는다`() {
        assertThat(stableIdTarget("Assembly-CSharp|Scenes.TitleSceneManager|LoadStoryScene|System.Void()"))
            .isEqualTo("Scenes.TitleSceneManager" to "LoadStoryScene")
        val nested = "Assembly-CSharp|Battle.Turns.TurnBattleSystem/<EnemyTurnCounter>d__13|MoveNext|System.Boolean()"
        assertThat(stableIdTarget(nested)).isEqualTo("Battle.Turns.TurnBattleSystem" to "MoveNext")
        // 람다 처리기의 메서드 이름은 그대로 둔다 — 그 이름 자체가 handlerId 의 키다.
        assertThat(stableIdTarget("Assembly-CSharp|Story.StoryController|<StoryTelling>b__8_0|System.Boolean()"))
            .isEqualTo("Story.StoryController" to "<StoryTelling>b__8_0")
    }

    /**
     * 형식이 아닌 키를 빈 문자열로 채우면 빈 타입끼리 서로 맞아 없는 배선이 생긴다. 못 읽으면
     * 안 걸리는 것이 맞다.
     */
    @Test
    fun `형식이 아닌 안정 키는 맞추지 않는다`() {
        assertThat(stableIdTarget("Scenes.TitleSceneManager::LoadStoryScene")).isNull()
        assertThat(stableIdTarget("Assembly-CSharp|Scenes.TitleSceneManager")).isNull()
        assertThat(stableIdTarget("Assembly-CSharp||LoadStoryScene|System.Void()")).isNull()
    }
}
