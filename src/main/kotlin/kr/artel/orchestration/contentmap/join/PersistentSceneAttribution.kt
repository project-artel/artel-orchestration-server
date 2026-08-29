package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.SceneObject

/**
 * `DontDestroyOnLoad` 에 있는 오브젝트가 실제로 동작하는 `scene` 을 정한다.
 *
 * 근거는 그런 오브젝트를 `persistentObjects` 에 따로 담고 그 `scene` 칸에 `DontDestroyOnLoad` 라고
 * 적는다. 배치 색인이 그 값을 그대로 받으면 지도에 `DontDestroyOnLoad` 라는 항목이 생긴다. 실측
 * 문서에서 capability 469 개 중 64 개가 그 항목에 앉았다. 거기서 만든 테스트케이스는 사전조건이
 * "`DontDestroyOnLoad` `scene` 이 실행 중이다" 가 되어 실행할 수 없다.
 *
 * **타입 이름으로 판정하지 않는다.** `TutorialController` · `SaveLoadController` 는 이 게임 한 벌의
 * 클래스 이름이고, SDK 는 임의의 Unity 게임에 붙는다. 판단 재료는 근거의 **구조와 조건**뿐이다.
 *
 * ## 무엇을 `scene` 으로 치나
 *
 * 문서의 `scenes` 배열에 있는 이름만 `scene` 이다. `DontDestroyOnLoad` 를 문자열로 지목하지 않는 이유는
 * 그것이 오늘 Unity 가 쓰는 이름일 뿐이기 때문이다 — 엔진이 다른 이름을 쓰거나 수집기가 또 다른
 * 가짜 `scene` 을 만들어도 같은 규칙이 걸린다. `scenes` 가 비어 있는 문서에서는 이 판정을 하지 않는다.
 * 그때는 모든 오브젝트가 가짜 `scene` 에 있다고 읽혀 지도가 통째로 비기 때문이다.
 *
 * ## 판단 단위는 record 가 아니라 **오브젝트 root**
 *
 * `DontDestroyOnLoad` 는 루트 오브젝트를 자식째 옮긴다. record 마다 따로 판정하면 같은 오브젝트의
 * `Start` 와 `Update` 가 서로 다른 `scene` 에 실린다 — 한 오브젝트가 두 화면에 나뉘어 앉는 것은 그
 * 오브젝트가 하나라는 사실과 어긋난다. 그래서 root 경로(`TutorialController/ChatWindow` 의 root 는
 * `TutorialController`)로 모아 한 번 판정하고, 그 답을 그 root 아래 전부에 적용한다.
 *
 * ## anchor 세 단계, 위가 이긴다
 *
 * | 단계 | 무엇을 읽나 | resolution |
 * |---|---|---|
 * | [PersistentSceneRule.ALSO_PLACED] | 같은 컴포넌트 타입이 진짜 `scene` 오브젝트에도 놓여 있다 | `exact` |
 * | [PersistentSceneRule.ACTIVE_SCENE_TEST] | 조건이 활성 `scene` 이름을 `==` 로 맞댄다 | `exact` |
 * | [PersistentSceneRule.CONDITION_SUBJECT_PLACED] | 조건이 읽는 타입이 딱 한 `scene` 에 있다 | `derived` |
 *
 * 위 단계가 하나라도 `scene` 을 내면 아래는 보지 않는다. 아래 단계가 더 넓은 답을 내기 때문이고,
 * 넓은 답을 좁은 답에 섞으면 확실한 자리에 불확실한 자리가 딸려 온다.
 *
 * 3단계는 `scene` 이 하나로 정해진 타입만 anchor 로 쓴다. 실측 `Tutorial.TutorialController` 는
 * 조건에서 `MapMove.StagePosition` 과 `StoryController.IsAdvanceKeyDown()` 을 둘 다 읽는데,
 * `Map.MapMove` 는 `Map_scene` 에만 놓였고 `Story.StoryController` 는 `StoryScene` 과 `EndingScene`
 * 두 곳에 놓였다. 자기 자리가 하나로 정해지지 않은 타입은 남의 자리도 정해 줄 수 없으므로
 * `MapMove` 만 anchor 가 되고 `TutorialController` 는 `Map_scene` 하나로 앉는다. 이 조건이 없으면
 * 같은 capability 가 `Map_scene` · `StoryScene` · `EndingScene` 세 곳에 복제된다.
 *
 * ## 정하지 못하면 비운다
 *
 * anchor 가 하나도 없으면 `scene` 목록이 빈 채로 나가고, 그 root 의 capability 는 행이 되지 않는다.
 * 아무 `scene` 에나 붙이는 것이 여기서 할 수 있는 가장 나쁜 선택이라는 판단은 [EvidenceJoin] 이
 * `scene` 없는 record 에 대해 이미 내려 둔 것과 같다. 몇 건이 그렇게 빠졌는지는
 * [EvidenceJoin.unattributedPersistentRecords] 가 센다.
 *
 * DB 도 Spring 도 없는 순수 계산이다. 입력은 파싱된 [EvidenceDocumentModel] 하나뿐이다.
 */
