package kr.artel.orchestration.qa.service

import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.dto.RunStatusMessage
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testrun.repository.TestRunRepository
import org.slf4j.Logger
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

private const val WAITING_AGENT = "WAITING_AGENT"
private const val RUNNING = "RUNNING"
private const val FINISHED = "FINISHED"

/**
 * QA try 의 시작·agent 세션 붙음·종료를 그 game instance 의 SDK socket 으로 알린다(ARTEL-836).
 *
 * `SessionManager.send`가 유일한 발신 경로다 — 새 배관을 만들지 않는다. **여기서 나가는 예외는
 * 없다.** 이 알림이 못 나갔다고 QA 런이 실패하거나 느려지면 안 된다 — 화면에 뜨는 문구 하나가
 * 실행을 좌우하게 되고, 그 결합은 `.agents/docs/error-handling.md`가 이 계층에 두라고 한 것이
 * 아니다. `CancellationException`은 오류가 아니라 취소 신호라 그대로 다시 던진다.
 */
@Service
class QaRunStatusNotifier(
    private val sessionManager: SessionManager,
    private val instanceRepository: GameInstanceRepository,
    private val projectRepository: ProjectRepository,
    private val runRepository: QaRunRepository,
    private val testRunRepository: TestRunRepository,
    private val tryRepository: QaTryRepository,
    private val clock: Clock
) {
    private val logger: Logger = LoggerFactory.getLogger(QaRunStatusNotifier::class.java)

    /** QA try 가 STARTING(또는 run 의 첫 try 가 PENDING)으로 적재되고, agent 세션은 아직 없다. */
    suspend fun waitingAgent(qaTryId: Long, gameInstanceId: Long, qaRunId: Long?) =
        notify(qaTryId, gameInstanceId, qaRunId, WAITING_AGENT, outcome = null)

    /** agent 세션이 붙어 이 qa_try(또는 run 안의 다음 시나리오)가 RUNNING 이 됐다. */
    suspend fun running(qaTryId: Long, gameInstanceId: Long, qaRunId: Long?) =
        notify(qaTryId, gameInstanceId, qaRunId, RUNNING, outcome = null)

    /** qa_try 가 종단됐다. [outcome] 은 `PASSED` · `FAILED` · `CANCELLED` · `ERROR` 중 하나다. */
    suspend fun finished(qaTryId: Long, gameInstanceId: Long, qaRunId: Long?, outcome: String) =
        notify(qaTryId, gameInstanceId, qaRunId, FINISHED, outcome)

    /**
     * SDK 가 (재)연결됐을 때, 그 game instance 에 활성 qa_try 가 있으면 지금 상태를 다시 보낸다.
     *
     * 활성 try 가 없으면 아무것도 보내지 않는다 — "끊겼다 붙은 창이 옛 문구를 들고 있지 않게
     * 한다"가 목적이지, 매 연결마다 "run 없음"을 알리는 채널이 아니다. `findActiveByGameInstanceId`가
     * STARTING/RUNNING 만 돌려주므로 이미 종단된 try 는 여기서도 조용히 넘어간다.
     */
    suspend fun onReconnect(gameInstanceId: Long) {
        val active = try {
            tryRepository.findActiveByGameInstanceId(gameInstanceId)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("RUN_STATUS 재연결 조회 실패 [gameInstanceId={}]: {}", gameInstanceId, error.message)
            return
        } ?: return
        val state = if (active.agentSessionId == null) WAITING_AGENT else RUNNING
        notify(requireNotNull(active.id), gameInstanceId, active.qaRunId, state, outcome = null)
    }

    private suspend fun notify(qaTryId: Long, gameInstanceId: Long, qaRunId: Long?, state: String, outcome: String?) {
        try {
            val instance = instanceRepository.findById(gameInstanceId) ?: return
            val projectName = projectRepository.findById(instance.projectId)?.name ?: return
            val qaRun = qaRunId?.let { runRepository.findById(it) }
            val testRunName = qaRun?.testRunId?.let { testRunRepository.findById(it)?.name }
            sessionManager.send(
                gameInstanceId.toString(),
                RunStatusMessage(
                    state = state,
                    projectName = projectName,
                    testRunName = testRunName,
                    qaRunId = qaRunId,
                    qaTryId = qaTryId,
                    label = qaRun?.label,
                    outcome = outcome,
                    at = Instant.now(clock)
                )
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 붙어 있는 socket 이 없거나 전송이 실패해도 QA 런은 계속 간다 — 상태 알림은
            // 곁가지지 실행의 전제조건이 아니다.
            logger.warn(
                "RUN_STATUS 전송 실패 [gameInstanceId={}, qaTryId={}, state={}]: {}",
                gameInstanceId, qaTryId, state, error.message
            )
        }
    }
}
