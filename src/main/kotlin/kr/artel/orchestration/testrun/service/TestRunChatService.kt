package kr.artel.orchestration.testrun.service

import com.fasterxml.jackson.core.type.TypeReference
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testrun.dto.RunChatCancellation
import kr.artel.orchestration.testrun.dto.RunChatMessage
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.DraftApplyResult
import kr.artel.orchestration.testscenario.dto.DraftScenario
import kr.artel.orchestration.testscenario.dto.DraftScenarioEdit
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import kr.artel.orchestration.testscenario.service.AuthoringDraftStore
import kr.artel.orchestration.testscenario.service.ScenarioAbsorbService
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import kr.artel.orchestration.testscenario.service.TestScenarioAgentService
import kr.artel.orchestration.testscenario.service.TestScenarioStreamManager
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service

/**
 * 작성 챗봇의 런 스코프 대화 서비스(코루틴) — ARTEL-206 Step 6.
 *
 * v2 작성은 한 번의 대화로 여러 시나리오를 추가·수정하므로 대화의 주체는 시나리오가 아니라 **런**이다.
 * 따라서 세션·SSE·채팅 저장을 모두 (userId, runId)로 스코프한다 — 같은 런 안에서는 어떤 시나리오를
 * 편집하든 대화가 이어진다.
 *
 * 접근은 프로젝트 참여로 인가한다(비참여자에게는 런이 존재하지 않는 것처럼 404). Agent 프로토콜(WS)과
 * SSE Sink는 각각 [TestScenarioAgentService]/[TestScenarioStreamManager]에 위임한다(세션 키만 run으로 바뀜).
 */