class PersistentSceneAttribution(private val document: EvidenceDocumentModel) {

    /** 문서가 `scene` 이라고 말한 이름들. 이 집합 밖의 `scene` 값은 `scene` 이 아니다. */
    private val realSceneNames: Set<String> = document.scenes.toSet()

    /** 진짜 `scene` 에 놓인 타입 → 그 타입이 놓인 `scene`들. 문서 순서. */
    private val realScenesByType: Map<String, List<String>> = buildRealPlacements()

    /** 짧은 이름 → 그것을 가진 배치된 타입 풀네임들. 둘 이상이면 조건 주어를 풀 수 없다. */
    private val placedTypesBySimpleName: Map<String, List<String>> =
        realScenesByType.keys.groupBy { it.substringAfterLast('.') }

    private val attributionsByRoot: Map<String, PersistentAttribution> = attributeRoots()

    /**
     * 이 오브젝트가 실제로 동작하는 자리들. 진짜 `scene` 에 놓인 오브젝트는 자기 자신 하나를 그대로 낸다.
     *
     * 귀속하지 못한 persistent 오브젝트는 **빈 목록**이다 — 부르는 쪽이 그 오브젝트를 통째로 빼야
     * 한다는 뜻이고, 가짜 `scene` 이름이 색인에 들어가는 유일한 길이 여기서 막힌다.
     */
    fun placementsOf(obj: SceneObject): List<ScenePlacement> {
        if (isRealScene(obj.scene)) return listOf(obj.toPlacement(anchors = emptyList()))
        val attribution = attributionsByRoot[rootOf(obj.path)] ?: return emptyList()
        return attribution.scenes.map { scene ->
            obj.toPlacement(anchors = attribution.anchorsAt(scene)).copy(scene = scene)
        }
    }

    /** 귀속하지 못한 root 경로들. 문서 순서. 비어 있는 것이 정상이고, 차 있으면 그만큼이 gap 이다. */
    fun unresolvedRoots(): List<String> =
        attributionsByRoot.values.filter { it.scenes.isEmpty() }.map { it.rootPath }

    /** 이 타입이 귀속 대상 persistent 오브젝트 위에만 사는가. 진짜 `scene` 에도 놓였으면 false. */
    fun isPersistentOnly(type: String): Boolean =
        type !in realScenesByType && document.persistentObjects.any { obj -> obj.carries(type) }

    private fun isRealScene(name: String): Boolean =
        realSceneNames.isEmpty() || name in realSceneNames

    private fun buildRealPlacements(): Map<String, List<String>> {
        val scenesByType = LinkedHashMap<String, MutableList<String>>()
        for (obj in document.allObjects) {
            if (!isRealScene(obj.scene)) continue
            for (type in obj.components.map { it.type }.distinct()) {
                val scenes = scenesByType.getOrPut(type) { mutableListOf() }
                if (obj.scene !in scenes) scenes += obj.scene
            }
        }
        return scenesByType
    }

    /**
     * root 마다 한 번 판정한다. 키 순서는 문서 순서다 — 같은 문서를 두 번 읽으면 같은 순서가 나와야
     * 재적재가 결정론적이다.
     */
    private fun attributeRoots(): Map<String, PersistentAttribution> {
        val objectsByRoot = LinkedHashMap<String, MutableList<SceneObject>>()
        for (obj in document.allObjects) {
            if (isRealScene(obj.scene)) continue
            objectsByRoot.getOrPut(rootOf(obj.path)) { mutableListOf() }.add(obj)
        }
        return objectsByRoot.mapValues { (rootPath, objects) -> attribute(rootPath, objects) }
    }

