package kr.artel.orchestration.contentmap.observe

import io.r2dbc.postgresql.codec.Json
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.Applicability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityInferenceEntity
import kr.artel.orchestration.contentmap.entity.CapabilityObservationEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.InputPhase
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.ObservationSource
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.repository.CapabilityInferenceRepository
import kr.artel.orchestration.contentmap.repository.upsert
import kr.artel.orchestration.contentmap.repository.CapabilityObservationRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/**
 * agent 가 적을 수 있는 `origin`. `evidence` 와 `human` 은 받지 않는다 — agent 가 정적 분석의
 * 옷을 입은 행을 만들면 "이 행이 어디서 왔나" 가 답할 수 없는 질문이 된다.
 */
private val WRITABLE_ORIGINS = setOf(CapabilityOrigin.OBSERVED, CapabilityOrigin.INFERRED)

/** `qa_try.model` 이 비어 있는 런. `capability_inference.model` 이 NOT NULL 이라 자리를 채워야 한다. */
private const val UNKNOWN_MODEL = "unknown"

/** capture 를 실었다고 주장할 때 그 frame 이 있어야 하는 방향과 타입. */
private const val CAPTURE_DIRECTION = "SDK_TO_ORCHE"
private const val CAPTURE_TYPE = "SCREENSHOT"

/**
 * QA agent 가 본 것을 지도에 적는 쓰기 경로(ARTEL-644).
 *
 * 실측이 이 서비스를 부른다. capability 472 행 중 `verification = 'confirmed'` 이 2 행이고,
 * `interaction = 'none'` 인 418 행은 누르는 대상이 아니라 action 전후의 `pulse` 를 비교하는
 * 방식으로는 영영 볼 수 없다(ARTEL-450 을 백로그로 내린 이유). 일어났는지는 `screen` 을 본
 * 쪽이 안다.
 *
 * ## 이 서비스가 지는 네 가지
 *
 * 1. **agent 가 정적 분석을 덮지 않는다.** `capability` 를 고치는 문장은
 *    [CapabilityRepository.recordVerification] 하나뿐이고 그 SET 절에는 `verification` 과 그
 *    포인터밖에 없다. 새 행을 넣는 경로는 `origin` 을 observed · inferred 로만 받는다.
 * 2. **verdict 만 받지 않는다.** rationale 이 비면 거절한다. DB CHECK 가 같은 것을 한 번 더 막는다.
 * 3. **멱등.** 같은 문장이 두 번 와도 행이 둘이 되지 않고, 그 보장은 유니크 인덱스 둘이 진다.
 * 4. **거절은 값으로 돌아온다.** throw 하지 않는다 — 라우터가 그것을 ERROR frame 으로 agent 에게
 *    돌려주고, 예외로 올리면 receive 체인이 끊겨 프레임 하나가 런 전체를 죽인다.
 *
 * frame 하나가 한 트랜잭션이다. 런이 중간에 끊겨도 그때까지 적은 것이 남는다.
 */
