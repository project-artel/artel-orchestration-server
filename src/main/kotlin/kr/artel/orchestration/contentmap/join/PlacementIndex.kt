package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.SceneObject

/**
 * 컴포넌트 타입 → 그 타입이 실제로 놓인 자리들.
 *
 * 문서의 씬 절반은 **오브젝트를 축으로** 적혀 있다(오브젝트 하나가 컴포넌트 여럿을 인다). 조인이 묻는
 * 것은 그 반대다 — "이 타입은 어느 씬 어디에 있나". 축을 뒤집어 한 번만 훑는다. 실측 문서는 씬
 * 오브젝트 27개 · 컴포넌트 35개인데 근거 레코드는 318건이라, 레코드마다 다시 훑으면 같은 순회가
 * 318번 반복된다.
 *
 * `unplaced` 는 여기 들어오지 않는다. 프리팹 위에만 사는 타입은 자리가 아니라 출처([SpawnOrigin])로
 * 씬에 붙고, 그 판정은 다른 단계의 일이다. 둘을 한 인덱스에 섞으면 "놓였다"와 "만들어진다"가
 * 같은 답으로 나온다.
 *
 * **씬이 아닌 이름은 색인에 들어오지 않는다.** `DontDestroyOnLoad` 에 있는 오브젝트는
 * [PersistentSceneAttribution] 이 실제 실행 씬으로 옮긴 뒤에야 자리가 되고, 옮기지 못한 것은 자리를
 * 하나도 내지 않는다(ARTEL-460). 색인이 가짜 씬 이름을 담으면 그 이름이 그대로 지도의 항목이 되고,
 * 거기 앉은 기능은 아무도 갈 수 없는 사전조건을 갖는다.
 */
class PlacementIndex private constructor(
    private val placementsByType: Map<String, List<ScenePlacement>>,
) {

    /** 이 타입이 놓인 자리들. 문서 순서. 놓인 적이 없으면 빈 목록이다. */
    fun placementsOf(type: String): List<ScenePlacement> = placementsByType[type].orEmpty()

    /**
     * 이 타입이 사는 씬들. 문서 순서로 한 번씩만 낸다.
     *
     * 씬은 후보 행의 축이라 같은 씬이 두 번 나오면 그대로 행이 두 벌이 된다 — 실측
     * `UnityEngine.UI.Button` 은 자리 7개가 씬 3개에 몰려 있다.
     */
    fun scenesOf(type: String): List<String> = placementsOf(type).map { it.scene }.distinct()

    /** 자리를 하나라도 가진 타입들. 실측 22개. */
    val placedTypes: Set<String> get() = placementsByType.keys

    /** 인덱싱된 자리 총 개수. 실측 33 — 컴포넌트 35개에서 오브젝트·타입이 겹친 2건이 접힌 수다. */
    val placementCount: Int get() = placementsByType.values.sumOf { it.size }

    companion object {

        fun build(document: EvidenceDocumentModel, persistent: PersistentSceneAttribution): PlacementIndex {
            val placementsByType = LinkedHashMap<String, MutableList<ScenePlacement>>()
            for (obj in document.allObjects) {
                val placements = persistent.placementsOf(obj)
                // 한 오브젝트가 같은 타입을 두 번 일 수 있다(실측 2건 — CombineZone/Zone1 과 Zone2 가
                // 각각 Combat.UI.DropZone 를 둘씩 인다). 자리는 오브젝트 단위 값이라 그대로 담으면
                // 똑같은 값이 두 번 들어가고, 자리를 세는 쪽이 "두 군데에 있다"고 잘못 읽는다.
                for (type in obj.components.map { it.type }.distinct()) {
                    placementsByType.getOrPut(type) { mutableListOf() }.addAll(placements)
                }
            }
            return PlacementIndex(placementsByType)
        }
    }
}

/**
 * 씬 오브젝트를 자리 값으로 옮긴다.
 *
 * `wiring` 조인도 컨트롤이 놓인 자리를 같은 규칙으로 만든다. 변환이 두 벌이 되면 `selector-v1` 을
 * 약속하지 않은 문서에서 한쪽만 조준 경로를 잃는 식으로 조용히 갈린다.
 *
 * 씬을 바꾸는 것은 [PersistentSceneAttribution.placementsOf] 뿐이다. 이 변환은 문서가 적어 준 값을
 * 그대로 옮기기만 한다 — 자리를 만드는 곳과 자리를 옮기는 곳이 갈려 있어야 옮긴 근거가 한 군데에
 * 모인다.
 */
internal fun SceneObject.toPlacement(anchors: List<SceneAnchor>): ScenePlacement =
    ScenePlacement(scene = scene, path = path, selector = selector, label = label, anchors = anchors)
