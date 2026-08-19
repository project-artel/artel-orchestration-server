package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * 실측 WordVenture 캡처(`wv-editor-latest.json`, schema 6)로 스폰 귀속 규칙을 못 박는다.
 *
 * 숫자는 전부 이 문서에서 센 값이다. 여기 있는 111 이 전투 씬의 대부분이라, 이 테스트가 깨진다는 것은
 * 조인이 근거의 3분의 1을 조용히 버리기 시작했다는 뜻이다.
 *
 * Spring 도 DB 도 쓰지 않는다 — [SpawnAttribution] 은 순수 계산이고, 컨텍스트를 세우면 계산이 아니라
 * 배선을 검증하게 된다. 문서 파싱은 한 번만 한다(1.4MB).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SpawnAttributionTest {

    private lateinit var document: EvidenceDocumentModel

    @BeforeAll
    fun parseOnce() {
        val json = File("src/test/resources/contentmap/wv-editor-latest.json").readText()
        document = EvidenceParser(ObjectMapper()).parse(json)
    }

    /**
     * 배치 조회를 문서 자체에서 만든다.
     *
     * `PlacementIndex` 를 부르지 않는 것이 요점이다 — [SpawnAttribution] 이 함수 타입을 받는 이유가
     * 색인 없이도 이 규칙만 검증할 수 있게 하는 것이므로, 여기서 색인을 끌어오면 그 분리가 무의미해진다.
     */
    private fun placementLookup(): (String) -> List<ScenePlacement> {
        val byType = mutableMapOf<String, MutableList<ScenePlacement>>()
        document.allObjects.forEach { obj ->
            obj.components.forEach { component ->
                byType.getOrPut(component.type) { mutableListOf() }
                    .add(ScenePlacement(obj.scene, obj.path, obj.selector, obj.label))
            }
        }
        return { type -> byType[type].orEmpty() }
    }

    private fun attribution() = SpawnAttribution(document, placementLookup())

    /**
     * 귀속의 총량. 프리팹 위에만 사는 타입은 구조적으로 배선 0건이라, 이 10개를 놓치면 근거 111건이
     * 어디에도 붙지 않고 사라진다 — 실패가 조용해서 총량으로 잡아야 한다.
     */
    @Test
    fun `createdBy 가 찬 unplaced 타입 10개에 근거 111건이 귀속된다`() {
        val attributed = attribution().attribute()

        assertThat(attributed).hasSize(10)
        assertThat(attributed.keys.sumOf { document.unplaced.getValue(it).evidence.size }).isEqualTo(111)
    }

    /**
     * 정밀한 길 — `refs[].carries`. 실측 70건 중 3건만 `carries` 가 차 있고, 그 셋이 이 길의 전부다.
     * 문서가 "이 참조가 이 타입을 싣는다"고 말했을 때만 씬 **경로**까지 단정할 수 있다.
     */
    @Test
    fun `carries 가 Cards Card 를 TurnBattleScene 의 CardSystem CardManager 로 정확히 놓는다`() {
        val origin = attribution().attribute().getValue("Cards.Card").single { it.scene == "TurnBattleScene" }

        assertThat(origin.field).isEqualTo("Cards.CardManager.cardPrefab")
        assertThat(origin.scenePath).isEqualTo("CardSystem/CardManager")
        assertThat(origin.ambiguous).isFalse()
    }

    /**
     * 유도된 길 — 소유 타입의 배치에서 씬만 가져온다.
     *
     * `scenePath` 가 null 이어야 한다. 여기에 만든 쪽(`Manager`)의 경로가 실리면 테스트 케이스가
     * 적을 만드는 오브젝트를 눌러 놓고 적을 확인했다고 말하게 된다.
     */
    @Test
    fun `carries 가 없으면 소유 타입의 배치에서 씬만 가져오고 씬 경로는 비운다`() {
        val origins = attribution().attribute().getValue("Combat.Enemies.Enemy")

        assertThat(origins).hasSize(1)
        val origin = origins.single()
        assertThat(origin.scene).isEqualTo("TurnBattleScene")
        assertThat(origin.field).isEqualTo("Combat.Enemies.EnemyPoolController.enemyDataContainer")
        assertThat(origin.scenePath).isNull()
    }

    /**
     * 모호는 **한 씬 안에서만** 성립한다.
     *
     * `Cards.Card` 의 `createdBy` 3건은 한 씬에 몰려 있지 않다 — `GameClearController` 의 `magicCard` ·
     * `spellCard` 둘이 GameClearScene 에서 같은 프리팹 목록을 실어 거기서만 못 정하고, TurnBattleScene 은
     * 후보가 하나라 그대로 정해진다. 씬을 가르지 않고 "후보 3개니까 모호"로 처리하면 멀쩡한
     * TurnBattleScene 귀속까지 같이 버린다.
     */
    @Test
    fun `한 씬에 후보가 둘이면 field 를 비우고 후보를 남기되 다른 씬의 귀속은 살린다`() {
        val origins = attribution().attribute().getValue("Cards.Card")

        val gameClear = origins.single { it.scene == "GameClearScene" }
        assertThat(gameClear.ambiguous).isTrue()
        assertThat(gameClear.field).isNull()
        assertThat(gameClear.scenePath).isNull()
        assertThat(gameClear.ambiguousCandidates).containsExactly(
            "Scenes.GameClearController.magicCard",
            "Scenes.GameClearController.spellCard",
        )
        assertThat(origins.single { it.scene == "TurnBattleScene" }.ambiguous).isFalse()
    }

    /**
     * 같은 프리팹을 공유하는 세 타입이 똑같이 갈린다. 셋 다 `carries` 목록 하나에서 나왔으므로
     * 하나만 다르게 갈리면 그건 규칙이 아니라 우연이다.
     */
    @Test
    fun `카드 프리팹을 공유하는 세 타입이 모두 GameClearScene 에서만 모호하다`() {
        val attributed = attribution().attribute()

        listOf("Cards.Card", "Cards.Order", "Combat.UI.DraggableCard").forEach { type ->
            assertThat(attributed.getValue(type).filter { it.ambiguous }.map { it.scene })
                .describedAs(type)
                .containsExactly("GameClearScene")
        }
    }

    /**
     * 배치를 못 찾은 소유 타입은 자리를 만들지 않는다. 씬을 모르는 채 귀속하면 근거가 아무 씬에나 붙는다.
     */
    @Test
    fun `배치 조회가 비면 유도된 길로는 아무것도 귀속되지 않는다`() {
        val attributed = SpawnAttribution(document) { emptyList() }.attribute()

        // carries 로 잡히는 세 타입만 살아남는다 — 그 길은 배치 조회를 쓰지 않는다.
        assertThat(attributed.keys).containsExactlyInAnyOrder("Cards.Card", "Cards.Order", "Combat.UI.DraggableCard")
    }

    /**
     * 죽은 코드 **후보**는 정확히 3개다.
     *
     * `createdBy` 가 빈 것은 4개지만 `Cards.Util` 은 `calledBy` 가 `Cards.CardManager` 를 들고 있어
     * 빠진다. 두 축을 다 보지 않으면 살아 있는 타입이 후보에 오른다. 그리고 이 목록은 판정이 아니다 —
     * `Combat.Spells.SpellObj` 는 devbuild 캡처에서 살아 있다.
     */
    @Test
    fun `createdBy 와 calledBy 가 둘 다 빈 3개만 죽은 코드 후보다`() {
        assertThat(attribution().deadCodeCandidates()).containsExactly(
            "Scenes.Test.RemoteControlPoCController",
            "Scenes.Test.TrackingTest",
            "Combat.Spells.SpellObj",
        )
    }

    /**
     * 죽은 코드 후보와 귀속 대상은 겹치지 않는다. 겹치면 같은 타입을 두고 "주소가 없다"와
     * "아무도 안 쓴다"를 동시에 말하는 것이라 둘 중 하나가 거짓이다.
     */
    @Test
    fun `죽은 코드 후보는 귀속된 타입과 겹치지 않는다`() {
        val attribution = attribution()

        assertThat(attribution.deadCodeCandidates()).doesNotContainAnyElementsOf(attribution.attribute().keys)
    }
}
