package kr.artel.orchestration.contentmap.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.Applicability
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.EffectCategory
import kr.artel.orchestration.contentmap.entity.EvidenceGap
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.RecordKind
import kr.artel.orchestration.contentmap.entity.TriggerKind
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.evidence.EvidenceEffect
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.join.CapabilityCandidate
import kr.artel.orchestration.contentmap.join.EvidenceJoin
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
import kr.artel.orchestration.contentmap.repository.CapabilityEvidenceRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.upsert
import kr.artel.orchestration.project.storage.DocumentStorage
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/**
 * 등록된 근거 문서를 읽어 **씬·기능 행으로 앉힌다.** 지금까지 끊겨 있던 자리다 — 등록(ARTEL-441)은
 * 문서 포인터만 만들고, 조인(ARTEL-485)은 후보를 메모리에만 낸다.
 *
 * ```
 * content_map_document (ingested_at IS NULL)
 *   → DocumentStorage.read → EvidenceParser → EvidenceJoin.candidates()
 *   → scene · capability · capability_evidence · capability_effect
 *   → ingested_by / ingested_at 도장
 * ```
 *
 * **문서 하나가 한 트랜잭션이다.** 중간에 죽으면 아무것도 남지 않아야 한다 — 절반만 upsert 된 상태에서
 * "이번 문서에 없는 기능"을 지우면, 아직 처리하지 못한 살아 있는 기능이 사라지고 그 CASCADE 로 관측까지
 * 날아간다. 도장도 같은 트랜잭션에서 찍어, 도장이 있으면 행이 다 있다는 뜻이 되게 한다.
 *
 * `content_map` 행 자체는 건드리지 않는다. 그것은 등록 경로가 소유한다 — 지문·유니티 버전·약속은
 * 문서가 올라온 시점의 사실이고, 적재기가 다시 쓰면 두 곳이 같은 값을 두고 다툰다.
 */
