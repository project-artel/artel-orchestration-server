package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.AnalysisConfidence
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord

/**
 * 근거 문서의 두 반쪽을 이어 기능 후보를 낸다. 조인 다섯 단계를 한자리에서 부르는 유일한 진입점이다.
 *
 * 각 단계는 자기 파일에 있고 여기는 **순서와 우선순위만** 정한다:
 *
 * | 단계 | 어디 |
 * |---|---|
 * | 타입이 놓인 자리 | [PlacementIndex] |
 * | 컨트롤에서 코드로 가는 길 셋(`alsoReachedBy` 펴기 포함) | [SceneWiringIndex] |
 * | 프리팹 위에만 사는 타입의 주소 | [SpawnAttribution] |
 * | `DontDestroyOnLoad` 에 있는 오브젝트의 실제 실행 `scene` | [PersistentSceneAttribution] |
 * | 조건 `branch` 쪼개기와 DB 어휘 번역 | [ConditionBranches] · [RecordTranslation] |
 *
 * DB 도 Spring 도 없다. 적재(ARTEL-442)는 이 목록을 받아 행으로 바꾼다.
 */
class EvidenceJoin(private val document: EvidenceDocumentModel) {

    // 다른 셋보다 먼저 선다. `scene` 이 아닌 이름(`DontDestroyOnLoad`)을 실제 실행 `scene` 으로 바꾸는 것이
    // 이것이고, 색인 셋은 전부 그 결과 위에서 자리를 만든다.
    private val persistent = PersistentSceneAttribution(document)
    private val placements = PlacementIndex.build(document, persistent)
    private val wiring = SceneWiringIndex.build(document, persistent)
    private val spawns = SpawnAttribution(document, placements::placementsOf, persistent::placementsOf)

    /**
     * 후보 전체. 문서 순서를 따른다 — 같은 문서를 두 번 읽으면 같은 순서가 나와야 재적재가
     * 결정론적이다.
     */
    fun candidates(): List<CapabilityCandidate> = placedCandidates() + spawnedCandidates()

    /**
     * 씬에 놓인 타입에서 나온 후보.
     *
     * 주소를 찾는 순서가 뜻을 정한다:
     *
     * 1. **`wiring`이 있으면 그 컨트롤이 주소다.** 무엇을 누르면 이 코드가 도는지를 문서가 직접 말한
     *    경우다. 컨트롤마다 후보가 따로 난다 — 같은 메서드라도 누르는 자리가 다르면 다른 스텝이다.
     * 2. **없으면 오너 타입이 놓인 씬으로 떨어진다.** 키보드 트리거가 이 길로 온다. 키 입력은
     *    컨트롤에 물리지 않아 `wiring`이 구조적으로 없고, 대신 그 스크립트가 놓인 씬에서만 성립한다.
     *
     * `wiring`이 있으면 **그 컨트롤이 있는 씬만** 낸다. 같은 스크립트가 다른 씬에도 놓여 있어도 거기서는
     * 누를 자리가 없기 때문이다 — 실측에서 `Core.SaveLoadController` 가 `TitleScene` 과 `Map_scene`
     * 양쪽에 놓여 있는데 `wiring`은 `TitleScene` 쪽뿐이라, `wiring`된 레코드는 `TitleScene` 만 내고 `wiring` 없는
     * 레코드(`Awake` 등)는 두 씬을 다 낸다. 자리 없는 씬까지 조작으로 내면 TC 가 없는 버튼을 누른다.
     *
     * 둘 다 못 찾은 레코드는 후보가 되지 않는다([unaddressedRecords] 가 센다). 씬을 모르면 명세의
     * `given` 자리가 통째로 비어 TC 가 어디서 시작해야 할지 말할 수 없다 — 아무 씬에나 붙이는 것이
     * 여기서 할 수 있는 가장 나쁜 선택이다.
     */
    private fun placedCandidates(): List<CapabilityCandidate> =
        document.types.values.flatten().flatMap { record ->
            val bindings = wiring.bindingsFor(record)
            if (bindings.isNotEmpty()) {
                bindings.flatMap { binding -> candidatesFor(record, binding.placement.scene, binding = binding) }
            } else {
                // `scenesOf` 가 아니라 자리 목록을 `scene` 으로 접는다. `scene` 이름만으로는 그 자리가 옮겨진
                // 것인지 문서가 적어 준 것인지 알 수 없어, 옮긴 근거를 후보까지 들고 갈 수 없다.
                placements.placementsOf(record.owner).distinctBy { it.scene }.flatMap { placement ->
                    candidatesFor(record, placement.scene, binding = null, anchors = placement.anchors)
                }
            }
        }

