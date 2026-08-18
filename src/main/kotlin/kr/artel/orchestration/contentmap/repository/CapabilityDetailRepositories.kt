package kr.artel.orchestration.contentmap.repository

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.CapabilityInferenceEntity
import kr.artel.orchestration.contentmap.entity.CapabilityObservationEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * evidence 출신 기능의 근거. `capability_id` 가 PK 라 [CoroutineCrudRepository] 의 id 가 그것이다.
 *
 * 방향이 둘인 불변식인데 강제 수단이 다르다.
 * - 관측 출신 기능에 근거를 붙이는 것은 **복합 FK 가 막는다**(`(capability_id, origin)` →
 *   `capability (id, origin)`)
 * - evidence 출신인데 근거가 없는 것은 선언으로 못 막는다(행이 두 INSERT 로 나뉜다).
 *   대신 `v_spec_gap` 이 `evidence-missing` 으로 세어 드러낸다 — 그것이 세어지면 고칠 곳은
 *   SDK 가 아니라 우리 적재기다
 */
interface CapabilityEvidenceRepository : CoroutineCrudRepository<CapabilityEvidenceEntity, Long> {

    fun findByEntryId(entryId: String): Flow<CapabilityEvidenceEntity>

    /**
     * 근거를 넣거나 갱신한다.
     *
     * `save()` 를 쓰지 못하는 이유: PK 를 우리가 정해서 주므로 Spring Data 가 이 행을 **기존 행으로
     * 보고 UPDATE 를 낸다.** 대상이 없으면 "Row with Id does not exist" 로 실패한다. 같은 모양인
     * `sdk_performance_run_summary` 도 명시 upsert 로 쓴다.
     *
     * 갱신인 이유: 재적재는 같은 기능의 근거를 새 스캔 것으로 덮어쓴다.
     */
    @Modifying
    @Query(
        """
        INSERT INTO capability_evidence (
            capability_id, origin, entry_id, owner_type, method, method_id,
            record_kind, trigger_kind, analysis_confidence,
            condition_tree, binding_event, binding_receiver, call_path, gaps
        ) VALUES (
            :capabilityId, 'evidence', :entryId, :ownerType, :method, :methodId,
            :recordKind, :triggerKind, :analysisConfidence,
            :conditionTree, :bindingEvent, :bindingReceiver, :callPath, :gaps
        )
        ON CONFLICT (capability_id) DO UPDATE SET
            entry_id = EXCLUDED.entry_id,
            owner_type = EXCLUDED.owner_type,
            method = EXCLUDED.method,
            method_id = EXCLUDED.method_id,
            record_kind = EXCLUDED.record_kind,
            trigger_kind = EXCLUDED.trigger_kind,
            analysis_confidence = EXCLUDED.analysis_confidence,
            condition_tree = EXCLUDED.condition_tree,
            binding_event = EXCLUDED.binding_event,
            binding_receiver = EXCLUDED.binding_receiver,
            call_path = EXCLUDED.call_path,
            gaps = EXCLUDED.gaps
        """
    )
    suspend fun upsert(
        capabilityId: Long,
        entryId: String,
        ownerType: String,
        method: String,
        methodId: String?,
        recordKind: String,
        triggerKind: String,
        analysisConfidence: String,
        conditionTree: Json,
        bindingEvent: String?,
        bindingReceiver: String?,
        callPath: Json,
        gaps: Json,
    ): Long
}

/** [CapabilityEvidenceRepository.upsert] 를 엔티티로 부르는 편의. */
suspend fun CapabilityEvidenceRepository.upsert(evidence: CapabilityEvidenceEntity): Long = upsert(
    capabilityId = evidence.capabilityId,
    entryId = evidence.entryId,
    ownerType = evidence.ownerType,
    method = evidence.method,
    methodId = evidence.methodId,
    recordKind = evidence.recordKind,
    triggerKind = evidence.triggerKind,
    analysisConfidence = evidence.analysisConfidence,
    conditionTree = evidence.conditionTree,
    bindingEvent = evidence.bindingEvent,
    bindingReceiver = evidence.bindingReceiver,
    callPath = evidence.callPath,
    gaps = evidence.gaps,
)

/** inferred 출신 기능의 근거. PK 를 직접 주므로 [upsert] 로 쓴다([CapabilityEvidenceRepository] 참고). */
interface CapabilityInferenceRepository : CoroutineCrudRepository<CapabilityInferenceEntity, Long> {

    @Modifying
    @Query(
        """
        INSERT INTO capability_inference (capability_id, model, prompt_version, rationale, based_on)
        VALUES (:capabilityId, :model, :promptVersion, :rationale, :basedOn)
        ON CONFLICT (capability_id) DO UPDATE SET
            model = EXCLUDED.model,
            prompt_version = EXCLUDED.prompt_version,
            rationale = EXCLUDED.rationale,
            based_on = EXCLUDED.based_on
        """
    )
    suspend fun upsert(
        capabilityId: Long,
        model: String,
        promptVersion: String?,
        rationale: String,
        basedOn: Json,
    ): Long
}

/** [CapabilityInferenceRepository.upsert] 를 엔티티로 부르는 편의. */
suspend fun CapabilityInferenceRepository.upsert(inference: CapabilityInferenceEntity): Long = upsert(
    capabilityId = inference.capabilityId,
    model = inference.model,
    promptVersion = inference.promptVersion,
    rationale = inference.rationale,
    basedOn = inference.basedOn,
)

/**
 * 효과(`then`). 기능 하나에 여러 개다.
 *
 * `category` 로 걸러 읽는 이유: `state` 는 결과로 쓰지 않는다. 이름으로 화면 변화를 짐작하면
 * 조용히 틀린다.
 */
interface CapabilityEffectRepository : CoroutineCrudRepository<CapabilityEffectEntity, Long> {

    fun findByCapabilityIdOrderByIdAsc(capabilityId: Long): Flow<CapabilityEffectEntity>

    fun findByCapabilityIdAndCategoryOrderByIdAsc(
        capabilityId: Long,
        category: String,
    ): Flow<CapabilityEffectEntity>

    /** 효과가 가리키는 값으로 되찾기. 관측이 어느 기능의 결과인지 맞출 때 쓴다. */
    fun findByTarget(target: String): Flow<CapabilityEffectEntity>
}

/** 관측. 조작 한 번이 한 행. */
interface CapabilityObservationRepository :
    CoroutineCrudRepository<CapabilityObservationEntity, Long> {

    fun findByCapabilityIdOrderByActedAtDesc(capabilityId: Long): Flow<CapabilityObservationEntity>

    fun findByQaRunIdOrderByActedAtAsc(qaRunId: Long): Flow<CapabilityObservationEntity>
}