    private fun attribute(rootPath: String, objects: List<SceneObject>): PersistentAttribution {
        val types = objects.flatMap { obj -> obj.components.map { it.type } }.distinct()
        val objectAddress = objects.first().let { "${it.scene}/$rootPath" }
        // 같은 anchor 가 record 마다 다시 나온다 — 실측 `Tutorial.TutorialController` 는 `Start` 에서
        // 갈라진 record 열 개가 전부 같은 `MapMove.StagePosition` 조건을 이고 있다. 접지 않으면 그
        // 사슬이 `capability_proof` 에 열 벌로 앉아, 읽는 사람이 근거가 열 개라고 오해한다.
        val anchors = PersistentSceneRule.entries
            .firstNotNullOfOrNull { rule ->
                anchorsBy(rule, objectAddress, types).distinct().takeIf { it.isNotEmpty() }
            }
            .orEmpty()
        return PersistentAttribution(rootPath, anchors)
    }

    private fun anchorsBy(
        rule: PersistentSceneRule,
        objectAddress: String,
        types: List<String>,
    ): List<SceneAnchor> =
        when (rule) {
            PersistentSceneRule.ALSO_PLACED -> alsoPlacedAnchors(objectAddress, types)
            PersistentSceneRule.ACTIVE_SCENE_TEST -> activeSceneAnchors(objectAddress, types)
            PersistentSceneRule.CONDITION_SUBJECT_PLACED -> conditionSubjectAnchors(objectAddress, types)
        }

    /**
     * 같은 컴포넌트 타입이 진짜 `scene` 오브젝트에도 놓여 있다.
     *
     * 실측 `Core.SaveLoadController` 와 `Combat.Stage.StageDataSingleton` 이 그렇다. 싱글턴이 `scene`마다
     * 자기 사본을 두고 첫 하나만 살아남는 흔한 모양이고, 문서가 "이 타입은 이 `scene` 에 놓인다"고 직접
     * 말한 것이라 유도가 필요 없다.
     */
    private fun alsoPlacedAnchors(objectAddress: String, types: List<String>): List<SceneAnchor> =
        types.flatMap { type ->
            realScenesByType[type].orEmpty().map { scene ->
                SceneAnchor(
                    scene = scene,
                    rule = PersistentSceneRule.ALSO_PLACED,
                    steps = listOf(
                        AnchorStep(objectAddress, "component-type", type),
                        AnchorStep(type, "placed-in", scene),
                    ),
                )
            }
        }

    /**
     * 조건이 활성 `scene` 이름을 `scene` 이름 리터럴과 맞댄다.
     *
     * `!=` 는 anchor 가 되지 않는다. "저 `scene` 이 아니다"는 남은 `scene` 이 여럿이라 자리를 하나로 좁히지
     * 못하고, 좁히지 못한 것을 anchor 라 부르면 나머지 `scene` 전부에 기능이 복제된다.
     */
    private fun activeSceneAnchors(objectAddress: String, types: List<String>): List<SceneAnchor> =
        recordsOf(types).flatMap { record ->
            record.condition.conjunctiveTests()
                .filter { it.left == ACTIVE_SCENE_NAME && it.operator == "==" }
                .mapNotNull { test -> test.right.unquotedOrNull() }
                .filter { it in realSceneNames }
                .map { scene ->
                    SceneAnchor(
                        scene = scene,
                        rule = PersistentSceneRule.ACTIVE_SCENE_TEST,
                        steps = listOf(
                            AnchorStep(objectAddress, "condition-reads", ACTIVE_SCENE_NAME),
                            AnchorStep(ACTIVE_SCENE_NAME, "equals", scene),
                        ),
                    )
                }
        }