    /**
     * 프리팹 위에만 사는 타입에서 나온 후보.
     *
     * **조작이 아니므로 조작인 척하지 않는다.** [Interaction.NONE] 으로 고정하고 컨트롤을 비운다 —
     * 만드는 쪽의 경로를 조준 대상으로 내주면 TC 가 카드 매니저를 눌러 카드가 뒤집혔다고 적는다.
     * 스키마도 같은 것을 `ck_capability_spawn_has_no_control` 로 강제한다(ARTEL-484).
     *
     * 그래도 담는 이유는 `then` 쪽이다. 적이 공격할 때 무엇이 달라지는지를 판독으로 확인할 근거가
     * 이 111건 말고는 아예 없다.
     */
    private fun spawnedCandidates(): List<CapabilityCandidate> {
        val origins = spawns.attribute()
        return document.unplaced.entries.flatMap { (type, unplaced) ->
            origins[type].orEmpty().flatMap { origin ->
                unplaced.evidence.flatMap { record ->
                    candidatesFor(record, origin.scene, binding = null, spawn = origin)
                }
            }
        }
    }

    private fun candidatesFor(
        record: EvidenceRecord,
        scene: String,
        binding: ControlBinding?,
        spawn: SpawnOrigin? = null,
        anchors: List<SceneAnchor> = binding?.placement?.anchors ?: spawn?.anchors.orEmpty(),
    ): List<CapabilityCandidate> =
        ConditionBranches.from(record, scene).map { branch ->
            val interaction = if (spawn != null) {
                NOT_A_STEP
            } else {
                RecordTranslation.interactionOf(branch, binding)
            }
            CapabilityCandidate(
                scene = scene,
                record = record,
                binding = binding,
                spawn = spawn,
                confidence = confidenceOf(record, anchors),
                interaction = interaction.interaction.wire,
                inputKey = interaction.inputKey,
                inputPhase = interaction.inputPhase?.wire,
                condition = branch.condition,
                branchOffset = branch.branchOffset,
                gaps = gapsOf(record, branch, spawn, anchors),
                sceneAnchors = anchors,
            )
        }

    /**
     * 사슬 전체의 확실성은 **가장 흐린 단계**가 정한다(`capability_evidence.analysis_confidence` 의 정의).
     *
     * `scene` 귀속도 그 사슬의 한 단계다. 근거가 스스로 `verified` 라고 말했어도 그 기능이 앉을 자리를
     * 유도로 정했다면 결론은 유도이고, 자리를 하나로 좁히지 못했다면 결론은 `ambiguous` 다. 여기서
     * 내려 두지 않으면 "확정된 사실"과 "우리가 옮겨 놓은 사실"이 같은 등급으로 보인다.
     */
    private fun confidenceOf(record: EvidenceRecord, anchors: List<SceneAnchor>): AnalysisConfidence {
        val fromRecord = RecordTranslation.confidenceOf(record.confidence)
        if (anchors.isEmpty()) return fromRecord
        val fromAnchors = if (anchors.map { it.scene }.distinct().size > 1) {
            AnalysisConfidence.AMBIGUOUS
        } else {
            // 한 `scene` 을 받치는 anchor 가 여럿이면 **가장 확실한 것**이 그 `scene` 을 정한 것이다. 확실한
            // 근거가 하나 있으면 흐린 근거가 그것을 흐리게 만들지 않는다.
            anchors.minOf { it.rule.resolution }
        }
        return maxOf(fromRecord, fromAnchors)
    }

    /**
     * 근거가 말한 공백에 조인이 판정한 공백을 더한다. 조인이 더하는 것은 둘이고, 둘 다 "여럿이라
     * 하나로 못 정했다"는 사실이다.
     *
     * - [EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS] — 한 `scene` 에서 이 타입을 만드는 후보가 둘 이상이다.
     *   `spawned_by_field` 는 단일 컬럼이라 하나를 고르면 고른 쪽이 근거가 없다.
     * - [EvidenceGap.PERSISTENT_SCENE_AMBIGUOUS] — `DontDestroyOnLoad` 오브젝트의 실행 `scene` 이 둘
     *   이상이다. 여러 `scene` 에 싣되 그 사실을 함께 적는다.
     *
     * 조용히 비면 "여럿이라 못 정했다"와 "원래 없다"가 구분되지 않는다.
     */
    private fun gapsOf(
        record: EvidenceRecord,
        branch: ConditionBranch,
        spawn: SpawnOrigin?,
        anchors: List<SceneAnchor>,
    ): List<String> = buildList {
        addAll(RecordTranslation.gapsOf(record, branch))
        if (spawn?.ambiguous == true) add(EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS.wire)
        // `scene` 을 하나로 좁히지 못한 채 여러 `scene` 에 실은 경우. 조용히 여러 줄을 내면 "여기서도 되고
        // 저기서도 된다"와 "어디인지 모른다"가 표에서 같은 모양이 된다.
        if (anchors.map { it.scene }.distinct().size > 1) add(EvidenceGap.PERSISTENT_SCENE_AMBIGUOUS.wire)
    }.distinct()

