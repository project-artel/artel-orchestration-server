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
 * | 단계 | 어디 |
 * |---|---|
 * | 타입이 놓인 자리 | [PlacementIndex] |
 * | 컨트롤에서 코드로 가는 길 셋(`alsoReachedBy` 펴기 포함) | [SceneWiringIndex] |
 * | 프리팹 위에만 사는 타입의 주소 | [SpawnAttribution] |
 * | 조건 `branch` 쪼개기와 DB 어휘 번역 | [ConditionBranches] · [RecordTranslation] |
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
                confidence = RecordTranslation.confidenceOf(record.confidence),
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
     * 씬에 놓인 타입 중 주소를 못 찾은 레코드 수. **실측 0** — 놓인 타입은 `wiring`이 없어도 배치가 있다.
     *
     * 0 인데도 세는 이유: 이 수가 늘면 `wiring` 조인이나 배치 색인이 깨진 것이고, 그때 조용히 후보만
     * 줄어드는 것이 가장 알아채기 어려운 고장이다.
     */
    fun unaddressedRecords(): Int =
        document.types.values.flatten().count { record ->
            wiring.bindingsFor(record).isEmpty() && placements.scenesOf(record.owner).isEmpty()
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

    private companion object {
        /** 스폰 후보의 고정 조작 값. 키도 phase 도 없다. */
        val NOT_A_STEP = BranchInteraction(Interaction.NONE, inputKey = null, inputPhase = null)
    }
}
