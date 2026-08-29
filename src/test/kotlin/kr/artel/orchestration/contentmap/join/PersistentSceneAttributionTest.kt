package kr.artel.orchestration.contentmap.join

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.ScenePresence
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
 * `DontDestroyOnLoad` 에 있는 오브젝트가 **모든 real `scene`** 에 앉고, 근거가 지목한 `scene` 만 다른
 * 표시를 받는지 본다(ARTEL-460).
 *
 * 실측 문서는 `wv-editor-play-schema7.json` — `persistent-objects-v1` 을 약속하는 유일한 픽스처이고,
 * `DontDestroyOnLoad` 오브젝트 넷과 `scene` 일곱을 담는다. 그 넷이 이 규칙이 실제로 만나는 모양의
 * 전부이므로 앞쪽 테스트는 전부 그 문서로만 검증한다.
 *
 * 뒤쪽 몇 개는 손으로 만든 작은 문서를 쓴다. 실측 문서에 없는 두 경우(활성 `scene` 이름 조건 · 같은
 * 타입이 real `scene` 에도 놓인 경우)를 덮기 위해서이고, **실측 문서에 있는 경우를 손으로 만든 것으로
 * 대신하지는 않는다.** 문서의 진짜 모양을 상상으로 대체하는 것이 이 단계에서 가장 자주 틀리는
 * 가정이라는 판단은 `ConditionBranchesTest` 가 이미 적어 둔 것과 같다.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class PersistentSceneAttributionTest {

    private lateinit var document: EvidenceDocumentModel
    private lateinit var join: EvidenceJoin

    @BeforeAll
    fun parseOnce() {
        document = EvidenceParser(ObjectMapper())
            .parse(File("src/test/resources/contentmap/wv-editor-play-schema7.json").readText())
        join = EvidenceJoin(document)
    }

    /**
     * **이 이슈의 완료 조건 하나.** `scene` 이름이 아닌 항목이 후보에 남지 않는다.
     *
     * 후보의 `scene` 이 곧 `scene` 행의 이름이 되므로(적재기가 `model.scenes + 후보의 scene` 을 upsert
     * 한다), 여기가 통과하면 지도에도 그 이름이 앉지 않는다. 문서가 `DontDestroyOnLoad` 오브젝트 넷을
     * 들고 있는데도 통과해야 뜻이 있어, 그 전제를 함께 못 박는다.
     */
    @Test
    fun `scene 이 아닌 이름은 후보에 남지 않는다`() {
        assertThat(document.persistentObjects.map { it.path })
            .containsExactly("SaveLoadController", "TutorialController", "TutorialController/ChatWindow", "GameObject")
        assertThat(document.persistentObjects.map { it.scene }.distinct()).containsExactly("DontDestroyOnLoad")

        assertThat(join.candidates().map { it.scene }.distinct()).isSubsetOf(document.scenes)
    }

    /**
     * **완료 조건 둘** — 존재는 추론이 아니다.
     *
     * `DontDestroyOnLoad` 는 `scene` 을 넘어 살아남으라는 뜻이라, 만들어진 뒤로 그 오브젝트는 모든
     * `scene` 에 실제로 있다. 그러므로 그 오브젝트의 근거는 real `scene` 전부에서 후보가 된다.
     *
     * 튜토리얼이 그 예다. 옛 규칙은 이것을 `Map_scene` 하나로 좁혔는데, 그것은 "어디에 있나"라는
     * 답이 이미 나와 있는 질문을 다시 푼 것이었다.
     */
    @Test
    fun `살아남는 오브젝트의 근거는 real scene 전부에서 후보가 된다`() {
        val tutorial = join.candidates().filter { it.record.owner == "Tutorial.TutorialController" }

        assertThat(document.scenes).hasSize(7)
        assertThat(tutorial.map { it.scene }.distinct()).containsExactlyInAnyOrderElementsOf(document.scenes)
    }

    /**
     * **완료 조건 셋** — 근거가 지목한 `scene` 의 행은 그냥 살아남아 있는 행과 구별된다.
     *
     * `TutorialController.Start()` 는 `MapMove.StagePosition <= 0` 일 때 창을 띄운다. `Map.MapMove` 는
     * 문서에서 `Map_scene` **한 곳에만** 놓였으므로 그 조건이 성립할 수 있는 자리도 거기뿐이다. 같은
     * 게임의 손으로 쓴 옛 산출물이 같은 튜토리얼 행동을 `Map_scene` 에 실은 것과 답이 같다.
     *
     * 나머지 여섯 `scene` 의 행은 `persistent-unconfirmed` 다. 오브젝트가 거기 있다는 것은 확실하고
     * 거기서 그 기능이 되는지만 모른다 — 그것을 확인하는 쪽은 플레이해 본 QA agent 다(ARTEL-644).
     */
    @Test
    fun `근거가 지목한 scene 만 evidenced 이고 나머지는 unconfirmed 다`() {
        val tutorial = join.candidates().filter { it.record.owner == "Tutorial.TutorialController" }
        val byPresence = tutorial.groupBy({ it.scenePresence }, { it.scene })

        assertThat(byPresence.getValue(ScenePresence.PERSISTENT_EVIDENCED).distinct())
            .containsExactly("Map_scene")
        assertThat(byPresence.getValue(ScenePresence.PERSISTENT_UNCONFIRMED).distinct())
            .containsExactlyInAnyOrderElementsOf(document.scenes - "Map_scene")
        assertThat(tutorial.map { it.scenePresence }).doesNotContain(ScenePresence.PLACED)
    }

    /**
     * 자식 오브젝트도 root 의 판정을 그대로 받는다.
     *
     * `DontDestroyOnLoad` 는 루트를 자식째 옮긴다. 자식만 따로 판정하면
     * `TutorialController/ChatWindow` 가 부모와 다른 `scene` 에서 표시를 받아, 한 오브젝트가 두
     * 화면으로 갈린다.
     */
    @Test
    fun `자식 오브젝트는 root 의 표시를 따른다`() {
        val chatWindow = join.candidates().filter { it.record.owner == "Tutorial.TutorialChatWindow" }

        assertThat(chatWindow).isNotEmpty
        assertThat(chatWindow.filter { it.scenePresence == ScenePresence.PERSISTENT_EVIDENCED }.map { it.scene })
            .containsOnly("Map_scene")
    }

    /**
     * **완료 조건 넷** — 어느 근거로 그 `scene` 을 지목했는지 읽을 수 있다.
     *
     * 사슬 세 단계가 그대로 `capability_proof` 세 행이 된다. 조용한 표시는 안 하느니만 못하다 —
     * 언젠가 누군가 이 판정이 옳았는지 확인해야 하고, 그때 근거가 없으면 지도 전체를 의심하게 된다.
     *
     * 같은 조건을 읽는 record 가 열 개라도 사슬은 한 벌이다. 접지 않으면 읽는 사람이 근거가 열
     * 개라고 오해한다. 지목받지 못한 `scene` 의 행은 사슬이 **비어 있다** — 지목이 없다는 것이
     * 그 행의 내용이라, 빈 사슬을 만들면 두 상태가 표에서 같은 모양이 된다.
     */
    @Test
    fun `지목한 scene 의 행만 무엇을 읽고 그랬는지를 사슬로 든다`() {
        val tutorial = join.candidates().filter { it.record.owner == "Tutorial.TutorialController" }

        val evidenced = tutorial.first { it.scenePresence == ScenePresence.PERSISTENT_EVIDENCED }
        assertThat(evidenced.sceneAnchors).hasSize(1)
        val anchor = evidenced.sceneAnchors.single()
        assertThat(anchor.scene).isEqualTo("Map_scene")
        assertThat(anchor.rule).isEqualTo(PersistentSceneRule.CONDITION_SUBJECT_PLACED)
        assertThat(anchor.steps).containsExactly(
            AnchorStep("DontDestroyOnLoad/TutorialController", "condition-reads", "MapMove.StagePosition"),
            AnchorStep("MapMove.StagePosition", "owned-by", "Map.MapMove"),
            AnchorStep("Map.MapMove", "placed-in", "Map_scene"),
        )

        assertThat(tutorial.filter { it.scenePresence == ScenePresence.PERSISTENT_UNCONFIRMED })
            .allSatisfy { assertThat(it.sceneAnchors).isEmpty() }
    }

    /**
     * 여러 `scene` 에 놓인 타입은 어느 `scene` 도 지목하지 못한다.
     *
     * 같은 `TutorialController.Update` 가 `StoryController.IsAdvanceKeyDown()` 도 읽는다.
     * `Story.StoryController` 는 `StoryScene` 과 `EndingScene` 두 곳에 놓여 자기 자리가 하나로
     * 정해지지 않았고, 그런 타입은 남의 자리도 지목해 줄 수 없다.
     *
     * 두 `scene` 에 튜토리얼 행이 **없어지는 것은 아니다** — 오브젝트는 거기에도 있다. 없어지는 것은
     * "근거가 여기를 지목했다"는 표시뿐이다.
     */
    @Test
    fun `두 scene 에 놓인 타입을 읽는 조건은 어느 scene 도 지목하지 못한다`() {
        val storyControllerScenes = document.objects
            .filter { obj -> obj.components.any { it.type == "Story.StoryController" } }
            .map { it.scene }
            .distinct()
        val readsStoryController = document.types.getValue("Tutorial.TutorialController")
            .any { record -> record.condition.conjunctiveTests().any { it.left.startsWith("StoryController.") } }

        assertThat(storyControllerScenes).containsExactlyInAnyOrder("StoryScene", "EndingScene")
        assertThat(readsStoryController).isTrue()

        val inStoryScenes = join.candidates()
            .filter { it.record.owner == "Tutorial.TutorialController" && it.scene in storyControllerScenes }
        assertThat(inStoryScenes).isNotEmpty
        assertThat(inStoryScenes.map { it.scenePresence }).containsOnly(ScenePresence.PERSISTENT_UNCONFIRMED)
    }

    /**
     * **옛 규칙이 지웠던 두 타입이 살아 있다.** 이 이슈가 다시 열린 이유가 그것이다.
     *
     * 옛 1단계(`persistent-also-placed`)는 같은 타입이 real `scene` 오브젝트에도 있으면 persistent
     * 사본을 그 `scene` 하나로 앉혔고, 같은 키를 내는 real 배치와 접혀 행이 통째로 사라졌다 —
     * `Core.SaveLoadController` 3 건과 `Combat.Stage.StageDataSingleton` 4 건이다. 저장이 어느
     * `scene` 에서나 된다는 사실이 그렇게 지도에서 빠졌다.
     *
     * `StageDataSingleton` 은 조건이 읽는 것이 자기 자신(`StageDataSingleton.Instance`)뿐이라 어느
     * `scene` 도 지목받지 못한다. 그래도 행은 real `scene` 전부에 남는다 — 오브젝트가 거기 있다는
     * 것은 지목과 무관한 사실이기 때문이다.
     */
    @Test
    fun `옛 규칙이 지웠던 두 타입의 근거가 모든 scene 에 살아 있다`() {
        val revived = join.candidates()
            .filter { it.record.owner in setOf("Core.SaveLoadController", "Combat.Stage.StageDataSingleton") }

        assertThat(revived.map { it.record.owner }.distinct())
            .containsExactlyInAnyOrder("Core.SaveLoadController", "Combat.Stage.StageDataSingleton")
        assertThat(revived.filter { it.record.owner == "Combat.Stage.StageDataSingleton" })
            .isNotEmpty
            .allSatisfy { assertThat(it.scenePresence).isEqualTo(ScenePresence.PERSISTENT_UNCONFIRMED) }
        assertThat(
            revived.filter { it.record.owner == "Combat.Stage.StageDataSingleton" }.map { it.scene }.distinct()
        ).containsExactlyInAnyOrderElementsOf(document.scenes)

        // 조인이 깨진 것이 아니라는 반대쪽 못. 실측 0 이고, 이 수가 0 이 아니면 배치 색인이 고장난 것이다.
        assertThat(join.unaddressedRecords()).isZero()
    }

    /**
     * 유도로 지목한 행의 확신도는 `exact` 로 남지 못한다.
     *
     * `analysis_confidence` 는 사슬의 가장 흐린 단계로 정의된다. `scene` 지목도 그 사슬의 한 단계라,
     * 근거가 스스로 `verified` 라고 말했어도 지목이 유도였으면 결론은 유도다.
     *
     * **`persistent-unconfirmed` 행은 내리지 않는다.** 그 행이 거기 있다는 것은 유도가 아니라 사실
     * 이고, 흐린 것은 여기서 의미가 있나다 — 그 흐림은 `scene_presence` 가 진다. 두 값을 한 칸에
     * 담으면 둘 다 못 읽는다.
     */
    @Test
    fun `유도로 지목한 행만 확신도가 내려간다`() {
        val tutorial = join.candidates().filter { it.record.owner == "Tutorial.TutorialController" }

        assertThat(tutorial.filter { it.scenePresence == ScenePresence.PERSISTENT_EVIDENCED }.map { it.confidence })
            .isNotEmpty
            .doesNotContain(AnalysisConfidence.EXACT)
        // 지목받지 못한 행은 record 가 말한 값 그대로다. 하나도 안 내려갔다는 것을 `exact` 가 남아
        // 있는 것으로 못 박는다 — 통째로 내려갔다면 이 값이 사라진다.
        assertThat(tutorial.filter { it.scenePresence == ScenePresence.PERSISTENT_UNCONFIRMED }.map { it.confidence })
            .contains(AnalysisConfidence.EXACT)

        // 같은 record 의 같은 branch 안에서 비교한다. 지목이 `derived` 였으므로 지목받은 행의 확신도는
        // record 가 말한 값과 `derived` 중 더 흐린 쪽이고, 지목받지 못한 행은 record 가 말한 값 그대로다.
        // record 를 키에 그대로 둔다 — 실측에는 `entryId` 와 `branch` 가 같은데 `confidence` 만 다른
        // record 쌍이 있어, 주소만으로 묶으면 서로 다른 두 record 가 한 무리가 된다.
        tutorial.groupBy { Triple(it.record, it.branchOffset, it.condition) }
            .values
            .filter { group -> group.any { it.scenePresence == ScenePresence.PERSISTENT_EVIDENCED } }
            .forEach { group ->
                val evidenced = group.first { it.scenePresence == ScenePresence.PERSISTENT_EVIDENCED }
                val unconfirmed = group.filter { it.scenePresence == ScenePresence.PERSISTENT_UNCONFIRMED }
                assertThat(unconfirmed).isNotEmpty
                assertThat(evidenced.confidence)
                    .isEqualTo(maxOf(unconfirmed.first().confidence, AnalysisConfidence.DERIVED))
                assertThat(unconfirmed.map { it.confidence }.distinct()).hasSize(1)
            }
    }

    /**
     * 같은 타입이 real `scene` 에도 놓여 있으면 그 `scene` 의 자리는 `placed` 로 남는다.
     *
     * 옛 1단계 규칙이 하던 일을 규칙 없이 얻는다 — real 배치가 이미 `placed` 자리를 내고, 같은
     * `scene` 의 자리를 합칠 때 **가장 강한 값**이 이긴다. 문서가 직접 놓았다고 말한 자리를 "여기서
     * 되는지 모른다"로 내리면 아는 것을 모른다고 적는 것이 된다.
     *
     * 아래 문서에서 `Core.Saver` 는 `Map_scene` 의 `Systems/Saver` 에도 놓였고 `DontDestroyOnLoad` 에도
     * 사본이 있다. `TitleScene` 에는 사본만 있으므로 그쪽은 `persistent-unconfirmed` 다.
     */
    @Test
    fun `real scene 에도 놓인 타입은 그 scene 에서 placed 로 남는다`() {
        val model = documentOf(
            scenes = listOf("TitleScene", "Map_scene"),
            objects = listOf(sceneObject("Map_scene", "Systems/Saver", "Core.Saver")),
            persistentObjects = listOf(sceneObject("DontDestroyOnLoad", "Saver", "Core.Saver")),
            records = mapOf("Core.Saver" to listOf(record("Core.Saver", ConditionNode.Always))),
        )

        val candidates = EvidenceJoin(model).candidates()

        assertThat(candidates.associate { it.scene to it.scenePresence }).isEqualTo(
            mapOf(
                "Map_scene" to ScenePresence.PLACED,
                "TitleScene" to ScenePresence.PERSISTENT_UNCONFIRMED,
            )
        )
        assertThat(candidates).allSatisfy { assertThat(it.sceneAnchors).isEmpty() }
    }

    /**
     * 코드가 활성 `scene` 이름을 직접 맞대면 그 `scene` 을 지목한 것이다. 유도가 아니라 코드가 말한 것이다.
     *
     * `==` 조건은 다른 `scene` 에서 성립할 수 없으므로 `ConditionBranches` 가 그쪽 후보를 아예 만들지
     * 않는다 — 그래서 행이 그 `scene` 하나뿐이다. `!=` 는 반대다. 지목은 못 하지만 남은 `scene` 에서는
     * 조건이 성립할 수 있어 행이 그대로 남고, 전부 `persistent-unconfirmed` 다.
     */
    @Test
    fun `활성 scene 이름 조건은 그 scene 을 지목한다`() {
        val equals = ConditionNode.Test(ACTIVE_SCENE_NAME, "==", "\"Map_scene\"", context = "static", offset = 3)
        val notEquals = ConditionNode.Test(ACTIVE_SCENE_NAME, "!=", "\"TitleScene\"", context = "static", offset = 3)

        val pinned = EvidenceJoin(persistentOnly(equals)).candidates()
        val unpinned = EvidenceJoin(persistentOnly(notEquals)).candidates()

        assertThat(pinned.map { it.scene }).containsOnly("Map_scene")
        assertThat(pinned.map { it.scenePresence }).containsOnly(ScenePresence.PERSISTENT_EVIDENCED)
        assertThat(pinned.first().sceneAnchors.single().rule).isEqualTo(PersistentSceneRule.ACTIVE_SCENE_TEST)
        assertThat(pinned.first().sceneAnchors.single().steps).containsExactly(
            AnchorStep("DontDestroyOnLoad/Runner", "condition-reads", ACTIVE_SCENE_NAME),
            AnchorStep(ACTIVE_SCENE_NAME, "equals", "Map_scene"),
        )

        assertThat(unpinned.map { it.scene }).containsOnly("Map_scene")
        assertThat(unpinned.map { it.scenePresence }).containsOnly(ScenePresence.PERSISTENT_UNCONFIRMED)
    }

    /**
     * 조건이 서로 다른 `scene` 의 타입 둘을 읽으면 **둘 다 지목**이다.
     *
     * 자리를 하나 고르던 때에는 이것이 "좁히지 못했다"는 gap 이었다. 이제는 아무 `scene` 도 배제되지
     * 않으므로 지목이 둘인 것이 손실이 아니다 — 두 자리 다 근거가 있고, 어느 근거였는지는 각
     * `scene` 의 `capability_proof` 사슬이 답한다.
     */
    @Test
    fun `서로 다른 scene 의 타입을 읽는 조건은 두 scene 을 다 지목한다`() {
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
            assertThat(candidate.scenePresence).isEqualTo(ScenePresence.PERSISTENT_EVIDENCED)
            assertThat(candidate.sceneAnchors.map { it.scene }).containsExactly(candidate.scene)
        }
    }

    /**
     * `scenes` 가 빈 문서에서는 판정 자체를 하지 않는다.
     *
     * `scene` 목록이 유일한 "무엇이 `scene`인가"의 근거라, 그것이 비었는데 규칙을 돌리면 **모든 오브젝트가
     * 가짜 `scene` 에 있다**고 읽혀 지도가 통째로 빈다. `scene` 이름을 모르는 것과 그 이름이 `scene` 이
     * 아닌 것은 다르다.
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
        assertThat(candidates.map { it.scenePresence }).containsOnly(ScenePresence.PLACED)
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