@Service
class TestRunChatService(
    private val caseRecommendations: kr.artel.orchestration.testscenario.service.CaseRecommendationService,
    private val runRepository: TestRunRepository,
    private val runMessageRepository: TestRunMessageRepository,
    private val projectAccessService: ProjectAccessService,
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager,
    private val reconcileService: ScenarioReconcileService,
    private val runScenarioReader: RunScenarioReader,
    private val objectMapper: com.fasterxml.jackson.databind.ObjectMapper,
    private val drafts: AuthoringDraftStore,
    private val absorbService: ScenarioAbsorbService,
    private val runLocks: RunAuthoringLocks,
) {

    /** 사용자별 프라이빗 채팅 스레드를 시간순으로 조회한다(재방문 복원). 접근 불가면 빈 목록. */
    suspend fun getMessages(runId: Long, appUserId: Long): List<MessageResponse> {
        accessible(runId, appUserId) ?: return emptyList()
        return runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .map {
                MessageResponse(
                    role = it.role,
                    content = it.content,
                    createdAt = it.createdAt,
                    payload = it.payload?.let { raw ->
                        runCatching {
                            objectMapper.readValue(raw.asString(), object : TypeReference<Map<String, Any?>>() {})
                        }.getOrNull()
                    },
                )
            }
            .toList()
    }

    /** FE가 Agent 응답을 실시간 수신하는 SSE 스트림. 접근 불가면 404. */
    suspend fun stream(appUserId: Long, runId: Long): Flow<ServerSentEvent<ScenarioStreamEvent>> {
        accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        // 붙자마자 지금 턴 상태부터 보낸다. 끊긴 사이의 진행 · 결과는 다시 오지 않는다.
        return streamManager.stream(key) { agentService.turnState(key) }
    }

    /** 사용자 입력을 Agent로 중계한다. 결과 시나리오는 이 런에 반영된다. 접근 불가면 404. */
    suspend fun relay(appUserId: Long, runId: Long, message: RunChatMessage) {
        val run = accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        // **승인 전 draft 가 있으면 새 요청은 받지 않는다**(ARTEL-1074). draft 를 그대로 둔 채 새 요청을 받으면
        // 고칠 대상이 draft 인지 저장된 시나리오인지 갈리고, 결과가 엉뚱한 draft 에 겹친다. 그동안의 말은
        // draft 만 고치는 길([RunChatMessage.draftOnly])로 받는다.
        val pending = drafts.hasDrafts(key)
        if (pending && !message.draftOnly) {
            throw ConflictException("승인하지 않은 draft 가 있습니다. draft 를 승인하거나 버린 뒤 새 요청을 보내 주세요.", DRAFT_PENDING)
        }
        if (!pending && message.draftOnly) {
            throw ConflictException("고칠 draft 가 없습니다.", NO_DRAFT)
        }
        val saved = runScenarioReader.currentScenarios(runId)
        agentService.sendMessage(
            sessionKey = key,
            runId = runId,
            projectId = run.projectId,
            appUserId = appUserId,
            userInput = message.message,
            // draft 만 고치는 말의 결과는 늘 draft 에 남는다.
            autoApply = message.autoApply && !message.draftOnly,
            // **draft 위에서 이어 고친다**(ARTEL-1074). 자동저장을 끈 채 다시 말하면 Agent 는 draft 가
            // 적용된 모습을 현재 상태로 본다 — "이건 빼 줘" 가 draft 에 먹어야 한다. 자동저장을 켠
            // 턴은 저장된 것만 본다. 그 턴의 결과는 바로 저장되는데, 저장 경로는 draft 번호를 모른다.
            // draft 만 고치는 말은 draft 만 보인다 — 저장된 것을 고칠 길이 없어야 범위가 지켜진다.
            currentScenarios = when {
                message.draftOnly -> drafts.draftsOnly(key)
                message.autoApply -> saved
                else -> drafts.overlay(key, saved)
            },
            answer = message.answer,
            draftOnly = message.draftOnly,
        )
    }

    /** 이 대화의 draft(ARTEL-1074). 새로고침한 화면이 되찾는 길이다. 접근 불가면 404. */
    suspend fun draft(appUserId: Long, runId: Long): List<DraftScenario> {
        accessible(runId, appUserId) ?: throw NotFoundException()
        return drafts.list(sessionKey(appUserId, runId))
    }

    /**
     * 사용자가 고친 본문으로 draft 를 바꾼다(ARTEL-1074). 시나리오에는 쓰지 않는다 — 승인하기 전까지는
     * draft 다. 다음 턴의 Agent 는 고친 모습을 본다. 없는 draft 면 404.
     */
    suspend fun updateDraft(appUserId: Long, runId: Long, draftId: String, edit: DraftScenarioEdit): DraftScenario {
        accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        val updated = drafts.of(key).update(draftId, edit) ?: throw NotFoundException("draft 가 없습니다.")
        drafts.publish(key)
        return updated
    }

    /**
     * 고른 draft 를 반영한다(ARTEL-1074). 자동저장과 **같은 엔진**이다 — 본문은 [ScenarioReconcileService],
     * 합치기의 걷어내기는 [ScenarioAbsorbService]. 하나씩 반영해서, 하나가 막혀도 나머지는 들어간다.
     * 막힌 것은 draft 에 되돌려 두고 이유를 낸다. 접근 불가면 404.
     */
    suspend fun applyDraft(appUserId: Long, runId: Long, draftIds: List<String>): DraftApplyResult {
        val run = accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        val draft = drafts.of(key)
        // **시나리오를 승인하면 그 시나리오가 쓰는 추천 TC 도 한 번에 저장한다.** 먼저 저장해 스텝을 새 TC 로 바꿔 끼운 뒤
        // 승인한다 — 거꾸로 하면 판정 없는 스텝으로 저장되고 추천만 남는다.
        val recommended = draft.list().filter { it.draftId in draftIds.toSet() }.flatMap { it.steps }.mapNotNull { it.recommendationId }.distinct()
        if (recommended.isNotEmpty()) caseRecommendations.save(run.projectId, runId, appUserId, recommended)
        var applied = 0
        val absorbed = mutableListOf<String>()
        val kept = mutableListOf<String>()
        val refused = mutableListOf<String>()
        for (item in draft.take(draftIds)) {
            // 다른 사람의 Artel Bot 이 고치는 중인 시나리오는 승인하지 않고 draft 에 남긴다(ARTEL-1264).
            val target = item.toResult().scenarioId
            if (target != null && target > 0 && runLocks.heldByOther(target, key)) {
                draft.restore(item)
                refused += "'${item.title}' — 다른 사람의 Artel Bot 이 이 시나리오를 고치는 중이라 승인하지 않았습니다."
                continue
            }
            val outcome = reconcileService.reconcile(runId, run.projectId, appUserId, listOf(item.toResult()))
            if (outcome.rejected) {
                draft.restore(item)
                refused += "'${item.title}' — ${outcome.findings.rejectionMessage()}"
                continue
            }
            applied += outcome.applied
            // 합치기는 **저장된 최종본**으로 센다. draft 본문으로 세면 코드가 손댄 뒤의 실제 본문과 달라
            // 실제로는 빠진 케이스를 있다고 세고 원본을 지울 수 있다.
            val keeper = outcome.saved.firstOrNull()
            if (keeper != null && item.absorbedScenarioIds.isNotEmpty()) {
                val result = absorbService.absorb(
                    runId, keeper.scenarioId, outcome.checked.firstOrNull() ?: item.toResult(), item.absorbedScenarioIds,
                )
                absorbed += result.absorbed
                kept += result.kept
            }
            agentService.deliver(appUserId, runId, outcome.notices, outcome.question)
        }
        drafts.publish(key)
        return DraftApplyResult(applied, absorbed, kept, refused, draft.list())
    }

    /** 고른 draft 를 버린다(ARTEL-1074). 남은 draft 를 돌려준다. 접근 불가면 404. */
    suspend fun discardDraft(appUserId: Long, runId: Long, draftIds: List<String>): List<DraftScenario> {
        accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        drafts.of(key).take(draftIds)
        drafts.publish(key)
        return drafts.list(key)
    }

    /**
     * 카드 검토 모드에서 사용자가 카드로 고른/편집한 [scenarios]를 이 런에 커밋한다(수동 트리거).
     * 자동저장과 **같은 reconcile 엔진**을 쓴다 — 추가/수정은 항목별 scenario_id로 갈린다. 접근 불가면 404.
     * @return 실제로 반영된 시나리오 수(추가 + 수정).
     */
    suspend fun commitScenarios(appUserId: Long, runId: Long, scenarios: List<ScenarioResult>): Int {
        val run = accessible(runId, appUserId) ?: throw NotFoundException()
        // 다른 사람의 Artel Bot 이 고치는 중인 시나리오는 커밋하지 못한다(ARTEL-1264).
        val key = sessionKey(appUserId, runId)
        scenarios.mapNotNull { it.scenarioId?.takeIf { id -> id > 0 } }.forEach { runLocks.assertWritableBy(it, key) }
        // 판정을 넘기지 않는다 — 이 경로의 시나리오는 사용자가 카드로 직접 고른 것이라 "Agent가 다
        // 봤는가"를 물을 대상이 아니다. 사람이 고른 것이 곧 요구다.
        //
        val outcome = reconcileService.reconcile(runId, run.projectId, appUserId, scenarios)
        // **알림과 질문은 저장 경로와 무관하게 나간다**(ARTEL-487). 이 경로의 응답은 반영 건수
        // 하나라 거기 실을 자리가 없어서, 챗봇과 같은 자리(대화 기록 + SSE)로 흘린다 — 예전에는
        // 여기서 조용히 버려져, 카드로 저장한 사용자에게는 미상 스텝만 남고 이유도 답할 방법도
        // 보이지 않았다.
        agentService.deliver(appUserId, runId, outcome.notices, outcome.question)
        return outcome.applied
    }

    /**
     * 도는 저작 요청 **하나만** 끊는다(ARTEL-955). 세션·대화·이미 저장한 시나리오는 그대로 남는다.
     *
     * [close] 와 다른 길인 것이 이 기능의 전부다 — 사용자가 멈추려는 것은 기다림이지 대화가 아니다.
     * 접근 불가면 404.
     */
    suspend fun cancel(appUserId: Long, runId: Long): RunChatCancellation {
        accessible(runId, appUserId) ?: throw NotFoundException()
        return agentService.cancelTurn(sessionKey(appUserId, runId))
    }

    /**
     * 작성 세션을 종료한다(사용자가 런 편집을 마치고 나갈 때). Agent WS 세션과 SSE 스트림을 함께 닫는다.
     * 채팅 내역·시나리오는 그대로 남는다. 접근 불가면 404.
     */
    suspend fun close(appUserId: Long, runId: Long) {
        accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        agentService.closeSession(key)
        // draft 는 대화를 닫을 때 버린다(ARTEL-1074). Agent WS 가 혼자 끊기는 것과 달리 이것은 사용자가 한 일이다.
        drafts.drop(key)
        streamManager.complete(key)
    }

    private suspend fun accessible(runId: Long, appUserId: Long): TestRunEntity? {
        val run = runRepository.findById(runId) ?: return null
        return if (projectAccessService.isMember(run.projectId, appUserId)) run else null
    }

    private fun sessionKey(appUserId: Long, runId: Long) = "$appUserId:$runId"

    /** 이 런에서 Artel Bot 이 고치는 중이라 잠긴 시나리오들(ARTEL-1264). 접근 불가면 404. */
    suspend fun authoringLock(appUserId: Long, runId: Long) =
        accessible(runId, appUserId)?.let { runLocks.describe(runId, appUserId) } ?: throw NotFoundException()

    companion object {
        /** 승인 전 draft 가 있어 새 요청을 받지 않는다(ARTEL-1074). 화면은 draft 만 고치는 입력으로 안내한다. */
        const val DRAFT_PENDING = "draft_pending"

        /** draft 만 고치는 말인데 고칠 draft 가 없다. */
        const val NO_DRAFT = "no_draft"
    }
}