    /**
     * 씬에 놓인 타입 중 주소를 못 찾은 레코드 수. **실측 0** — 놓인 타입은 `wiring`이 없어도 배치가 있다.
     *
     * 0 인데도 세는 이유: 이 수가 늘면 `wiring` 조인이나 배치 색인이 깨진 것이고, 그때 조용히 후보만
     * 줄어드는 것이 가장 알아채기 어려운 고장이다.
     *
     * `DontDestroyOnLoad` 위에만 사는 타입은 여기서 빠진다. 그쪽이 자리를 못 얻는 것은 조인이 깨진
     * 것이 아니라 `scene` 을 정할 근거가 없는 것이고, 고칠 곳이 달라서 [unattributedPersistentRecords] 가
     * 따로 센다. 한 통에 넣으면 이 수가 0 이 아닌 것이 정상인 날이 생겨 고장 신호가 죽는다.
     */
    fun unaddressedRecords(): Int =
        document.types.values.flatten().count { record ->
            !persistent.isPersistentOnly(record.owner) &&
                wiring.bindingsFor(record).isEmpty() &&
                placements.scenesOf(record.owner).isEmpty()
        }

    /**
     * 프리팹 위 타입 중 씬에 못 붙은 근거 수. **실측 17건** — 죽은 코드 후보 3타입의 16건과
     * `Cards.Util` 1건이다.
     *
     * [unaddressedRecords] 와 나누어 세는 이유: 저쪽이 늘면 조인이 깨진 것이고, 이쪽이 늘면 게임이
     * 프리팹으로 옮겨 갔거나 `createdBy` 추적이 짧아진 것이다. 고칠 곳이 서로 다르다.
     *
     * `Cards.Util` 이 여기 있는 것은 정상이다 — `calledBy` 가 차 있어 죽은 코드는 아니지만, 부르는
     * 쪽이 씬 어디에 있는지는 `createdBy` 없이 알 수 없다.
     */
    fun unattributedSpawnRecords(): Int {
        val attributed = spawns.attribute().keys
        return document.unplaced.filterKeys { it !in attributed }.values.sumOf { it.evidence.size }
    }

    /** [SpawnAttribution.deadCodeCandidates] 를 그대로 낸다. 판정이 아니라 후보 목록이다. */
    fun deadCodeCandidates(): List<String> = spawns.deadCodeCandidates()

    /**
     * `DontDestroyOnLoad` 에 있는 오브젝트 중 **실제 실행 `scene` 을 정하지 못한** root 경로들(ARTEL-460).
     *
     * 그 root 아래 타입의 근거는 후보가 되지 않는다 — `scene` 을 모르는 채 아무 `scene` 에나 붙이면 QA agent 가
     * 없는 컨트롤을 찾으러 가고, 그 실패는 지도가 아니라 게임이 깨진 것처럼 읽힌다. 비어 있는 것이
     * 정상이고, 차 있으면 그만큼이 지도의 공백이다.
     */
    fun unresolvedPersistentRoots(): List<String> = persistent.unresolvedRoots()

    /**
     * 그렇게 빠진 근거의 수. [unresolvedPersistentRoots] 와 나누어 세는 이유는 [unaddressedRecords] 를
     * 따로 세는 이유와 같다 — 몇 곳을 못 정했나와 그래서 몇 건을 잃었나는 다른 질문이고, 전자는
     * 규칙을 고칠 자리를, 후자는 지도가 얼마나 비었나를 말한다.
     */
    fun unattributedPersistentRecords(): Int {
        val unresolved = persistent.unresolvedRoots().toSet()
        if (unresolved.isEmpty()) return 0
        val lostTypes = document.persistentObjects
            .filter { it.path.substringBefore('/') in unresolved }
            .flatMap { obj -> obj.components.map { it.type } }
            .filter { persistent.isPersistentOnly(it) }
            .distinct()
        return lostTypes.sumOf { type ->
            document.types[type]?.size.orZero() + document.unplaced[type]?.evidence?.size.orZero()
        }
    }

    private fun Int?.orZero(): Int = this ?: 0

    private companion object {
        /** 스폰 후보의 고정 조작 값. 키도 phase 도 없다. */
        val NOT_A_STEP = BranchInteraction(Interaction.NONE, inputKey = null, inputPhase = null)
    }
}