@Service
class ContentMapIngestService(
    private val documents: ContentMapDocumentRepository,
    private val scenes: SceneRepository,
    private val capabilities: CapabilityRepository,
    private val evidences: CapabilityEvidenceRepository,
    private val effects: CapabilityEffectRepository,
    private val storage: DocumentStorage,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ContentMapIngestService::class.java)

    /**
     * 아직 적재되지 않은 문서를 [limit] 개까지 적재한다.
     *
     * 한 문서가 실패해도 나머지를 계속한다. 한 게임의 문서가 깨졌다고 다른 게임의 적재가 멈추면,
     * 고치는 사람이 **깨진 문서를 찾기 전에** 큐가 밀린 것부터 보게 된다.
     *
     * [maxAttempts] 번을 채운 문서는 큐에서 빠진다. 행과 `last_error` 는 남아 조회할 수 있다.
     * 상한이 없으면 파싱에서 죽는 문서 하나가 매 tick 마다 스토리지에서 1.4 MB 를 다시 읽고,
     * `received_at ASC` 라 언제나 앞자리를 차지한다 — 영영 성공하지 못하면서 영영 재시도된다.
     *
     * 시도 횟수를 올리는 것은 [ContentMapDocumentRepository.claimPending] 이다. 여기가 아니라
     * 거기인 이유는 그쪽 주석에 있다.
     */
    suspend fun ingestPending(
        limit: Int = DEFAULT_BATCH,
        maxAttempts: Int = DEFAULT_MAX_ATTEMPTS,
    ): List<IngestResult> =
        documents.claimPending(limit, maxAttempts).toList().mapNotNull { document ->
            runCatching { ingest(document) }
                .onFailure { failure -> recordFailure(document, maxAttempts, failure) }
                .getOrNull()
        }

    /**
     * 실패를 장부에 적는다. 적재 트랜잭션 밖이라 롤백이 이것까지 되돌리지 않는다.
     *
     * 장부를 적다 실패해도 배치를 세우지 않는다. 사유를 남기지 못하는 것과 큐를 멈추는 것은
     * 서로 다른 크기의 사고다.
     */
    private suspend fun recordFailure(
        document: ContentMapDocumentEntity,
        maxAttempts: Int,
        failure: Throwable,
    ) {
        if (failure is CancellationException) throw failure

        logger.error("근거 문서 적재 실패 (documentId={}): {}", document.id, failure.message, failure)

        // 더하지 않는다. `claimPending` 의 `RETURNING` 은 UPDATE 뒤의 값을 돌려주므로 이 숫자에는
        // 이번 시도가 이미 들어 있다. 여기서 한 번 더 세면 경고가 한 시도 이르게 뜨고, 그 줄이
        // "5/5" 라고 적으면서 정작 문서는 한 번 더 집힌다.
        val attempts = document.ingestAttempts
        if (attempts >= maxAttempts) {
            // 조용히 큐에서 빠지면 아무도 모른다. 빠지는 순간이 그것을 말할 유일한 자리다.
            logger.warn(
                "근거 문서가 적재 시도 상한에 닿아 대기 목록에서 빠진다 (documentId={}, attempts={}/{}). " +
                    "last_error 와 함께 행은 남는다.",
                document.id, attempts, maxAttempts,
            )
        }

        runCatching { documents.recordFailure(document.id!!, describe(failure)) }
            .onFailure { logger.error("적재 실패 사유를 남기지 못했다 (documentId={}): {}", document.id, it.message, it) }
    }

    /**
     * 실패 사유를 컬럼에 앉을 크기로 줄인다.
     *
     * 파싱 오류는 문서 원문을 인용한다. 실측 문서가 1.4 MB 라 자르지 않으면 장부가 문서의
     * 사본이 되고, 큐를 들여다보는 사람이 읽으려던 한 줄이 그 안에 묻힌다. 원본은 스토리지에
     * 그대로 있으니 잘라도 잃는 것이 없다 — 컬럼 폭을 자르는 것과 같은 이유다.
     */
    private fun describe(failure: Throwable): String =
        "${failure::class.simpleName}: ${failure.message.orEmpty()}".take(ERROR_WIDTH)

    /**
     * 문서 하나를 적재한다. 성공하면 도장이 찍히고, 실패하면 트랜잭션째 되돌아간다.
     *
     * `@Transactional` 이 아니라 [TransactionalOperator] 인 이유: [ingestPending] 이 같은 클래스의
     * 이 함수를 부른다. 어노테이션은 프록시를 거쳐야 걸리는데 자기호출은 프록시를 지나지 않아,
     * **배치 경로에서만 트랜잭션이 조용히 사라진다.** 그 경로가 나중에 트리거가 붙을 유일한 입구다.
     */
    suspend fun ingest(document: ContentMapDocumentEntity): IngestResult =
        transactionalOperator.executeAndAwait { ingestInTransaction(document) }

    private suspend fun ingestInTransaction(document: ContentMapDocumentEntity): IngestResult {
        val bytes = storage.read(document.objectKey).awaitSingleOrNull()
            ?: throw NotFoundException("적재할 근거 문서를 스토리지에서 찾을 수 없습니다.")

        val model = EvidenceParser(objectMapper).parse(bytes.decodeToString())
        val candidates = EvidenceJoin(model).candidates()

        val sceneIds = upsertScenes(document.contentMapId, model.scenes + candidates.map { it.scene })

        // 키가 같은 후보는 **같은 명세의 다른 조각**이다. 실측 529 후보 중 38건이 그렇고, 씬·진입점·
        // 메서드·갈래·조건·조작이 전부 같은 채 `effects` 만 다르다(20그룹) 또는 `recordKind` 만
        // 다르다(8그룹). given 과 when 이 같으면 한 줄이고, then 은 그 줄에 함께 달린다 — 따로 쓰면
        // 같은 조작이 표에 두 번 나와 TC 가 같은 것을 두 번 시험한다.
        val grouped = candidates.groupBy { CapabilityKey.of(it) }
        val keptKeys = grouped.keys
        val collapsed = candidates.size - grouped.size

        for ((key, group) in grouped) {
            // 조작 후보가 흐름보다 먼저다. 같은 자리에서 `candidate` 와 `flow` 가 함께 나오면
            // 실행할 수 있는 쪽이 그 줄의 대표다.
            val candidate = group.firstOrNull { it.record.recordKind == RECORD_KIND_CANDIDATE } ?: group.first()
            val sceneId = sceneIds.getValue(candidate.scene)

            // 효과를 먼저 합친다. 관측 축이 대표 레코드의 효과만 보면, 효과만 다른 조각이 접힌 그룹에서
            // "행에는 관측 가능한 효과가 달려 있는데 축은 unknown" 인 줄이 나온다.
            val mergedEffects = group.flatMap { it.record.effects }
                .distinctBy { listOf(it.category, it.kind, it.target, it.detail, it.offset) }
            val row = CapabilityRow.of(candidate, mergedEffects)
            // 덮어쓰기 전에 근거가 달라졌는지 본다. upsert 뒤에는 옛 값이 없다.
            val previous = capabilities.findByContentMapIdAndCapabilityKey(document.contentMapId, key)
            val previousEvidence = previous?.id?.let { evidences.findById(it) }

            val capabilityId = capabilities.upsertByKey(
                sceneId = sceneId,
                contentMapId = document.contentMapId,
                capabilityKey = key,
                verification = VerificationState.UNVERIFIED.wire,
                summary = row.summary,
                givenText = row.givenText,
                controlSelector = row.controlSelector,
                controlPath = row.controlPath,
                controlLabel = row.controlLabel,
                spawnedByField = row.spawnedByField,
                spawnedByScenePath = row.spawnedByScenePath,
                interaction = row.interaction,
                inputKey = row.inputKey,
                inputPhase = row.inputPhase,
                actionability = row.actionability,
                observability = row.observability,
                applicability = Applicability.APPLIES.wire,
            )

            writeEvidence(capabilityId, candidate, group)
            writeEffects(capabilityId, mergedEffects)

            // 근거가 달라졌으면 확인을 되돌린다. 코드가 바뀌었는데 "확인됨"이 남아 있으면 그 표시가
            // 지금 코드에 대한 것이 아니게 되고, 아무도 그 사실을 모른다.
            if (previousEvidence != null && evidenceChanged(previousEvidence, evidences.findById(capabilityId))) {
                capabilities.resetVerification(capabilityId)
            }
        }

        val retired = retireVanished(document.contentMapId, keptKeys)

        documents.stampIngested(document.id!!, INGESTER_VERSION, Instant.now(clock))

        return IngestResult(
            documentId = document.id!!,
            contentMapId = document.contentMapId,
            scenes = sceneIds.size,
            capabilities = keptKeys.size,
            deleted = retired.deleted,
            markedNotApplicable = retired.markedNotApplicable,
            collapsed = collapsed,
        )
    }

    /**
     * 이 줄의 `given` 으로 실릴 조건 JSON.
     *
     * 쪼개지지 않은 갈래는 **문서 원문 그대로** 싣는다 — 타입 트리에서 되쓰면 우리가 못 담은 키가
     * 조용히 사라진다.
     *
     * 입력을 가르는 `either` 를 쪼갠 갈래는 원문을 실을 수 없다. 원문은 두 키를 모두 담고 있어,
     * `input_key` 가 하나인 줄의 `given` 이 "둘 중 아무거나"가 된다 — 쪼갠 이유가 그 자리에서 무너진다.
     * 그때만 타입 트리를 직렬화해 **이 갈래의 조건만** 싣는다.
     */
    private fun conditionJsonOf(candidate: CapabilityCandidate): String =
        if (candidate.condition == candidate.record.condition) {
            candidate.record.conditionJson
        } else {
            objectMapper.writeValueAsString(candidate.condition)
        }

    /**
     * 안정 키에서 메서드 이름만 뽑는다.
     *
     * 모양은 `Assembly|타입|메서드|반환형(인자형)` 이라 **셋째 칸**이다. 뒤에서 자르면 시그니처가 나와
     * `capability_evidence.method` 에 `System.Void()` 가 앉고, 그 값은 사람이 읽는 자리에도 그대로 실려
     * 나간다. 길이 제약에 안 걸려 조용히 틀린다.
     */
    private fun methodNameOf(methodId: String): String = methodNameFrom(methodId)

    /**
     * 근거가 실제로 달라졌나. 같은 기능이라도 조건·갈래 위치·확신도·되짚기가 바뀌면 다른 근거다.
     *
     * `capability_evidence` 를 보는 이유: 기능 행(`capability`)의 칸은 표시용이 많아, 라벨 한 글자만
     * 바뀌어도 확인이 버려진다. 판정의 근거는 근거 쪽에 있다.
     */
    private fun evidenceChanged(before: CapabilityEvidenceEntity, after: CapabilityEvidenceEntity?): Boolean {
        if (after == null) return true
        return before.conditionTree.asString() != after.conditionTree.asString() ||
            before.branchOffset != after.branchOffset ||
            before.analysisConfidence != after.analysisConfidence ||
            before.entryId != after.entryId ||
            before.methodId != after.methodId ||
            before.callPath.asString() != after.callPath.asString() ||
            before.gaps.asString() != after.gaps.asString()
    }

    /**
     * 씬 행을 이름으로 맞춘다.
     *
     * 이미 있으면 **그대로 둔다.** `walked` · `image_object_key` 는 QA 런이 쓴 값이라, 스캔이 다시
     * 돌았다고 "아직 안 걸어 본 씬"으로 되돌리면 커버리지가 매 스캔마다 0 이 된다.
     *
     * 후보의 씬도 함께 넣는다 — 문서의 `scenes` 목록에 없는 이름이 배치에서 나올 수 있고, 그때
     * 기능만 있고 씬이 없으면 FK 가 적재를 통째로 거절한다.
     */
    private suspend fun upsertScenes(contentMapId: Long, names: List<String>): Map<String, Long> =
        names.distinct().associateWith { name ->
            val existing = scenes.findByContentMapIdAndName(contentMapId, name)
            existing?.id ?: scenes.save(
                SceneEntity(contentMapId = contentMapId, name = name, scannedAt = Instant.now(clock))
            ).id!!
        }

    /**
     * 근거 행. 기능 한 줄에 한 벌이다.
     *
     * 되짚기 두 축이 비면 **사유를 여기서 채운다.** 조인은 문서가 말한 gap 만 싣고, DB 는
     * `ck_capability_evidence_call_path_or_gap` 으로 사유 없는 빈 축을 거절한다 — 조용히 비면
     * "적재기가 못 채운 것"과 "근거에 원래 없는 것"이 구분되지 않는다.
     */
    private suspend fun writeEvidence(
        capabilityId: Long,
        candidate: CapabilityCandidate,
        group: List<CapabilityCandidate>,
    ) {
        val record = candidate.record
        val gaps = buildList {
            // 접힌 조각들의 공백도 함께 싣는다. 한 조각만 "못 읽었다"고 말해도 그 줄은 단정할 수 없다.
            group.forEach { addAll(it.gaps) }
            if (record.methodId.isBlank()) add(EvidenceGap.METHOD_ID_MISSING.wire)
            if (record.callPath.isEmpty()) add(EvidenceGap.CALL_PATH_MISSING.wire)
        }.distinct()

        evidences.upsert(
            CapabilityEvidenceEntity(
                capabilityId = capabilityId,
                entryId = record.entryId,
                ownerType = record.owner,
                method = methodNameOf(record.methodId).take(METHOD_WIDTH),
                methodId = record.methodId.ifBlank { null },
                branchOffset = candidate.branchOffset,
                // 문서 어휘를 그대로 넣지 않는다. 모르는 값이면 CHECK 가 INSERT 를 거절하고, 그
                // 거절은 문서 하나를 통째로 되돌린 뒤 다음 tick 에 똑같이 되풀이된다.
                recordKind = RecordKind.from(record.recordKind)?.wire ?: RecordKind.FLOW.wire,
                triggerKind = TriggerKind.from(record.triggerKind)?.wire ?: TriggerKind.LIFECYCLE.wire,
                analysisConfidence = candidate.confidence.wire,
                conditionTree = Json.of(conditionJsonOf(candidate)),
                bindingEvent = candidate.binding?.event,
                bindingReceiver = candidate.binding?.placement?.path,
                callPath = Json.of(objectMapper.writeValueAsString(record.callPath)),
                gaps = Json.of(objectMapper.writeValueAsString(gaps)),
            )
        )
    }

    /**
     * 효과 행. 안정 키가 없어 **근거 출신만 지우고 다시 넣는다.**
     *
     * 관측이 남긴 효과(`origin='observed'`)는 건드리지 않는다. `watchable` 은 기본값 그대로 둔다 —
     * 무엇을 볼 수 있는지는 판독과 대조해야 알고, 그것은 ARTEL-452 다.
     */
    private suspend fun writeEffects(capabilityId: Long, merged: List<EvidenceEffect>) {
        effects.deleteEvidenceEffects(capabilityId)
        for (effect in merged) {
            if (EffectCategory.entries.none { it.wire == effect.category }) continue
            effects.save(
                CapabilityEffectEntity(
                    capabilityId = capabilityId,
                    category = effect.category,
                    // 문서 값이 컬럼 폭을 넘으면 그 한 줄이 문서 전체를 되돌린다. 잘라서 싣고,
                    // 잘렸다는 사실은 원본 문서가 스토리지에 그대로 있어 언제든 되찾을 수 있다.
                    kind = effect.kind.take(EFFECT_KIND_WIDTH),
                    target = effect.target?.take(EFFECT_TARGET_WIDTH),
                    detail = effect.detail?.take(EFFECT_DETAIL_WIDTH),
                    ilOffset = effect.offset,
                )
            )
        }
    }

    /**
     * 이번 문서에 없는 근거 출신 기능을 내린다.
     *
     * 참조가 없으면 지운다 — 재계산 가능한 파생물이다. 참조가 있으면 **남기고 적용 불가로 내린다.**
     * 지우면 `capability_observation` 이 CASCADE 로 함께 사라져, 코드에서 기능이 없어진 일이 QA 가
     * 눌러 본 기록까지 지우는 일이 된다.
     */
    private suspend fun retireVanished(contentMapId: Long, keptKeys: Set<String>): Retired {
        var deleted = 0
        var marked = 0
        for (existing in capabilities.findEvidenceCapabilitiesOfMap(contentMapId).toList()) {
            if (existing.capabilityKey in keptKeys) continue
            if (capabilities.hasRuntimeReferences(existing.id!!)) {
                // 이미 내려간 행은 0건을 돌려준다. 세는 것은 **이번에 내린 것**뿐이다.
                if (capabilities.markNotApplicable(existing.id) > 0) marked++
            } else {
                capabilities.deleteById(existing.id)
                deleted++
            }
        }
        return Retired(deleted, marked)
    }

    private data class Retired(val deleted: Int, val markedNotApplicable: Int)

    companion object {
        /**
         * 어느 판의 적재기가 이 문서를 처리했나. 적재 규칙을 고치면 올리고, 낡은 문서부터 다시 돌린다.
         */
        const val INGESTER_VERSION = "content-map-ingest-1"

        private const val DEFAULT_BATCH = 5

        /**
         * 한 문서를 몇 번까지 시도할지의 기본값. 스케줄러는 설정값을 넘긴다.
         *
         * `artel.knowledge.backfill.max-attempts` 와 같은 5다. 같은 종류의 판단이라 다른 숫자를
         * 고를 이유를 찾지 못했다.
         */
        private const val DEFAULT_MAX_ATTEMPTS = 5

        /** `last_error` 에 싣는 사유의 길이 상한. */
        private const val ERROR_WIDTH = 1000

        /** 조작 후보. `flow` 는 흐름만 말하는 조각이다. */
        private const val RECORD_KIND_CANDIDATE = "candidate"

        /**
         * 컬럼 폭. 문서 값이 이보다 길면 그 한 줄이 문서 전체를 되돌린다.
         *
         * 잘라서 싣는 것이 버리는 것이 아니다 — 원본 문서는 스토리지에 그대로 있고, 잘린 값은
         * 표시와 조인에만 쓰인다.
         */
        private const val METHOD_WIDTH = 255
        private const val EFFECT_KIND_WIDTH = 32
        private const val EFFECT_TARGET_WIDTH = 1024
        private const val EFFECT_DETAIL_WIDTH = 512
    }
}

