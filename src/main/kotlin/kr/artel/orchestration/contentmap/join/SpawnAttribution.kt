package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.evidence.CreatedBy
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.SceneObject

/**
 * 프리팹 위에만 사는 타입을 씬에 귀속시킨다.
 *
 * 이런 타입은 `objects[].components[]` 에 한 번도 나타나지 않아 `wiring` 조인이 구조적으로 0건을 낸다.
 * **죽은 코드라서가 아니라 주소가 없어서다** — 프리팹은 씬 계층 밖에 있고, 씬은 그것을 참조로만 든다.
 * 실측 `wv-editor-latest.json`(schema 6)에서 unplaced 14개 중 `createdBy` 가 찬 10개가 근거 레코드
 * 111건을 들고 있고, 그게 전투 씬의 대부분이다. 여기서 붙이지 않으면 그 111건이 통째로 사라진다.
 *
 * DB 도 Spring 도 없는 순수 계산이다. 입력은 파싱된 [EvidenceDocumentModel] 하나뿐이다.
 *
 * @param placementsOf 타입 풀네임 → 그 타입이 놓인 자리들. 클래스가 아니라 **함수 타입**으로 받는다 —
 *   배치 색인(`PlacementIndex`)과 이 계산은 서로의 내부를 알 필요가 없고, 함수로 끊어 두면 색인이
 *   바뀌어도 여기가 다시 컴파일되지 않으며 테스트가 색인 전체를 세우지 않고도 이 규칙만 검증한다.
 */
