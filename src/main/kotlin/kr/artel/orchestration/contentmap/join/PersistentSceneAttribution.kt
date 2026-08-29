package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.ScenePresence
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord
import kr.artel.orchestration.contentmap.evidence.GroupKind
import kr.artel.orchestration.contentmap.evidence.SceneObject

/**
 * `DontDestroyOnLoad` 에 있는 오브젝트를 **모든 real `scene`** 에 두고, 근거가 지목한 `scene` 만 따로
 * 표시한다(ARTEL-460).
 *
 * 근거는 그런 오브젝트를 `persistentObjects` 에 따로 담고 그 `scene` 칸에 `DontDestroyOnLoad` 라고
 * 적는다. 배치 색인이 그 값을 그대로 받으면 지도에 `DontDestroyOnLoad` 라는 항목이 생긴다. 실측
 * 문서에서 capability 469 개 중 64 개가 그 항목에 앉았다. 거기서 만든 테스트케이스는 사전조건이
 * "`DontDestroyOnLoad` `scene` 이 실행 중이다" 가 되어 실행할 수 없다.
 *
 * ## 어디에 있나는 질문이 아니다
 *
 * `DontDestroyOnLoad` 는 `scene` load 를 넘어 살아남으라는 뜻이다. 만들어진 뒤로 그 오브젝트는 **모든
 * `scene` 에 실제로 존재한다.** 자리는 추론할 것이 아니라 이미 나와 있다. 그래서 이 클래스는 자리를
 * 고르지 않고 real `scene` 전부에 자리를 낸다.
 *
 * 가르는 것은 둘이다.
 *
 * | | 무엇인가 | 값 |
 * |---|---|---|
 * | 존재 | 모든 `scene`. 확실하고 추론이 아니다 | 자리를 낸다 |
 * | 여기서 의미가 있나 | 모른다. 확인해야 한다 | [ScenePresence] |
 *
 * 한쪽을 고르는 설계였다면 틀리는 방향이 조용하다 — 잘못 앉힌 `scene` 은 아무도 못 보고, 안 앉힌
 * `scene` 은 그 기능이 원래 없는 것처럼 보인다. 모든 `scene` 에 두면 틀리는 방향이 시끄러워지고,
 * QA agent 가 해 보고 안 되면 그 행만 내린다(ARTEL-644).
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
 * `Start` 와 `Update` 가 서로 다른 `scene` 에서 표시를 받는다 — 한 오브젝트가 두 화면으로 갈리는 것은
 * 그 오브젝트가 하나라는 사실과 어긋난다. 그래서 root 경로(`TutorialController/ChatWindow` 의 root 는
 * `TutorialController`)로 모아 한 번 판정하고, 그 답을 그 root 아래 전부에 적용한다.
 *
 * ## 여기서는 확실히 의미가 있다 — anchor 두 규칙
 *
 * | 규칙 | 무엇을 읽나 | resolution |
 * |---|---|---|
 * | [PersistentSceneRule.ACTIVE_SCENE_TEST] | 조건이 활성 `scene` 이름을 `==` 로 맞댄다 | `exact` |
 * | [PersistentSceneRule.CONDITION_SUBJECT_PLACED] | 조건이 읽는 타입이 딱 한 `scene` 에 있다 | `derived` |
 *
 * 둘 사이에 우선순위가 없다. 자리를 **고를 때**는 좁은 답이 넓은 답을 이겨야 했지만, 이제 아무 `scene`
 * 도 배제되지 않으므로 넓은 답은 표시를 더 붙일 뿐이다. 한 `scene` 을 여러 anchor 가 받치면 그중 가장
 * 확실한 것이 그 자리의 resolution 이다.
 *
 * 2번 규칙이 `scene` 이 하나로 정해진 타입만 anchor 로 쓰는 것은 그대로다. 실측
 * `Tutorial.TutorialController` 는 조건에서 `MapMove.StagePosition` 과
 * `StoryController.IsAdvanceKeyDown()` 을 둘 다 읽는데, `Map.MapMove` 는 `Map_scene` 에만 놓였고
 * `Story.StoryController` 는 `StoryScene` 과 `EndingScene` 두 곳에 놓였다. 자기 자리가 하나로 정해지지
 * 않은 타입은 남의 자리도 지목해 줄 수 없다.
 *
 * ## 옛 규칙 하나를 버렸다
 *
 * "같은 컴포넌트 타입이 진짜 `scene` 오브젝트에도 놓여 있다"(`persistent-also-placed`)가 그것이다.
 * 그 규칙은 persistent 사본을 그 `scene` 하나로 앉혔고, 같은 키를 내는 real 배치와 접혀 **행이 통째로
 * 사라졌다** — 실측에서 `Core.SaveLoadController` 3 건과 `Combat.Stage.StageDataSingleton` 4 건이
 * 그렇게 없어졌고, 저장이 어느 `scene` 에서나 된다는 사실이 지도에서 빠졌다.
 *
 * 규칙을 지워도 잃는 것이 없다. 그 `scene` 에 실제로 놓인 오브젝트가 이미 [ScenePresence.PLACED] 행을
 * 내고, 접힐 때 강한 값이 이기므로 그 자리는 그대로 `placed` 로 남는다.
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

    /** persistent root 경로 → 근거가 지목한 자리들. 지목이 하나도 없는 root 도 키로 남는다. */
    private val anchorsByRoot: Map<String, List<SceneAnchor>> = anchorRoots()

    /**
     * 이 오브젝트가 실제로 서 있는 자리들.
     *
     * 진짜 `scene` 에 놓인 오브젝트는 자기 자신 하나를 [ScenePresence.PLACED] 로 낸다. `scene` 을 넘어
     * 살아남는 오브젝트는 **real `scene` 하나마다 한 자리씩** 내고, 근거가 그 `scene` 을 지목했으면
     * [ScenePresence.PERSISTENT_EVIDENCED], 아니면 [ScenePresence.PERSISTENT_UNCONFIRMED] 다.
     *
     * 순서는 문서의 `scenes` 순서다 — 같은 문서를 두 번 읽으면 같은 순서가 나와야 재적재가 결정론적이다.
     */
    fun placementsOf(obj: SceneObject): List<ScenePlacement> {
        if (isRealScene(obj.scene)) return listOf(obj.toPlacement(ScenePresence.PLACED, anchors = emptyList()))
        val anchors = anchorsByRoot[rootOf(obj.path)].orEmpty()
        return document.scenes.map { scene ->
            val here = anchors.filter { it.scene == scene }
            val presence = if (here.isEmpty()) {
                ScenePresence.PERSISTENT_UNCONFIRMED
            } else {
                ScenePresence.PERSISTENT_EVIDENCED
            }
            obj.toPlacement(presence, anchors = here).copy(scene = scene)
        }
    }

    /**
     * `scenes` 가 빈 문서에서는 판정하지 않는다. `scene` 목록이 "무엇이 `scene` 인가"의 유일한 근거라,
     * 그것이 비었는데 규칙을 돌리면 모든 오브젝트가 가짜 `scene` 에 있다고 읽혀 지도가 통째로 빈다.
     */
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
    private fun anchorRoots(): Map<String, List<SceneAnchor>> {
        val objectsByRoot = LinkedHashMap<String, MutableList<SceneObject>>()
        for (obj in document.allObjects) {
            if (isRealScene(obj.scene)) continue
            objectsByRoot.getOrPut(rootOf(obj.path)) { mutableListOf() }.add(obj)
        }
        return objectsByRoot.mapValues { (rootPath, objects) -> anchorsOf(rootPath, objects) }
    }

    private fun anchorsOf(rootPath: String, objects: List<SceneObject>): List<SceneAnchor> {
        val types = objects.flatMap { obj -> obj.components.map { it.type } }.distinct()
        val objectAddress = objects.first().let { "${it.scene}/$rootPath" }
        // 같은 anchor 가 record 마다 다시 나온다 — 실측 `Tutorial.TutorialController` 는 `Start` 에서
        // 갈라진 record 열 개가 전부 같은 `MapMove.StagePosition` 조건을 이고 있다. 접지 않으면 그
        // 사슬이 `capability_proof` 에 열 벌로 앉아, 읽는 사람이 근거가 열 개라고 오해한다.
        return (activeSceneAnchors(objectAddress, types) + conditionSubjectAnchors(objectAddress, types))
            .distinct()
    }

    /**
     * 조건이 활성 `scene` 이름을 `scene` 이름 리터럴과 맞댄다. **게임이 직접 말한 것이라 추론이 아니다.**
     *
     * `!=` 는 anchor 가 되지 않는다. "저 `scene` 이 아니다"는 남은 `scene` 이 여럿이라 어느 하나를
     * 지목하지 못하고, 지목하지 못한 것을 anchor 라 부르면 나머지 `scene` 전부가 근거 있는 자리로
     * 보인다. 그 `scene` 에서 조건이 성립할 수 없다는 사실은 `ConditionBranches` 가 이미 쓴다 —
     * 그쪽이 후보를 아예 만들지 않는다.
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
     * 조건이 읽는 상태의 주인 타입이 딱 한 `scene` 에 놓여 있다. **유도지만 사슬이 남는다.**
     *
     * `MapMove.StagePosition <= 0` 의 주어는 `MapMove` 이고, `Map.MapMove` 는 `Map_scene` 에만 놓였다.
     * 그 조건이 성립할 수 있는 자리는 그 `scene` 뿐이므로 그 기능이 의미를 갖는 자리도 거기다.
     *
     * 조건의 **양쪽**을 다 본다 — `null == SaveLoadController._instance` 처럼 주어가 오른쪽에 오는
     * 비교가 실제로 있다. 짧은 이름이 두 타입에 걸리거나 그 타입이 여러 `scene` 에 놓였으면 버린다.
     * 반쪽짜리 단서로 자리를 지목하면 그 표시가 근거 없는 확신이 된다.
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
 * 근거가 이 `scene` 을 지목했다는 사실과 **무엇을 읽고 그렇게 판정했나.**
 *
 * 조용한 표시는 안 하느니만 못하다 — 언젠가 누군가 그 판정이 옳았는지 확인해야 하고, 그때 근거가
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
 * 근거가 persistent 오브젝트의 `scene` 을 지목한 방법. [resolution] 이 그 지목의 확실성이다.
 *
 * **선언 순서에 우선순위가 없다.** 두 규칙 모두 자기가 지목한 `scene` 을
 * [kr.artel.orchestration.contentmap.entity.ScenePresence.PERSISTENT_EVIDENCED] 로 올리고, 한 `scene` 을
 * 둘이 함께 받치면 더 확실한 쪽이 그 자리의 resolution 이 된다.
 *
 * 규칙 이름을 `capability_proof.rule` 에 그대로 싣는다. 같은 규칙이 계속 흐린 결론을 내면 그 이름이
 * 뭉쳐 나오고, 그것이 고칠 규칙이다.
 */
enum class PersistentSceneRule(val wire: String, val resolution: AnalysisConfidence) {
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