    /**
     * 조건이 읽는 상태의 주인 타입이 딱 한 `scene` 에 놓여 있다.
     *
     * `MapMove.StagePosition <= 0` 의 주어는 `MapMove` 이고, `Map.MapMove` 는 `Map_scene` 에만 놓였다.
     * 그 조건이 성립할 수 있는 자리는 그 `scene` 뿐이므로 오브젝트도 거기서 동작한다.
     *
     * 조건의 **양쪽**을 다 본다 — `null == SaveLoadController._instance` 처럼 주어가 오른쪽에 오는
     * 비교가 실제로 있다. 짧은 이름이 두 타입에 걸리거나 그 타입이 여러 `scene` 에 놓였으면 버린다.
     * 반쪽짜리 단서로 자리를 정하면 QA agent 가 없는 컨트롤을 찾으러 간다.
     */
    private fun conditionSubjectAnchors(objectAddress: String, types: List<String>): List<SceneAnchor> =
        recordsOf(types).flatMap { record ->
            record.condition.conjunctiveTests().flatMap { test ->
                listOf(test.left, test.right).mapNotNull { subjectAnchor(objectAddress, it) }
            }
        }

    private fun subjectAnchor(objectAddress: String, expression: String): SceneAnchor? {
        if (expression == ACTIVE_SCENE_NAME) return null
        val owner = leadingIdentifier(expression) ?: return null
        val type = placedTypesBySimpleName[owner]?.singleOrNull() ?: return null
        val scene = realScenesByType.getValue(type).singleOrNull() ?: return null
        return SceneAnchor(
            scene = scene,
            rule = PersistentSceneRule.CONDITION_SUBJECT_PLACED,
            steps = listOf(
                AnchorStep(objectAddress, "condition-reads", expression),
                AnchorStep(expression, "owned-by", type),
                AnchorStep(type, "placed-in", scene),
            ),
        )
    }

    private fun recordsOf(types: List<String>): List<EvidenceRecord> =
        types.flatMap { type -> document.types[type].orEmpty() + document.unplaced[type]?.evidence.orEmpty() }

    private fun SceneObject.carries(type: String): Boolean = components.any { it.type == type }

    private companion object {

        /** `TutorialController/ChatWindow` 의 root 는 `TutorialController`. */
        fun rootOf(path: String): String = path.substringBefore('/')

        /**
         * 표현식이 읽는 대상의 **첫 마디**. `MapMove.StagePosition` → `MapMove`.
         *
         * 첫 마디만 보는 이유: 근거는 IL 을 소스 표기로 렌더한 문자열을 주고, 상태를 소유한 타입은
         * 그 표기의 맨 앞에 온다. 뒤 마디까지 뒤지면 `TutorialController.tutorialChatWindow.IsStreaming`
         * 의 `IsStreaming` 같은 멤버 이름이 타입 이름과 우연히 맞아 엉뚱한 `scene` 을 집는다.
         */
        fun leadingIdentifier(expression: String): String? =
            LEADING_IDENTIFIER.find(expression)?.value

        val LEADING_IDENTIFIER = Regex("^[A-Za-z_][A-Za-z0-9_]*")
    }
}

/**
 * persistent 오브젝트 root 하나의 판정 결과.
 *
 * [scenes] 가 비면 정하지 못한 것이다. 비지 않았는데 둘 이상이면 하나로 좁히지 못한 것이고, 그때
 * 부르는 쪽이 [kr.artel.orchestration.contentmap.entity.EvidenceGap.PERSISTENT_SCENE_AMBIGUOUS] 를 남긴다.
 */
private data class PersistentAttribution(val rootPath: String, val anchors: List<SceneAnchor>) {

    /** anchor 가 가리킨 `scene`들. 문서 순서로 한 번씩만. */
    val scenes: List<String> = anchors.map { it.scene }.distinct()

    /**
     * 이 `scene` 에 붙일 anchor 들.
     *
     * [scenes] 가 하나면 그 `scene` 의 anchor 만 낸다. 둘 이상이면 **모든 `scene` 의 anchor 를 다 낸다** — `scene` 을
     * 하나로 좁히지 못했다는 사실 자체가 그 기능에 대해 알아야 할 것이고, `Map_scene` 줄만 보는
     * 사람이 `TitleScene` 후보가 있었다는 것을 모르면 그 줄을 확정으로 읽는다. 부르는 쪽은 이
     * 목록의 `scene` 이 둘 이상인 것을 보고 `persistent-scene-ambiguous` 를 남긴다.
     */
    fun anchorsAt(scene: String): List<SceneAnchor> =
        if (scenes.size > 1) anchors else anchors.filter { it.scene == scene }
}