/** 한 문서를 적재하고 남은 것. 워커가 로그로 남기고 테스트가 읽는다. */
data class IngestResult(
    val documentId: Long,
    val contentMapId: Long,
    val scenes: Int,
    val capabilities: Int,
    val deleted: Int,
    val markedNotApplicable: Int,
    /**
     * 같은 명세로 접힌 후보 수(후보 수 − 기능 행 수).
     *
     * 0 이 목표가 아니다 — 문서는 같은 조작의 효과를 여러 레코드로 나눠 말한다. 이 수가 갑자기 뛰면
     * 키 산식이 무언가를 잃기 시작한 것이다.
     */
    val collapsed: Int,
)

/**
 * 후보 하나를 `capability` 한 줄의 칸으로 옮긴다.
 *
 * 스키마가 강제하는 것을 **여기서 한 번에** 맞춘다. 후보의 다른 칸을 믿지 않는다 — V46 의
 * `ck_capability_spawn_has_no_control` 은 스폰 행에 조준 대상이 하나라도 남으면 INSERT 를 거절하고,
 * 그 거절은 문서 하나를 통째로 되돌린다.
 */
private data class CapabilityRow(
    val summary: String,
    val givenText: String?,
    val controlSelector: String?,
    val controlPath: String?,
    val controlLabel: String?,
    val spawnedByField: String?,
    val spawnedByScenePath: String?,
    val interaction: String,
    val inputKey: String?,
    val inputPhase: String?,
    val actionability: String,
    val observability: String,
) {
    companion object {
        fun of(candidate: CapabilityCandidate, mergedEffects: List<EvidenceEffect>): CapabilityRow {
            val spawned = candidate.spawn?.field != null
            return CapabilityRow(
                summary = summaryOf(candidate),
                givenText = null,
                // 스폰 행은 조준 대상을 갖지 않는다. 프리팹에는 씬 경로가 없고, 그것을 쥔 오브젝트의
                // 경로를 내주면 TC 가 만드는 쪽을 눌러 만들어지는 쪽을 확인했다고 말한다.
                controlSelector = if (spawned) null else candidate.binding?.placement?.selector,
                controlPath = if (spawned) null else candidate.binding?.placement?.path,
                controlLabel = if (spawned) null else candidate.binding?.placement?.label,
                spawnedByField = candidate.spawn?.field,
                spawnedByScenePath = candidate.spawn?.scenePath,
                interaction = if (spawned) Interaction.NONE.wire else candidate.interaction,
                inputKey = if (spawned) null else candidate.inputKey,
                inputPhase = if (spawned) null else candidate.inputPhase,
                actionability = actionabilityOf(candidate, spawned),
                observability = observabilityOf(mergedEffects),
            )
        }

        /**
         * 실행 축 첫 판정. 조작이 없으면 스텝이 될 수 없다.
         *
         * gap 이 남은 기능을 `needs-probe` 로 내리는 것은 **ARTEL-461 이 가져간다.** 여기서 먼저 내리면
         * 두 곳이 같은 축을 두고 다투고, 어느 쪽이 참인지 아무도 모르게 된다.
         */
        private fun actionabilityOf(candidate: CapabilityCandidate, spawned: Boolean): String =
            if (spawned || candidate.interaction == Interaction.NONE.wire) {
                Actionability.NOT_A_STEP.wire
            } else {
                Actionability.RUNNABLE.wire
            }

        /**
         * 관측 축 첫 판정. **ARTEL-452 가 판독과 대조해 덮어쓸 자리다.**
         *
         * 기본값(`unknown`)을 그대로 두면 V45 의 유도 규칙이 모든 행을 `needs-probe` 로 내려, 근거가
         * 멀쩡히 효과를 말한 기능까지 TC 가 실행하지 못한다. 근거가 관측 가능한 효과를 하나라도 말했으면
         * 일단 볼 수 있다고 적고, 판독이 아니라고 하면 그때 내린다.
         */
        private fun observabilityOf(mergedEffects: List<EvidenceEffect>): String {
            val watchableCategories = setOf(EffectCategory.OBSERVABLE.wire, EffectCategory.AVAILABILITY.wire)
            // 이 줄에 **실제로 실릴** 효과를 본다. 대표 레코드만 보면, 효과만 다른 조각이 접힌 그룹에서
            // 행에는 관측 가능한 효과가 달렸는데 축은 unknown 인 줄이 나온다.
            val hasVisibleEffect = mergedEffects.any { it.category in watchableCategories }
            return if (hasVisibleEffect) Observability.OBSERVABLE.wire else Observability.UNKNOWN.wire
        }

        /**
         * 식별자를 남긴 한 줄. 문장 생성은 ARTEL-447 몫이다.
         *
         * 말로 옮기지 않는다 — `MapMove.position` 을 "캐릭터가 옆으로 이동"으로 바꾸는 것이 이 시스템에서
         * 가장 비싼 거짓 명세다. 경로·타입·메서드를 원문 그대로 두고 사이만 잇는다.
         */
        private fun summaryOf(candidate: CapabilityCandidate): String {
            val method = methodNameFrom(candidate.record.methodId)
            val target = "`${candidate.record.owner}.$method()`"
            return when {
                candidate.spawn?.field != null -> "`${candidate.spawn.field}` 가 만드는 것 → $target"
                candidate.binding != null ->
                    "`${candidate.binding.placement.path}` ${candidate.interaction} → $target"
                candidate.inputKey != null -> "`${candidate.inputKey}` 키 → $target"
                else -> "$target (조작 없음)"
            }
        }
    }
}

/**
 * `Assembly|타입|메서드|반환형(인자형)` 의 **셋째 칸**. 못 읽으면 원본을 그대로 돌려준다.
 *
 * 적재기와 요약 문구가 같은 자리를 봐야 한다 — 한쪽만 고치면 근거 행의 메서드와 사람이 읽는 한 줄이
 * 서로 다른 것을 가리킨다.
 */
private fun methodNameFrom(methodId: String): String =
    methodId.split('|').getOrNull(2)?.takeIf { it.isNotBlank() } ?: methodId