class SpawnAttribution(
    private val document: EvidenceDocumentModel,
    private val placementsOf: (String) -> List<ScenePlacement>,
    /** `scene` 오브젝트 하나가 실제로 서 있는 자리들. `DontDestroyOnLoad` 오브젝트의 `scene` 을 여기서 정한다(ARTEL-460). */
    private val placementsOfObject: (SceneObject) -> List<ScenePlacement>,
) {

    /**
     * `createdBy` 가 찬 unplaced 타입마다 붙을 자리를 낸다. 자리를 하나도 못 찾은 타입은 빠진다.
     *
     * 키 순서는 문서 순서, 값 순서는 씬을 처음 만난 순서다 — 같은 문서를 두 번 읽으면 같은 순서가
     * 나와야 재적재가 결정론적이다(`EvidenceParser` 와 같은 약속).
     */
    fun attribute(): Map<String, List<SpawnOrigin>> =
        document.unplaced
            .filterValues { it.createdBy.isNotEmpty() }
            .mapValues { (type, unplaced) -> originsOf(type, unplaced.createdBy) }
            .filterValues { it.isNotEmpty() }

    /**
     * `createdBy` 와 `calledBy` 가 **둘 다** 빈 타입.
     *
     * **판정이 아니라 후보 목록이다.** 캡처 한 장은 죽은 코드를 결정할 수 없다 — 근거는 그 캡처가
     * 걸어 본 경로만 담고, 실측에서 `Combat.Spells.SpellObj` 는 여기 오르지만 devbuild 캡처에서는
     * 살아 있다. 반대로 `Cards.Util` 은 `createdBy` 가 비었어도 `calledBy` 가 차 있어 후보가 아니다.
     * 호출자는 이 목록만 보고 아무것도 지우면 안 되고, 캡처를 더 모아 교집합을 봐야 한다.
     */
    fun deadCodeCandidates(): List<String> =
        document.unplaced
            .filterValues { it.createdBy.isEmpty() && it.calledBy.isEmpty() }
            .keys
            .toList()

    private fun originsOf(type: String, createdBy: List<CreatedBy>): List<SpawnOrigin> {
        val sites = createdBy.mapNotNull { parseCreatedBy(it.field) }
            .flatMap { sitesOf(type, it) }
            .distinct()
        return sites.groupBy { it.scene }.map { (scene, sceneSites) -> toOrigin(scene, sceneSites) }
    }

    /**
     * 한 씬 안에서 후보가 둘 이상이면 [SpawnOrigin.field] 를 비운다(ARTEL-484 가 스키마에 박은 계약).
     *
     * `field` 는 단일 값이라 둘 중 하나를 고르면 **고른 쪽이 근거가 없다.** 실측에서 `Cards.Card` ·
     * `Cards.Order` · `Combat.UI.DraggableCard` 는 `GameClearController` 의 `magicCard` 와 `spellCard`
     * 양쪽이 같은 프리팹 목록을 실어 GameClearScene 에서 정확히 이 상태가 된다. 호출자는 이때
     * `EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS`(`spawn-origin-ambiguous`) 를 남긴다 — 이 계산은 공백을
     * 판정만 하고 토큰은 후보를 조립하는 쪽이 붙인다.
     */
    private fun toOrigin(scene: String, sceneSites: List<SpawnSite>): SpawnOrigin {
        val candidates = sceneSites.map { it.createdBy }.distinct()
        val anchors = sceneSites.flatMap { it.anchors }.distinct()
        if (candidates.size > 1) {
            return SpawnOrigin(
                scene = scene,
                field = null,
                scenePath = null,
                ambiguousCandidates = candidates,
                anchors = anchors,
            )
        }
        // 같은 후보가 한 씬의 두 오브젝트에 실려 있으면 경로도 정할 수 없다. 하나일 때만 적는다.
        //
        // 그 경우 경로가 null 이 되어, 읽는 쪽에서는 "유도 경로라 경로가 없다"와 구분되지 않는다.
        // 실측에는 없는 사례라 사유 토큰을 새로 짓지 않았다 — 생기면 그때가 토큰을 만들 때다.
        return SpawnOrigin(
            scene = scene,
            field = candidates.single(),
            scenePath = sceneSites.mapNotNull { it.scenePath }.distinct().singleOrNull(),
            anchors = anchors,
        )
    }

    /**
     * 후보 하나가 가리키는 자리들. 두 길이 있고 **정밀한 쪽이 이긴다.**
     *
     * `carries` 는 그 참조가 실제로 이 타입을 싣고 있다고 문서가 말한 것이라 씬과 씬 경로를 함께 준다.
     * 그것이 잡히면 배치 유도로 내려가지 않는다 — 실측 `Scenes.GameClearController` 는 GameClearScene 과
     * GameOverScene 두 곳에 놓였지만 `magicCard` 참조는 GameClearScene 쪽에만 있다. 유도로 내려가면
     * 참조가 아예 없는 GameOverScene 까지 후보로 삼아 없는 사실을 지어낸다.
     */
    private fun sitesOf(type: String, ref: CreatedByRef): List<SpawnSite> {
        val carried = carriedSitesOf(type, ref)
        if (carried.isNotEmpty()) return carried
        return placementsOf(ref.ownerType)
            .map { SpawnSite(it.scene, scenePath = null, createdBy = ref.raw, anchors = it.anchors) }
    }

    /** 정밀한 길 — 소유 타입의 그 필드가 `carries` 로 이 타입을 실었다고 문서가 말한 자리. */
    private fun carriedSitesOf(type: String, ref: CreatedByRef): List<SpawnSite> =
        document.allObjects
            .filter { obj ->
                obj.components.any { component ->
                    component.type == ref.ownerType &&
                        component.refs.any { it.field == ref.field && type in it.carries }
                }
            }
            // 오브젝트의 `scene` 을 그대로 쓰지 않는다. 만드는 쪽이 `DontDestroyOnLoad` 에
            // 있으면 그 이름이 그대로 프리팹의 주소가 되고, 지도에 갈 수 없는 항목이 다시 생긴다.
            .flatMap { obj ->
                placementsOfObject(obj).map { placement ->
                    SpawnSite(
                        scene = placement.scene,
                        scenePath = placement.path,
                        createdBy = ref.raw,
                        anchors = placement.anchors,
                    )
                }
            }

    /**
     * 배치로 유도한 자리는 씬만 남기고 [SpawnOrigin.scenePath] 를 비운다.
     *
     * 프리팹에는 씬 경로가 없다. 여기에 만든 쪽(`CardSystem/CardManager`)의 경로를 적으면 테스트
     * 케이스가 **만드는 것을 눌러 놓고 만들어진 것을 확인했다**고 말하게 된다.
     */
    private data class SpawnSite(
        val scene: String,
        val scenePath: String?,
        val createdBy: String,
        /** 만드는 쪽이 `DontDestroyOnLoad` 에서 옮겨진 것이라면 그 근거. 대개 비어 있다. */
        val anchors: List<SceneAnchor> = emptyList(),
    )

    /** `"<OwnerType>.<field>"` 를 쪼갠 것. 소유 타입에도 점이 있으므로 **마지막 점**에서 가른다. */
    private data class CreatedByRef(val raw: String, val ownerType: String, val field: String)

    /** 점이 없거나 한쪽이 비면 소유 타입도 필드도 정할 수 없다. 반쪽을 상상하지 않고 버린다. */
    private fun parseCreatedBy(raw: String): CreatedByRef? {
        val cut = raw.lastIndexOf('.')
        if (cut <= 0 || cut == raw.length - 1) return null
        return CreatedByRef(raw = raw, ownerType = raw.substring(0, cut), field = raw.substring(cut + 1))
    }
}