/**
 * persistent 오브젝트를 어떤 `scene` 으로 옮겼고 **무엇을 읽고 그렇게 판정했나.**
 *
 * 조용한 재귀속은 안 하느니만 못하다 — 언젠가 누군가 그 판정이 옳았는지 확인해야 하고, 그때 근거가
 * 없으면 지도 전체를 의심하게 된다. [steps] 가 그 근거이고, 적재기가 그것을 `capability_proof` 행으로
 * 옮긴다(한 단계 = 한 행).
 */
data class SceneAnchor(
    val scene: String,
    val rule: PersistentSceneRule,
    /** 결론에 이르는 단계들. 순서가 곧 사슬의 순서다. */
    val steps: List<AnchorStep>,
)

/** 사슬 한 단계 — [source] 를 [relation] 으로 따라가 [target] 에 닿았다. `capability_proof` 한 행이다. */
data class AnchorStep(val source: String, val relation: String, val target: String)

/**
 * persistent 오브젝트를 `scene` 에 앉힌 규칙. **선언 순서가 곧 우선순위**이고, [resolution] 이 그 단계의
 * 확실성이다.
 *
 * 규칙 이름을 `capability_proof.rule` 에 그대로 싣는다. 같은 규칙이 계속 흐린 결론을 내면 그 이름이
 * 뭉쳐 나오고, 그것이 고칠 규칙이다.
 */
enum class PersistentSceneRule(val wire: String, val resolution: AnalysisConfidence) {
    /** 같은 컴포넌트 타입이 진짜 `scene` 오브젝트에도 놓여 있다. 문서가 직접 말한 자리다. */
    ALSO_PLACED("persistent-also-placed", AnalysisConfidence.EXACT),

    /** 조건이 활성 `scene` 이름을 그 `scene` 과 맞댄다. 코드가 직접 말한 자리다. */
    ACTIVE_SCENE_TEST("persistent-active-scene-test", AnalysisConfidence.EXACT),

    /** 조건이 읽는 상태의 주인 타입이 딱 한 `scene` 에 놓여 있다. 유도다. */
    CONDITION_SUBJECT_PLACED("persistent-condition-subject-placed", AnalysisConfidence.DERIVED),
    ;

    companion object {
        fun from(wire: String?): PersistentSceneRule? = entries.firstOrNull { it.wire == wire }
    }
}

/**
 * 이 노드에서 **`every` 사슬로만** 내려가며 만나는 test 들.
 *
 * `either` 안쪽은 반대쪽 `branch`로 만족될 수 있어 조건을 단정하지 못한다. `ConditionBranches` 가 `scene`
 * 이름 조건을 볼 때 쓰는 규칙과 같은 것이고, 두 곳이 다른 규칙을 쓰면 같은 트리를 두고 서로 다른
 * `scene` 을 말하게 된다.
 */
internal fun ConditionNode.conjunctiveTests(): List<ConditionNode.Test> = when {
    this is ConditionNode.Test -> listOf(this)
    this is ConditionNode.Group && kind == GroupKind.EVERY -> parts.flatMap { it.conjunctiveTests() }
    else -> emptyList()
}

/**
 * `SceneManager.GetActiveScene().name` — `scene` 이름 조건의 왼쪽 항. 실측 12건이 전부 이 문자열이다.
 *
 * 부분 일치로 넓히지 않는다 — `GetActiveScene().buildIndex` 같은 다른 조건까지 `scene` 이름으로 오인한다.
 */
internal const val ACTIVE_SCENE_NAME = "SceneManager.GetActiveScene().name"

/**
 * `"\"GameClearScene\""` 처럼 **따옴표까지 값에 든** 항에서 `scene` 이름을 꺼낸다.
 *
 * 근거는 IL 의 문자열 리터럴을 소스 표기 그대로 렌더해 넣는다. 따옴표를 벗기지 않고 비교하면 실측
 * 12건이 전부 어긋나 어떤 `scene` 에서도 남지 않는다.
 */
internal fun String.unquotedOrNull(): String? =
    if (length >= 2 && startsWith('"') && endsWith('"')) substring(1, length - 1) else null
