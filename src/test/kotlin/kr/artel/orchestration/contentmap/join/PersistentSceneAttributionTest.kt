package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceBuild
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.SceneComponent
import kr.artel.orchestration.contentmap.evidence.SceneObject
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import java.io.File

/**
 * `DontDestroyOnLoad` 에 있는 오브젝트가 실제 실행 `scene` 으로 옮겨 앉는지 본다(ARTEL-460).
 *
 * 실측 문서는 `wv-editor-play-schema7.json` — `persistent-objects-v1` 을 약속하는 유일한 픽스처이고,
 * `DontDestroyOnLoad` 오브젝트 넷을 담는다. 그 넷이 이 규칙이 실제로 만나는 모양의 전부이므로 앞쪽 테스트는
 * 전부 그 문서로만 검증한다.
 *
 * 뒤쪽 몇 개는 손으로 만든 작은 문서를 쓴다. 실측 문서에 없는 세 경우(같은 오브젝트의 컴포넌트가 진짜
 * `scene` 에도 놓인 경우 · 활성 `scene` 이름 조건 · `scene` 이 둘로 갈리는 경우)를 덮기 위해서이고, **실측 문서에
 * 있는 경우를 손으로 만든 것으로 대신하지는 않는다.** 문서의 진짜 모양을 상상으로 대체하는 것이 이 단계에서 가장 자주
 * 틀리는 가정이라는 판단은 `ConditionBranchesTest` 가 이미 적어 둔 것과 같다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistentSceneAttributionTest {

    private lateinit var document: EvidenceDocumentModel
    private lateinit var attribution: PersistentSceneAttribution
    private lateinit var join: EvidenceJoin

    @BeforeAll
    fun parseOnce() {
        document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-play-schema7.json").readText())
        attribution = PersistentSceneAttribution(document)
        join = EvidenceJoin(document)
    }

    /**
     * **이 이슈의 완료 조건 하나.** `scene` 이름이 아닌 항목이 후보에 남지 않는다.
     *
     * 후보의 `scene` 이 곧 `scene` 행의 이름이 되므로(적재기가 `model.scenes + 후보의 `scene` 을 upsert 한다),
     * 여기가 통과하면 지도에도 그 이름이 앉지 않는다. 문서가 `DontDestroyOnLoad` 오브젝트 넷을 들고 있는데도
     * 통과해야 뜻이 있어, 그 전제를 함께 못 박는다.
     */
    @Test
    fun `scene 이 아닌 이름은 후보에 남지 않는다`() {
        assertThat(document.persistentObjects.map { it.path })
            .containsExactly("SaveLoadController", "TutorialController", "TutorialController/ChatWindow", "GameObject")
        assertThat(document.persistentObjects.map { it.scene }.distinct()).containsExactly("DontDestroyOnLoad")

        assertThat(join.candidates().map { it.scene }.distinct()).isSubsetOf(document.scenes)
    }

    /**
     * 이슈가 든 그 예시 — 튜토리얼은 `Map_scene` 에서 돈다.
     *
     * `TutorialController.Start()` 는 `MapMove.StagePosition <= 0` 일 때 창을 띄운다. `Map.MapMove` 는
     * 문서에서 `Map_scene` **한 곳에만** 놓였으므로 그 조건이 성립할 수 있는 자리도 거기뿐이다. 같은
     * 게임의 손으로 쓴 옛 산출물이 같은 튜토리얼 행동을 `Map_scene` 에 실은 것과 답이 같다.
     *
     * 자식 오브젝트(`TutorialController/ChatWindow`)도 함께 간다. `DontDestroyOnLoad` 는 루트를
     * 자식째 옮기므로, 자식만 따로 판정하면 한 오브젝트가 두 화면에 나뉘어 앉는다.
     */
    @Test
    fun `튜토리얼은 MapMove 를 읽어 Map_scene 으로 간다`() {
        val tutorial = join.candidates().filter { it.record.owner.startsWith("Tutorial.") }

        assertThat(tutorial).isNotEmpty
        assertThat(tutorial.map { it.scene }.distinct()).containsExactly("Map_scene")
        assertThat(tutorial.map { it.record.owner }.distinct())
            .containsExactlyInAnyOrder("Tutorial.TutorialController", "Tutorial.TutorialChatWindow")
    }

    /**
     * **완료 조건 둘** — 옮긴 기능마다 무엇을 읽고 그 `scene` 에 실었는지가 값으로 남는다.
     *
     * 사슬 세 단계가 그대로 `capability_proof` 세 행이 된다. 조용한 재귀속은 안 하느니만 못하다 —
     * 언젠가 누군가 이 판정이 옳았는지 확인해야 하고, 그때 근거가 없으면 지도 전체를 의심하게 된다.
     *
     * 같은 조건을 읽는 record 가 열 개라도 사슬은 한 벌이다. 접지 않으면 읽는 사람이 근거가 열
     * 개라고 오해한다.
     */
    @Test
    fun `옮긴 기능은 무엇을 읽고 그 scene 에 실었는지를 사슬로 든다`() {
        val anchors = join.candidates()
            .first { it.record.owner == "Tutorial.TutorialController" }
            .sceneAnchors

        assertThat(anchors).hasSize(1)
        val anchor = anchors.single()
        assertThat(anchor.scene).isEqualTo("Map_scene")
        assertThat(anchor.rule).isEqualTo(PersistentSceneRule.CONDITION_SUBJECT_PLACED)
        assertThat(anchor.steps).containsExactly(
            AnchorStep("DontDestroyOnLoad/TutorialController", "condition-reads", "MapMove.StagePosition"),
            AnchorStep("MapMove.StagePosition", "owned-by", "Map.MapMove"),
            AnchorStep("Map.MapMove", "placed-in", "Map_scene"),
        )
    }

    /**
     * 여러 `scene` 에 놓인 타입은 anchor 가 되지 않는다.
     *
     * 같은 `TutorialController.Update` 가 `StoryController.IsAdvanceKeyDown()` 도 읽는다.
     * `Story.StoryController` 는 `StoryScene` 과 `EndingScene` 두 곳에 놓여 자기 자리가 하나로
     * 정해지지 않았고, 그런 타입은 남의 자리도 정해 줄 수 없다. 이 조건이 없으면 튜토리얼이 스토리
     * `scene` 까지 세 곳에 복제되고 QA agent 는 없는 창을 찾으러 간다.
     */
    @Test
    fun `두 scene 에 놓인 타입을 읽는 조건은 scene 을 정하지 못한다`() {
        val storyControllerScenes = document.objects
            .filter { obj -> obj.components.any { it.type == "Story.StoryController" } }
            .map { it.scene }
            .distinct()
        val readsStoryController = document.types.getValue("Tutorial.TutorialController")
            .any { record -> record.condition.conjunctiveTests().any { it.left.startsWith("StoryController.") } }

        assertThat(storyControllerScenes).containsExactlyInAnyOrder("StoryScene", "EndingScene")
        assertThat(readsStoryController).isTrue()
        assertThat(join.candidates().filter { it.record.owner.startsWith("Tutorial.") }.map { it.scene }.distinct())
            .doesNotContain("StoryScene", "EndingScene")
    }

    /**
     * **완료 조건 셋** — 정하지 못한 것은 추측이 아니라 gap 이 된다.
     *
     * 실측 `GameObject` 루트(`Combat.Stage.StageDataSingleton`)가 그렇다. 조건이 읽는 것이 자기
     * 자신(`StageDataSingleton.Instance`)뿐이라 밖에서 자리를 말해 주는 것이 없다. 그 근거 4건은
     * 후보가 되지 않고 여기에만 남는다 — 아무 `scene` 에나 붙이면 QA agent 가 없는 컨트롤을 찾으러 가고,
     * 그 실패는 지도가 아니라 게임이 깨진 것처럼 읽힌다.
     *
     * 조인이 깨진 것과는 다른 사건이라 [EvidenceJoin.unaddressedRecords] 는 0 을 유지한다. 한 통에
     * 넣으면 그 수가 0 이 아닌 것이 정상인 날이 생겨 고장 신호가 죽는다.
     */
    @Test
    fun `scene 을 정하지 못한 root 는 후보가 되지 않고 gap 으로 남는다`() {
        assertThat(join.unresolvedPersistentRoots()).containsExactly("GameObject")
        assertThat(join.unattributedPersistentRecords()).isEqualTo(4)
        assertThat(join.unaddressedRecords()).isZero()

        assertThat(join.candidates().map { it.record.owner })
            .doesNotContain("Combat.Stage.StageDataSingleton")
    }

    /**
     * 옮긴 것은 옮긴 만큼만이다. `DontDestroyOnLoad` 오브젝트 넷 중 셋이 앉고 넷째가 gap 이 된다.
     *
     * 옮긴 후보 60건 = `TutorialController` 53 + `TutorialChatWindow` 4 + `SaveLoadController` 3.
     * `SaveLoadController` 는 나머지 record 가 `alsoReachedBy` 로 이미 컨트롤에 닿아 있어 3건만
     * 배치로 떨어진다 — 그쪽은 컨트롤이 주소라 옮길 일이 없다.
     *
     * 이 수가 갑자기 뛰면 진짜 `scene` 에 놓인 오브젝트까지 옮기기 시작한 것이고, 0 이 되면 `DontDestroyOnLoad`
     * 오브젝트가 통째로 사라진 것이다.
     */
    @Test
    fun `DontDestroyOnLoad 오브젝트에서 온 후보 60건만 옮겨진다`() {
        val moved = join.candidates().filter { it.sceneAnchors.isNotEmpty() }

        assertThat(moved).hasSize(60)
        assertThat(moved.groupingBy { it.record.owner }.eachCount()).isEqualTo(
            mapOf(
                "Core.SaveLoadController" to 3,
                "Tutorial.TutorialController" to 53,
                "Tutorial.TutorialChatWindow" to 4,
            )
        )
        assertThat(moved.groupingBy { it.scene }.eachCount())
            .isEqualTo(mapOf("TitleScene" to 3, "Map_scene" to 57))
    }

    /**
     * 옮긴 것은 **유도**다. 근거가 스스로 `verified` 라고 말했어도 결론은 그 위로 올라가지 못한다.
     *
     * `analysis_confidence` 는 사슬의 가장 흐린 단계로 정의된다. `scene` 귀속도 그 사슬의 한 단계이므로
     * 여기서 내려 두지 않으면 "확정된 사실"과 "우리가 옮겨 놓은 사실"이 같은 등급으로 보인다.
     */
    @Test
    fun `유도로 옮긴 기능은 확신도가 exact 로 남지 않는다`() {
        val moved = join.candidates().filter { it.sceneAnchors.isNotEmpty() }

        assertThat(moved.map { it.confidence }.distinct()).doesNotContain(AnalysisConfidence.EXACT)
    }

    /**
     * 한 오브젝트에 붙은 컴포넌트 하나가 진짜 `scene` 에도 놓여 있으면, 같은 오브젝트의 나머지도 그 `scene` 으로
     * 간다. 문서가 "이 타입은 `Map_scene` 에 있다"고 직접 말한 것이라 유도가 아니다.
     *
     * 아래 문서에서 `Core.Saver` 는 `Map_scene` 의 `Systems/Saver` 에도 놓였고 `Core.SaverUi` 는
     * `DontDestroyOnLoad` 에만 있다. `SaverUi` 의 record 는 조건이 없어 혼자서는 `scene` 을 말할 수 없는데,
     * 같은 root 의 `Saver` 가 말해 준다.
     *
     * 실측 픽스처에는 이 모양이 없다 — `DontDestroyOnLoad` 의 타입 넷 중 `objects[]` 에도 있는 것이
     * 없다. 같은 게임의 다른 capture 에는 `Core.SaveLoadController` 와
     * `Combat.Stage.StageDataSingleton` 이 `Map_scene` 에 함께 놓여 있어 이 규칙이 걸린다.
     */
    @Test
    fun `같은 오브젝트의 컴포넌트가 진짜 scene 에 놓였으면 유도 없이 그 scene 으로 간다`() {
        val model = documentOf(
            scenes = listOf("TitleScene", "Map_scene"),
            objects = listOf(sceneObject("Map_scene", "Systems/Saver", "Core.Saver")),
            persistentObjects = listOf(sceneObject("DontDestroyOnLoad", "Saver", "Core.Saver", "Core.SaverUi")),
            records = mapOf("Core.SaverUi" to listOf(record("Core.SaverUi", ConditionNode.Always))),
        )

        val candidates = EvidenceJoin(model).candidates()

        assertThat(candidates.map { it.scene }).containsOnly("Map_scene")
        assertThat(candidates.single().confidence).isEqualTo(AnalysisConfidence.EXACT)
        assertThat(candidates.single().sceneAnchors.single().steps).containsExactly(
            AnchorStep("DontDestroyOnLoad/Saver", "component-type", "Core.Saver"),
            AnchorStep("Core.Saver", "placed-in", "Map_scene"),
        )
    }

    /**
     * 코드가 활성 `scene` 이름을 직접 맞대면 그 `scene` 이다. 유도가 아니라 코드가 말한 것이다.
     *
     * `!=` 는 anchor 가 되지 않는다 — "저 `scene` 이 아니다"는 남은 `scene` 이 여럿이라 자리를 좁히지 못하고,
     * 좁히지 못한 것을 anchor 라 부르면 나머지 `scene` 전부에 기능이 복제된다.
     */
    @Test
    fun `활성 scene 이름 조건은 그 scene 을 직접 가리킨다`() {
        val equals = ConditionNode.Test(ACTIVE_SCENE_NAME, "==", "\"Map_scene\"", context = "static", offset = 3)
        val notEquals = ConditionNode.Test(ACTIVE_SCENE_NAME, "!=", "\"TitleScene\"", context = "static", offset = 3)

        val pinned = EvidenceJoin(persistentOnly(equals)).candidates()
        val unpinned = EvidenceJoin(persistentOnly(notEquals))

        assertThat(pinned.map { it.scene }).containsOnly("Map_scene")
        assertThat(pinned.first().sceneAnchors.single().steps).containsExactly(
            AnchorStep("DontDestroyOnLoad/Runner", "condition-reads", ACTIVE_SCENE_NAME),
            AnchorStep(ACTIVE_SCENE_NAME, "equals", "Map_scene"),
        )
        assertThat(unpinned.candidates()).isEmpty()
        assertThat(unpinned.unresolvedPersistentRoots()).containsExactly("Runner")
    }

    /**
     * 조건이 서로 다른 `scene` 의 타입 둘을 읽으면 두 `scene` 에 다 싣고, `persistent-scene-ambiguous` 를 남긴다.
     *
     * 아래 문서에서 `Core.Alpha` 는 `TitleScene` 에만, `Core.Beta` 는 `Map_scene` 에만 놓였고
     * `Core.Runner` 의 조건이 둘 다 읽는다. 하나를 고르면 고른 쪽이 근거가 없다. 사유 없이 두 줄만
     * 내면 "두 곳에서 다 된다"와 "어디인지 모른다"가 표에서 같은 모양이 되므로 gap 을 함께 적고,
     * `analysis_confidence` 도 `ambiguous` 로 내린다.
     */
    @Test
    fun `scene 을 좁히지 못하면 여러 scene 에 싣고 사유를 남긴다`() {
        val model = documentOf(
            scenes = listOf("TitleScene", "Map_scene"),
            objects = listOf(
                sceneObject("TitleScene", "Systems/Alpha", "Core.Alpha"),
                sceneObject("Map_scene", "Systems/Beta", "Core.Beta"),
            ),
            persistentObjects = listOf(sceneObject("DontDestroyOnLoad", "Runner", "Core.Runner")),
            records = mapOf(
                "Core.Runner" to listOf(
                    record(
                        "Core.Runner",
                        ConditionNode.Group(
                            GroupKind.EVERY,
                            listOf(
                                ConditionNode.Test("Alpha.slot", "==", "0", context = "static", offset = 3),
                                ConditionNode.Test("Beta.slot", "==", "0", context = "static", offset = 7),
                            ),
                        ),
                    )
                )
            ),
        )

        val candidates = EvidenceJoin(model).candidates()

        assertThat(candidates.map { it.scene }).containsExactlyInAnyOrder("TitleScene", "Map_scene")
        assertThat(candidates).allSatisfy { candidate ->
            assertThat(candidate.gaps).contains(EvidenceGap.PERSISTENT_SCENE_AMBIGUOUS.wire)
            assertThat(candidate.confidence).isEqualTo(AnalysisConfidence.AMBIGUOUS)
        }
    }

    /**
     * `scenes` 가 빈 문서에서는 판정 자체를 하지 않는다.
     *
     * `scene` 목록이 유일한 "무엇이 `scene`인가"의 근거라, 그것이 비었는데 규칙을 돌리면 **모든 오브젝트가 가짜
     * `scene` 에 있다**고 읽혀 지도가 통째로 빈다. `scene` 이름을 모르는 것과 그 이름이 `scene` 이 아닌 것은 다르다.
     */
    @Test
    fun `scene 목록이 빈 문서에서는 아무것도 옮기지 않는다`() {
        val model = documentOf(
            scenes = emptyList(),
            objects = listOf(sceneObject("Map_scene", "Systems/Saver", "Core.Saver")),
            persistentObjects = emptyList(),
            records = mapOf("Core.Saver" to listOf(record("Core.Saver", ConditionNode.Always))),
        )

        val candidates = EvidenceJoin(model).candidates()

        assertThat(candidates.map { it.scene }).containsOnly("Map_scene")
        assertThat(candidates.first().sceneAnchors).isEmpty()
    }

    // ── 손으로 만든 작은 문서 ────────────────────────────────────────────────
    // 실측 문서에 없는 경우만 여기서 만든다. 필드는 규칙이 읽는 것만 채우고 나머지는 빈 값이다 —
    // 규칙이 읽지 않는 칸을 채우면 무엇이 판정에 쓰였는지가 테스트에서 흐려진다.

    private fun persistentOnly(condition: ConditionNode): EvidenceDocumentModel = documentOf(
        scenes = listOf("TitleScene", "Map_scene"),
        objects = listOf(sceneObject("Map_scene", "Systems/Mover", "Core.Mover")),
        persistentObjects = listOf(sceneObject("DontDestroyOnLoad", "Runner", "Core.Runner")),
        records = mapOf("Core.Runner" to listOf(record("Core.Runner", condition))),
    )

    private fun documentOf(
        scenes: List<String>,
        objects: List<SceneObject>,
        persistentObjects: List<SceneObject>,
        records: Map<String, List<EvidenceRecord>>,
    ) = EvidenceDocumentModel(
        schema = 7,
        capture = "editor-play",
        promises = listOf("persistent-objects-v1"),
        build = EvidenceBuild(null, null, null, null, null, null),
        scenes = scenes,
        types = records,
        unplaced = emptyMap(),
        objects = objects,
        persistentObjects = persistentObjects,
        gaps = emptyList(),
    )

    private fun sceneObject(scene: String, path: String, vararg types: String) = SceneObject(
        path = path,
        selector = null,
        scene = scene,
        active = true,
        components = types.map { SceneComponent(type = it, calls = emptyList(), refs = emptyList()) },
        visuals = emptyList(),
    )

    private fun record(owner: String, condition: ConditionNode) = EvidenceRecord(
        owner = owner,
        entry = "System.Void $owner::Start()",
        entryId = "Assembly-CSharp|$owner|Start|System.Void()",
        source = "System.Void $owner::Start()",
        methodId = "Assembly-CSharp|$owner|Start|System.Void()",
        recordKind = "candidate",
        triggerKind = "lifecycle",
        confidence = "verified",
        callPath = listOf("System.Void $owner::Start()"),
        condition = condition,
        conditionJson = "{}",
        inputs = emptyList(),
        effects = emptyList(),
        calls = emptyList(),
        handles = emptyList(),
        alsoReachedBy = emptyList(),
        gaps = emptyList(),
        calledBy = emptyList(),
    )
}