@Service
class AgentCapabilityWriteService(
    private val gameInstances: GameInstanceRepository,
    private val contentMaps: ContentMapRepository,
    private val scenes: SceneRepository,
    private val screens: ScreenRepository,
    private val capabilities: CapabilityRepository,
    private val observations: CapabilityObservationRepository,
    private val inferences: CapabilityInferenceRepository,
    private val qaLogs: QaLogRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {

    /**
     * "이 capability 가 되더라 / 안 되더라" 를 적는다.
     *
     * `verification` 을 agent 의 말대로 그대로 옮긴다. observation 이 몇 개 쌓이면 올린다는 규칙은
     * 여기 없다 — 그것은 ARTEL-646 이고, 여기는 agent 가 말한 것을 적는 데까지다.
     */
    suspend fun recordVerdict(
        qaTry: QaTryEntity,
        messageId: String,
        request: CapabilityVerdictRequest,
    ): CapabilityWrite {
        val verdict = CapabilityVerdict.from(request.verdict)
            ?: return refuse(
                CapabilityWriteFrames.VERDICT,
                "payload.verdict must be one of ${CapabilityVerdict.NAMES}: ${request.verdict}"
            )
        val rationale = when (val step = validRationale(CapabilityWriteFrames.VERDICT, request.rationale)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val target = when (val step = resolve(qaTry, CapabilityWriteFrames.VERDICT, request.scene)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }

        val hasKey = !request.capabilityKey.isNullOrBlank()
        val hasId = !request.capabilityId.isNullOrBlank()
        if (hasKey == hasId) {
            return refuse(
                CapabilityWriteFrames.VERDICT,
                "needs exactly one of capability_key or capability_id"
            )
        }
        val capability = if (hasKey) {
            capabilities.findByContentMapIdAndCapabilityKey(target.contentMapId, request.capabilityKey!!)
                ?: return refuse(
                    CapabilityWriteFrames.VERDICT,
                    "references an unknown capability_key: ${request.capabilityKey}"
                )
        } else {
            val id = request.capabilityId!!.trim().toLongOrNull()
                ?: return refuse(
                    CapabilityWriteFrames.VERDICT,
                    "payload.capability_id must be a numeric id: ${request.capabilityId}"
                )
            capabilities.findById(id)
                ?: return refuse(CapabilityWriteFrames.VERDICT, "references an unknown capability_id: $id")
        }
        // 접힌 행에는 적지 않는다. `merged_into` 가 찍힌 행은 어느 읽기 창구에도 나오지 않으므로,
        // 여기에 남긴 verdict 는 아무도 다시 보지 못한다. 어디로 접혔는지를 함께 돌려준다.
        capability.mergedInto?.let { into ->
            return refuse(
                CapabilityWriteFrames.VERDICT,
                "capability ${capability.id} has been merged into $into — send the verdict there"
            )
        }
        // 거절 규칙의 축. agent 가 서 있지 않은 `scene` 의 capability 에 verdict 를 찍으면 그
        // verdict 는 이 런이 실제로 본 것이 아니다. 조용히 버리지 않고 어느 `scene` 의 것인지
        // 함께 돌려준다 — agent 가 `scene` 을 잘못 적었는지 대상을 잘못 골랐는지 갈리기 때문이다.
        if (capability.sceneId != target.sceneId) {
            val owner = scenes.findById(capability.sceneId)?.name ?: "(unknown)"
            return refuse(
                CapabilityWriteFrames.VERDICT,
                "capability ${capability.id} belongs to scene $owner, not ${target.sceneName}"
            )
        }

        val screenId = when (val step = resolveScreen(CapabilityWriteFrames.VERDICT, target.sceneId, request.screenId)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val captureId = when (val step = validCapture(CapabilityWriteFrames.VERDICT, qaTry, request.captureId)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }

        return applyVerdict(
            type = CapabilityWriteFrames.VERDICT,
            qaTry = qaTry,
            messageId = messageId,
            capability = capability,
            sceneId = target.sceneId,
            qaRunId = target.qaRunId,
            screenId = screenId,
            verdict = verdict,
            rationale = rationale,
            captureId = captureId,
            action = request.action,
            created = false,
        )
    }

    /**
     * `evidence` 에 없던 capability 를 적는다.
     *
     * 이미 있는 행을 찾으면 그 행을 그대로 돌려준다(`created = false`). 재전송을 흡수하는 것이
     * 목적이고, 찾은 행의 어느 칸도 고치지 않는다 — agent 가 두 번째 문장으로 첫 문장을 덮을 수
     * 있으면 "지우거나 고칠 수 없다" 가 뒷문으로 무너진다.
     */
    suspend fun recordDiscovery(
        qaTry: QaTryEntity,
        messageId: String,
        request: CapabilityDiscoveredRequest,
    ): CapabilityWrite {
        val type = CapabilityWriteFrames.DISCOVERED
        val origin = CapabilityOrigin.from(request.origin)?.takeIf { it in WRITABLE_ORIGINS }
            ?: return refuse(
                type,
                "payload.origin must be one of [${CapabilityOrigin.OBSERVED.wire}, " +
                    "${CapabilityOrigin.INFERRED.wire}]: ${request.origin}"
            )
        val summary = request.summary?.trim()
        if (summary.isNullOrEmpty()) return refuse(type, "payload.summary is required")
        if (summary.length > CapabilityWriteFrames.MAX_SUMMARY_LENGTH) {
            return refuse(type, "payload.summary is longer than ${CapabilityWriteFrames.MAX_SUMMARY_LENGTH} characters")
        }
        val rationale = when (val step = validRationale(type, request.rationale)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val interaction = Interaction.from(request.interaction)
            ?: return refuse(
                type,
                "payload.interaction must be one of ${Interaction.entries.map { it.wire }}: ${request.interaction}"
            )
        // `ck_capability_press_needs_key` 를 여기서 한 번 더 본다. DB 가 막아 주기는 하지만 그
        // 실패는 제약 이름이 실린 예외라 agent 가 무엇을 고쳐야 하는지 읽을 수 없다.
        val inputKey = request.inputKey?.trim()?.takeIf { it.isNotEmpty() }
        if ((interaction == Interaction.PRESS) != (inputKey != null)) {
            return refuse(
                type,
                "interaction ${Interaction.PRESS.wire} requires input_key, and no other interaction may carry one"
            )
        }
        val inputPhase = request.inputPhase?.trim()?.takeIf { it.isNotEmpty() }
        if (inputPhase != null && InputPhase.from(inputPhase) == null) {
            return refuse(
                type,
                "payload.input_phase must be one of ${InputPhase.entries.map { it.wire }}: $inputPhase"
            )
        }
        val verdict = request.verdict?.let {
            CapabilityVerdict.from(it)
                ?: return refuse(type, "payload.verdict must be one of ${CapabilityVerdict.NAMES}: $it")
        }
        if (origin == CapabilityOrigin.INFERRED && verdict != null) {
            return refuse(
                type,
                "origin ${CapabilityOrigin.INFERRED.wire} cannot carry a verdict — " +
                    "an inference is not a sighting"
            )
        }
        // `observed` 는 눌러 보고 결과까지 본 것을 뜻한다(`CapabilityOrigin.OBSERVED` 의 KDoc).
        // verdict 를 요구하는 것이 그 뜻을 지키는 일이자, rationale 이 반드시 어딘가에 앉게 하는
        // 유일한 길이다 — observation 행은 verdict 없이 설 수 없고(CHECK), `capability_inference`
        // 는 `inferred` 전용이라, verdict 가 없으면 rationale 을 적을 자리가 없다.
        if (origin == CapabilityOrigin.OBSERVED && verdict == null) {
            return refuse(
                type,
                "origin ${CapabilityOrigin.OBSERVED.wire} requires a verdict — " +
                    "if you did not watch the result, write it as ${CapabilityOrigin.INFERRED.wire}"
            )
        }

        val target = when (val step = resolve(qaTry, type, request.scene)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val screenId = when (val step = resolveScreen(type, target.sceneId, request.screenId)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val captureId = when (val step = validCapture(type, qaTry, request.captureId)) {
            is Step.No -> return CapabilityWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val basedOn = if (origin == CapabilityOrigin.INFERRED) {
            when (val step = validBasedOn(type, target.qaRunId, request.basedOn)) {
                is Step.No -> return CapabilityWrite.Rejected(step.reason)
                is Step.Ok -> step.value
            }
        } else {
            emptyList()
        }

        val controlPath = request.controlPath?.trim()?.takeIf { it.isNotEmpty() }
        val existing = capabilities.findAgentStatement(
            sceneId = target.sceneId,
            interaction = interaction.wire,
            controlPath = controlPath,
            summary = summary,
        )
        if (existing != null) {
            // 찾은 행의 어느 칸도 고치지 않는다 — agent 가 두 번째 문장으로 첫 문장을 덮을 수
            // 있으면 "지우거나 고칠 수 없다" 가 뒷문으로 무너진다. **verification 은 예외다.**
            // 먼저 `inferred` 로 적어 둔 것을 나중에 실제로 보는 경우가 이 자리이고, 그때 origin 은
            // `inferred` 로 남고 verification 만 올라간다 — 축이 둘인 설계가 그 경우를 위한 것이다.
            val existingId = existing.id!!
            if (verdict == null) {
                return CapabilityWrite.Accepted(
                    type = type,
                    capabilityId = existingId,
                    capabilityKey = existing.capabilityKey,
                    sceneId = target.sceneId,
                    verification = existing.verification,
                    observationId = null,
                    created = false,
                )
            }
            return applyVerdict(
                type = type,
                qaTry = qaTry,
                messageId = messageId,
                capability = existing,
                sceneId = target.sceneId,
                qaRunId = target.qaRunId,
                screenId = screenId,
                verdict = verdict,
                rationale = rationale,
                captureId = captureId,
                action = request.action,
                created = false,
            )
        }

        return try {
            transactionalOperator.executeAndAwait {
                val saved = capabilities.save(
                    CapabilityEntity(
                        sceneId = target.sceneId,
                        contentMapId = target.contentMapId,
                        // observed · inferred 출신은 `entry_id` 도 `branch_offset` 도 없어 산식의
                        // 입력이 없다. 더미값을 넣으면 그 순간 키가 키가 아니게 된다.
                        capabilityKey = null,
                        origin = origin.wire,
                        summary = summary,
                        givenText = request.givenText?.trim()?.takeIf { it.isNotEmpty() },
                        controlPath = controlPath,
                        controlLabel = request.controlLabel?.trim()?.takeIf { it.isNotEmpty() },
                        interaction = interaction.wire,
                        inputKey = inputKey,
                        inputPhase = inputPhase,
                        actionability = actionabilityOf(interaction).wire,
                        // agent 는 효과를 적지 않는다. 무엇이 달라지는지는 `capability_effect` 의
                        // 몫이고 그쪽은 ARTEL-646 이라, 여기서 observable 이라고 말하면 근거 없이
                        // TC 창구를 통과하는 행이 생긴다.
                        observability = Observability.UNKNOWN.wire,
                        applicability = Applicability.APPLIES.wire,
                    )
                )
                val capabilityId = saved.id!!
                if (origin == CapabilityOrigin.INFERRED) {
                    // `save()` 를 쓰지 않는다. PK 가 `capability_id` 라 값이 이미 채워져 있고,
                    // Spring Data 는 그것을 "새 행이 아니다" 로 읽어 0 행짜리 UPDATE 를 낸다.
                    inferences.upsert(
                        CapabilityInferenceEntity(
                            capabilityId = capabilityId,
                            // agent 가 자기 모델 이름을 싣게 하지 않는다. 무엇으로 돌았는지는 세션
                            // 개설이 확정한 `qa_try` 의 값이 진실이다.
                            model = qaTry.model ?: UNKNOWN_MODEL,
                            promptVersion = qaTry.promptVersion,
                            rationale = rationale,
                            basedOn = Json.of(objectMapper.writeValueAsString(basedOn)),
                        )
                    )
                }
                var verification = VerificationState.UNVERIFIED.wire
                var observationId: Long? = null
                if (verdict != null) {
                    observationId = writeStatement(
                        qaTry = qaTry,
                        messageId = messageId,
                        capabilityId = capabilityId,
                        screenId = screenId,
                        verdict = verdict,
                        rationale = rationale,
                        captureId = captureId,
                        action = request.action,
                        qaRunId = target.qaRunId,
                    )
                    verification = verificationOf(verdict).wire
                    capabilities.recordVerification(capabilityId, verification, observationId)
                }
                CapabilityWrite.Accepted(
                    type = type,
                    capabilityId = capabilityId,
                    capabilityKey = null,
                    sceneId = target.sceneId,
                    verification = verification,
                    observationId = observationId,
                    created = true,
                )
            }
        } catch (conflict: DataIntegrityViolationException) {
            // 위의 조회와 이 INSERT 사이에 같은 발견이 들어왔다. **복구는 트랜잭션 밖에서 한다** —
            // Postgres 는 실패한 문장 뒤의 트랜잭션을 통째로 막으므로, 안에서 다시 조회하면 그
            // 조회마저 죽는다. 여기는 롤백이 끝난 뒤라 새 조회가 선다.
            val raced = capabilities.findAgentStatement(target.sceneId, interaction.wire, controlPath, summary)
                ?: throw conflict
            CapabilityWrite.Accepted(
                type = type,
                capabilityId = raced.id!!,
                capabilityKey = raced.capabilityKey,
                sceneId = target.sceneId,
                verification = raced.verification,
                observationId = null,
                created = false,
            )
        }
    }

    /**
     * 문장 한 행을 남기고 그 verdict 를 `verification` 으로 옮긴다. 둘이 한 트랜잭션이다.
     *
     * `CAPABILITY_VERDICT` 와, verdict 를 함께 실은 `CAPABILITY_DISCOVERED` 가 같은 것을 쓰므로
     * 이 하나를 나눠 쓴다. 두 벌로 두면 한쪽만 고쳐져 "발견으로 적었을 때와 판정으로 적었을 때가
     * 다르다" 는 상태가 생긴다.
     */
    private suspend fun applyVerdict(
        type: String,
        qaTry: QaTryEntity,
        messageId: String,
        capability: CapabilityEntity,
        sceneId: Long,
        qaRunId: Long,
        screenId: Long?,
        verdict: CapabilityVerdict,
        rationale: String,
        captureId: String?,
        action: CapabilityActionRecord?,
        created: Boolean,
    ): CapabilityWrite.Accepted {
        val capabilityId = requireNotNull(capability.id) { "capability 가 저장되지 않았다" }
        val accepted = CapabilityWrite.Accepted(
            type = type,
            capabilityId = capabilityId,
            capabilityKey = capability.capabilityKey,
            sceneId = sceneId,
            verification = verificationOf(verdict).wire,
            observationId = null,
            created = created,
        )
        return try {
            transactionalOperator.executeAndAwait {
                val observationId = writeStatement(
                    qaTry = qaTry,
                    messageId = messageId,
                    capabilityId = capabilityId,
                    screenId = screenId,
                    verdict = verdict,
                    rationale = rationale,
                    captureId = captureId,
                    action = action,
                    qaRunId = qaRunId,
                )
                capabilities.recordVerification(capabilityId, accepted.verification, observationId)
                // 한 번의 조작은 그 컨트롤의 형제 행 전부에 대한 관측이다. 지목된 행만 움직이면
                // 같은 조작을 겪은 나머지가 `unverified` 로 남는다(ARTEL-805).
                capabilities.recordVerificationOfSiblings(
                    capabilityId,
                    accepted.verification,
                    observationId,
                )
                accepted.copy(observationId = observationId)
            }
        } catch (conflict: DataIntegrityViolationException) {
            // 같은 문장이 동시에 둘 오면 유니크가 하나를 떨군다. **복구는 트랜잭션 밖에서 한다** —
            // Postgres 는 실패한 문장 뒤의 트랜잭션을 통째로 막으므로, 안에서 다시 조회하면 그
            // 조회마저 죽는다. 여기는 롤백이 끝난 뒤라 새 조회가 선다.
            val raced = observations.findAgentStatement(qaRunId, capabilityId, verdict.wire)
                ?: throw conflict
            accepted.copy(observationId = raced.id)
        }
    }

    /**
     * agent 문장 한 행을 넣거나, 이미 있으면 그 행의 id 를 돌려준다.
     *
     * 조회 후 INSERT 라 경합에서 유니크에 걸릴 수 있고, 그때 다시 조회해 원래 행으로 되돌린다 —
     * `IssueService` 가 재전송 frame 을 흡수하는 것과 같은 모양이다.
     *
     * 이미 있는 행의 rationale 을 덮지 않는다. 두 번째 문장이 첫 문장을 덮을 수 있으면 "무엇을
     * 보고 그렇게 말했나" 의 첫 답이 사라진다.
     */
    private suspend fun writeStatement(
        qaTry: QaTryEntity,
        messageId: String,
        capabilityId: Long,
        screenId: Long?,
        verdict: CapabilityVerdict,
        rationale: String,
        captureId: String?,
        action: CapabilityActionRecord?,
        qaRunId: Long,
    ): Long {
        observations.findAgentStatement(qaRunId, capabilityId, verdict.wire)?.let { return it.id!! }
        val row = CapabilityObservationEntity(
            capabilityId = capabilityId,
            qaRunId = qaRunId,
            screenId = screenId,
            source = ObservationSource.AGENT.wire,
            // agent 가 실은 시각이 아니라 서버 시계다. 시계가 어긋난 agent 하나가 런의 시간축을
            // 흔들 수 있고, frame 이 도착한 시각과 몇 밀리초 차이는 이 표가 답하는 질문을 바꾸지 않는다.
            actedAt = Instant.now(clock),
            actionMethod = action?.method?.trim()?.takeIf { it.isNotEmpty() },
            actionParams = Json.of(objectMapper.writeValueAsString(action?.params ?: objectMapper.createObjectNode())),
            attempts = action?.attempts?.takeIf { it >= 1 } ?: 1,
            // `fired` 는 "`pulse` 값이 달라졌나" 라는 측정이라 agent 가 답하는 질문이 아니다.
            fired = null,
            verdict = verdict.wire,
            rationale = rationale,
            captureId = captureId,
            qaTryId = qaTry.id,
            agentMessageId = messageId,
        )
        // 유니크에 걸리면 여기서 잡지 않는다. 트랜잭션 안이라 실패한 문장 뒤의 조회가 함께 막히기
        // 때문이고, 복구는 트랜잭션을 벗어난 호출부가 한다.
        return observations.save(row).id!!
    }

    /** 런에서 지도를 찾고 agent 가 말한 `scene` 을 해석한다. 셋 중 하나라도 없으면 거절이다. */
    private suspend fun resolve(qaTry: QaTryEntity, type: String, scene: String?): Step<ResolvedScene> {
        val sceneName = scene?.trim()
        if (sceneName.isNullOrEmpty()) return reject(type, "payload.scene is required")
        // `capability_observation.qa_run_id` 가 NOT NULL 이다. 런에 속하지 않은 try 는 하위호환용
        // 단독 실행이고, 그 실행에는 문장을 걸 런이 없다.
        val qaRunId = qaTry.qaRunId ?: return reject(type, "this try does not belong to a qa_run")
        val instance = gameInstances.findById(qaTry.gameInstanceId)
            ?: return reject(type, "cannot resolve the game instance of this run")
        val buildId = instance.lastGameBuildId
            ?: return reject(type, "this game instance has no build to attach a capability to")
        // 빌드마다 지도가 하나다(ARTEL-642). 고를 것이 없다.
        val contentMapId = contentMaps.findByGameBuildId(buildId)?.id
            ?: return reject(type, "this game build has no content map yet")
        val sceneRow = scenes.findByContentMapIdAndName(contentMapId, sceneName)
            ?: return reject(type, "references an unknown scene: $sceneName")
        return Step.Ok(
            ResolvedScene(
                contentMapId = contentMapId,
                sceneId = sceneRow.id!!,
                sceneName = sceneName,
                qaRunId = qaRunId,
            )
        )
    }

    /**
     * agent 가 `screen` 을 지목했으면 그것이 이 `scene` 의 것인지 본다.
     *
     * 지목하지 않는 것이 지금의 정상이다. `screen` id 를 agent 에게 알리는 것은 ARTEL-668 이라,
     * 그 전까지 이 칸은 비어 있다. 미리 받아 두는 이유는 그쪽이 붙을 때 계약을 다시 열지 않기
     * 위해서다.
     */
    private suspend fun resolveScreen(type: String, sceneId: Long, screenId: String?): Step<Long?> {
        val raw = screenId?.trim()?.takeIf { it.isNotEmpty() } ?: return Step.Ok(null)
        val id = raw.toLongOrNull()
            ?: return reject(type, "payload.screen_id must be a numeric id: $raw")
        val screen = screens.findById(id)
            ?: return reject(type, "references an unknown screen_id: $id")
        if (screen.sceneId != sceneId) return reject(type, "screen $id is not in the scene this frame names")
        return Step.Ok(id)
    }

    /**
     * 캡처를 실었으면 그 캡처가 이 try 의 것인지 본다.
     *
     * 캡처는 표가 아니라 `qa_log` 의 SCREENSHOT 한 행이고 그 `message_id` 가 captureId 다. 남의
     * try 의 캡처를 근거로 달 수 있으면 rationale 이 가리키는 그림이 다른 런의 것이 된다.
     */
    private suspend fun validCapture(type: String, qaTry: QaTryEntity, captureId: String?): Step<String?> {
        val id = captureId?.trim()?.takeIf { it.isNotEmpty() } ?: return Step.Ok(null)
        val log = qaLogs.findByQaTryIdAndDirectionAndMessageId(qaTry.id!!, CAPTURE_DIRECTION, id)
        if (log == null || log.type != CAPTURE_TYPE) {
            return reject(type, "references a capture this try never took: $id")
        }
        return Step.Ok(id)
    }

    /** `inferred` 가 딛고 선 observation. 이 런의 것이어야 한다. */
    private suspend fun validBasedOn(type: String, qaRunId: Long, basedOn: List<String>): Step<List<Long>> {
        val parsed = basedOn.map { it.trim().toLongOrNull() }
        if (parsed.any { it == null }) {
            return reject(type, "payload.based_on must be numeric observation ids: $basedOn")
        }
        // 같은 id 를 두 번 적은 것은 오류가 아니라 중복이다. 접고 넘어가되, 아래 개수 대조는 접은
        // 뒤의 것으로 한다 — 접기 전 개수로 대조하면 멀쩡한 목록이 "이 런의 것이 아니다" 로 막힌다.
        val ids = parsed.filterNotNull().distinct()
        if (ids.isEmpty()) {
            return reject(
                type,
                "origin ${CapabilityOrigin.INFERRED.wire} requires based_on — " +
                    "an inference that names no observation cannot be retraced"
            )
        }
        if (observations.countOfRun(qaRunId, ids) != ids.size.toLong()) {
            return reject(type, "payload.based_on references observations outside this run: $ids")
        }
        return Step.Ok(ids)
    }

    private fun validRationale(type: String, rationale: String?): Step<String> {
        val text = rationale?.trim()
        if (text.isNullOrEmpty()) {
            return reject(type, "payload.rationale is required — a verdict nobody can retrace cannot be checked later")
        }
        if (text.length > CapabilityWriteFrames.MAX_RATIONALE_LENGTH) {
            return reject(
                type,
                "payload.rationale is longer than ${CapabilityWriteFrames.MAX_RATIONALE_LENGTH} characters"
            )
        }
        return Step.Ok(text)
    }

    /** 사유 문장의 앞머리를 요청 타입으로 통일한다. agent 로그에서 어느 frame 이 막혔는지 바로 읽힌다. */
    private fun message(type: String, reason: String) = "$type $reason"

    /** 검증 한 칸이 막혔다. 호출부가 [CapabilityWrite.Rejected] 로 옮긴다. */
    private fun reject(type: String, reason: String) = Step.No(message(type, reason))

    /** 쓰기 전체가 막혔다. 라우터가 이것을 ERROR frame 으로 돌려준다. */
    private fun refuse(type: String, reason: String) = CapabilityWrite.Rejected(message(type, reason))

    private fun verificationOf(verdict: CapabilityVerdict) = when (verdict) {
        CapabilityVerdict.WORKS -> VerificationState.CONFIRMED
        CapabilityVerdict.FAILS -> VerificationState.CONTRADICTED
    }

    /**
     * `SpecStatus.derive` 가 세 축에서 `status` 를 뽑는 것과 같은 규칙의 첫 칸이다.
     *
     * `interaction = none` 은 조작이 없다는 뜻이라 단독 명세가 될 수 없고, 그 행은 `status` 가
     * `not-a-step` 이 되어 TC 생성기가 읽는
     * [kr.artel.orchestration.contentmap.repository.ContentMapRepository.findStepCapabilityRows]
     * 에서 빠진다. 실측 472 행 중 418 행이 그 상태이므로 이것은 agent 가 적은 행에만 있는 사정이
     * 아니라 이 표의 기본값에 가깝다.
     *
     * agent 는 그 418 행을 그대로 받는다(ARTEL-680, V72). 못 보면 적을 대상을 지목할 수 없고,
     * 그러면 이 서비스가 여는 쓰기 경로에 닿을 것이 54 행뿐이다.
     */
    private fun actionabilityOf(interaction: Interaction) =
        if (interaction == Interaction.NONE) Actionability.NOT_A_STEP else Actionability.RUNNABLE

    private data class ResolvedScene(
        val contentMapId: Long,
        val sceneId: Long,
        val sceneName: String,
        val qaRunId: Long,
    )

    /**
     * 검증 한 칸의 결과. 거절 사유를 값으로 들고 다니게 하려고 둔 것이고, 바깥으로 나가는 결과는
     * [CapabilityWrite] 다.
     */
    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>
        data class No(val reason: String) : Step<Nothing>
    }
}

/**
 * 쓰기 하나의 결과. **거절을 예외로 올리지 않는다.**
 *
 * 라우터가 이것을 읽어 성공은 `CAPABILITY_WRITE_RESULT`, 거절은 요청의 correlation 을 문 `ERROR`
 * frame 으로 답한다. 예외로 올리면 receive 체인이 끊겨 frame 하나가 QA 런 전체를 실패시킨다 —
 * 지식 쓰기가 `KnowledgeMutation` 으로 같은 모양을 이미 쓰고 있다.
 */
sealed interface CapabilityWrite {

    data class Accepted(
        val type: String,
        val capabilityId: Long,
        val capabilityKey: String?,
        val sceneId: Long,
        val verification: String,
        /** verdict 를 실은 frame 에만 있다. 발견만 한 frame 에서는 null 이다. */
        val observationId: Long?,
        /** `CAPABILITY_DISCOVERED` 가 행을 새로 넣었나. 재전송이 흡수되면 false 다. */
        val created: Boolean,
    ) : CapabilityWrite

    data class Rejected(val reason: String) : CapabilityWrite
}
