package kr.artel.orchestration.testrun.service

import kr.artel.orchestration.common.error.NotFoundException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testrun.dto.RunChatMessage
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.MessageResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
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
    private val runRepository: TestRunRepository,
    private val runMessageRepository: TestRunMessageRepository,
    private val projectAccessService: ProjectAccessService,
    private val agentService: TestScenarioAgentService,
    private val streamManager: TestScenarioStreamManager,
    private val reconcileService: ScenarioReconcileService,
    private val runScenarioReader: RunScenarioReader,
) {

    /** 사용자별 프라이빗 채팅 스레드를 시간순으로 조회한다(재방문 복원). 접근 불가면 빈 목록. */
    suspend fun getMessages(runId: Long, appUserId: Long): List<MessageResponse> {
        accessible(runId, appUserId) ?: return emptyList()
        return runMessageRepository
            .findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(runId, appUserId)
            .map { MessageResponse(role = it.role, content = it.content, createdAt = it.createdAt) }
            .toList()
    }

    /** FE가 Agent 응답을 실시간 수신하는 SSE 스트림. 접근 불가면 404. */
    suspend fun stream(appUserId: Long, runId: Long): Flow<ServerSentEvent<ScenarioStreamEvent>> {
        accessible(runId, appUserId) ?: throw NotFoundException()
        return streamManager.stream(sessionKey(appUserId, runId))
    }

    /** 사용자 입력을 Agent로 중계한다. 결과 시나리오는 이 런에 반영된다. 접근 불가면 404. */
    suspend fun relay(appUserId: Long, runId: Long, message: RunChatMessage) {
        val run = accessible(runId, appUserId) ?: throw NotFoundException()
        agentService.sendMessage(
            sessionKey = sessionKey(appUserId, runId),
            runId = runId,
            projectId = run.projectId,
            appUserId = appUserId,
            userInput = message.message,
            autoApply = message.autoApply,
            currentScenarios = runScenarioReader.currentScenarios(runId),
        )
    }

    /**
     * 카드 검토 모드에서 사용자가 카드로 고른/편집한 [scenarios]를 이 런에 커밋한다(수동 트리거).
     * 자동저장과 **같은 reconcile 엔진**을 쓴다 — 추가/수정은 항목별 scenario_id로 갈린다. 접근 불가면 404.
     * @return 실제로 반영된 시나리오 수(추가 + 수정).
     */
    suspend fun commitScenarios(appUserId: Long, runId: Long, scenarios: List<ScenarioResult>): Int {
        val run = accessible(runId, appUserId) ?: throw NotFoundException()
        // 판정을 넘기지 않는다 — 이 경로의 시나리오는 사용자가 카드로 직접 고른 것이라 "Agent가 다
        // 봤는가"를 물을 대상이 아니다. 사람이 고른 것이 곧 요구다.
        return reconcileService.reconcile(runId, run.projectId, scenarios).applied
    }

    /**
     * 작성 세션을 종료한다(사용자가 런 편집을 마치고 나갈 때). Agent WS 세션과 SSE 스트림을 함께 닫는다.
     * 채팅 내역·시나리오는 그대로 남는다. 접근 불가면 404.
     */
    suspend fun close(appUserId: Long, runId: Long) {
        accessible(runId, appUserId) ?: throw NotFoundException()
        val key = sessionKey(appUserId, runId)
        agentService.closeSession(key)
        streamManager.complete(key)
    }

    private suspend fun accessible(runId: Long, appUserId: Long): TestRunEntity? {
        val run = runRepository.findById(runId) ?: return null
        return if (projectAccessService.isMember(run.projectId, appUserId)) run else null
    }

    private fun sessionKey(appUserId: Long, runId: Long) = "$appUserId:$runId"
}
