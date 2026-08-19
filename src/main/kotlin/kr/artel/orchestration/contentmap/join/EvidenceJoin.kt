package kr.artel.orchestration.contentmap.join

import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.evidence.EvidenceDocumentModel
import kr.artel.orchestration.contentmap.evidence.EvidenceRecord

/**
 * 근거 문서의 두 반쪽을 이어 기능 후보를 낸다. 조인 다섯 단계를 한자리에서 부르는 유일한 진입점이다.
 *
 * 각 단계는 자기 파일에 있고 여기는 **순서와 우선순위만** 정한다:
 *
 * ```
 * placement()  타입이 놓인 자리          PlacementIndex
 * wiring()     컨트롤 → 코드 (길 셋)     SceneWiringIndex
 * arrivals()   alsoReachedBy 펴기        SceneWiringIndex 안
 * spawning()   프리팹 위 타입의 주소      SpawnAttribution
 * reach        조건 갈래 + 조작 어휘      ConditionBranches · RecordTranslation
 * ```
 *
 * DB 도 Spring 도 없다. 적재(ARTEL-442)는 이 목록을 받아 행으로 바꾼다.
 */
class EvidenceJoin(private val document: EvidenceDocumentModel) {

    private val placements = PlacementIndex.build(document)
    private val wiring = SceneWiringIndex.build(document)
    private val spawns = SpawnAttribution(document, placements::placementsOf)

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
     * 1. **배선이 있으면 그 컨트롤이 주소다.** 무엇을 누르면 이 코드가 도는지를 문서가 직접 말한
     *    경우다. 컨트롤마다 후보가 따로 난다 — 같은 메서드라도 누르는 자리가 다르면 다른 스텝이다.
     * 2. **없으면 오너 타입이 놓인 씬으로 떨어진다.** 키보드 트리거가 이 길로 온다. 키 입력은
     *    컨트롤에 물리지 않아 배선이 구조적으로 없고, 대신 그 스크립트가 놓인 씬에서만 성립한다.
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
                placements.scenesOf(record.owner).flatMap { scene -> candidatesFor(record, scene, binding = null) }
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
                interaction = interaction.interaction.wire,
                inputKey = interaction.inputKey,
                inputPhase = interaction.inputPhase?.wire,
                condition = branch.condition,
                branchOffset = branch.branchOffset,
                gaps = gapsOf(record, branch, spawn),
            )
        }

    /**
     * 근거가 말한 공백에 조인이 판정한 공백을 더한다.
     *
     * 스폰 후보가 둘 이상이면 [EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS] 를 남긴다. 컬럼은 단일 값이라
     * 하나를 고르면 **고른 쪽이 근거가 없고**, 조용히 비면 "여럿이라 못 정했다"와 "원래 없다"가
     * 구분되지 않는다.
     */
    private fun gapsOf(record: EvidenceRecord, branch: ConditionBranch, spawn: SpawnOrigin?): List<String> {
        val fromRecord = RecordTranslation.gapsOf(record, branch)
        if (spawn?.ambiguous != true) return fromRecord
        return (fromRecord + EvidenceGap.SPAWN_ORIGIN_AMBIGUOUS.wire).distinct()
    }

    /**
     * 씬을 못 찾아 후보가 되지 못한 레코드 수.
     *
     * 0 이 목표가 아니다 — 죽은 코드와 아직 안 걸어 본 씬이 여기 섞여 있고, 이 수가 갑자기 늘면
     * 배선 조인이나 배치 색인이 깨진 것이다. 그때 알아채라고 세어 둔다.
     */
    fun unaddressedRecords(): Int =
        document.types.values.flatten().count { record ->
            wiring.bindingsFor(record).isEmpty() && placements.scenesOf(record.owner).isEmpty()
        }

    /** [SpawnAttribution.deadCodeCandidates] 를 그대로 낸다. 판정이 아니라 후보 목록이다. */
    fun deadCodeCandidates(): List<String> = spawns.deadCodeCandidates()

    private companion object {
        /** 스폰 후보의 고정 조작 값. 키도 phase 도 없다. */
        val NOT_A_STEP = BranchInteraction(Interaction.NONE, inputKey = null, inputPhase = null)
    }
}
