package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.knowledge.service.KnowledgeCitationService
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant
import java.util.UUID

@Service
class QaExecutionFailurePersistence(
    private val tryRepository: QaTryRepository,
    private val runRollupService: QaRunRollupService,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock
) {
    suspend fun failActiveByInstance(gameInstanceId: Long, reason: String): FailureLogs? =
        transactionalOperator.executeAndAwait {
            val completedAt = Instant.now(clock)
            val active = tryRepository.findActiveByGameInstanceId(gameInstanceId)
                ?: return@executeAndAwait null
            if (tryRepository.failActiveByGameInstanceId(gameInstanceId, completedAt) != 1) {
                return@executeAndAwait null
            }
            runRollupService.rollUpIfAllTriesDone(active.qaRunId, completedAt)
            val qaTryId = requireNotNull(active.id)
            val errorLog = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "ERROR",
                message = reason,
                payload = objectMapper.createObjectNode().put("reason", reason)
            )
            val statusLog = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution failed because its connection closed.",
                payload = objectMapper.valueToTree(QaStatusPayload("FAILED", completedAt))
            )
            FailureLogs(qaTryId, active.gameInstanceId, active.agentSessionId, errorLog, statusLog)
        }

    suspend fun failActiveById(qaTryId: Long, reason: String): FailureLogs? =
        transactionalOperator.executeAndAwait {
            val completedAt = Instant.now(clock)
            val active = tryRepository.findById(qaTryId)
                ?.takeIf { it.status == "STARTING" || it.status == "RUNNING" }
                ?: return@executeAndAwait null
            if (tryRepository.failActiveById(qaTryId, completedAt) != 1) {
                return@executeAndAwait null
            }
            runRollupService.rollUpIfAllTriesDone(active.qaRunId, completedAt)
            val errorLog = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "ERROR",
                message = reason,
                payload = objectMapper.createObjectNode().put("reason", reason)
            )
            val statusLog = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution failed.",
                payload = objectMapper.valueToTree(QaStatusPayload("FAILED", completedAt))
            )
            FailureLogs(qaTryId, active.gameInstanceId, active.agentSessionId, errorLog, statusLog)
        }

    /**
     * Marks a run cancelled and records who ended it.
     *
     * Orchestration decides this, rather than waiting for the Agent to confirm:
     * an operator must be able to end a run whose Agent has stopped answering,
     * which is exactly when ending it matters most. The Agent is told afterwards,
     * best effort.
     */
    suspend fun cancelActiveById(qaTryId: Long, reason: String): FailureLogs? =
        transactionalOperator.executeAndAwait {
            val completedAt = Instant.now(clock)
            val active = tryRepository.findById(qaTryId)
                ?.takeIf { it.status == "STARTING" || it.status == "RUNNING" }
                ?: return@executeAndAwait null
            if (tryRepository.cancelActiveById(qaTryId, completedAt) != 1) {
                return@executeAndAwait null
            }
            runRollupService.rollUpIfAllTriesDone(active.qaRunId, completedAt)
            val requestLog = logService.append(
                qaTryId = qaTryId,
                direction = "USER_TO_ORCHE",
                type = "LOG",
                message = reason,
                payload = objectMapper.createObjectNode().put("reason", reason)
            )
            val statusLog = logService.append(
                qaTryId = qaTryId,
                direction = "ORCHE_INTERNAL",
                type = "STATUS",
                message = "QA execution was cancelled.",
                payload = objectMapper.valueToTree(QaStatusPayload("CANCELLED", completedAt))
            )
            FailureLogs(qaTryId, active.gameInstanceId, active.agentSessionId, requestLog, statusLog)
        }

}

data class FailureLogs(
    val qaTryId: Long,
    /** 판독을 끌 대상. 트랜잭션이 커밋된 뒤에 쓰이므로 여기 실어 내보낸다 (ARTEL-507). */
    val gameInstanceId: Long,
    val agentSessionId: String?,
    val error: QaLogAppendResult,
    val status: QaLogAppendResult
)

/**
 * 런이 정상 종료 외의 경로로 끝나는 자리 전부. 소켓 사망, Agent 세션 생성 실패, 운영자 취소가
 * 여기로 모인다.
 *
 * **그래서 인용 확정도 여기 걸린다**(ARTEL-293). 정상 종료(`QaAgentInboundRouter.routeStatus`)에만
 * 걸면 실패한 런의 `knowledge_usage.cited`가 영영 NULL로 남아, "보고할 수 없었던 런"과
 * "보고했는데 인용하지 않은 런"이 갈리지 않는다. 확정은 상태 전이가 **커밋된 뒤에** 부른다 —
 * 트랜잭션 안에서 실패하면 그 트랜잭션이 통째로 되돌아가 런 종료 자체가 깨진다.
 */
@Service
class QaExecutionFailureService(
    private val persistence: QaExecutionFailurePersistence,
    private val readings: QaReadingsService,
    private val logService: QaLogService,
    private val streamManager: QaLogStreamManager,
    private val agentPort: QaAgentPort,
    private val citationService: KnowledgeCitationService,
    private val grader: ExpectedStepsGrader,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    suspend fun sdkDisconnected(gameInstanceId: Long) {
        val failed = persistence.failActiveByInstance(gameInstanceId, "SDK connection closed.") ?: return
        logService.publish(failed.error)
        logService.publish(failed.status)
        // 기록이 먼저고 파생이 나중이다: 인용 확정은 이 런이 무엇을 썼는지를 못박고,
        // 채점은 그 뒤에 판정을 기대 라벨과 대조한다. 둘 다 스스로 실패를 삼킨다.
        citationService.finalizeTry(failed.qaTryId)
        grader.grade(failed.qaTryId)
        // 판독을 끄지 않는다. 소켓이 이미 죽어 보낼 곳이 없고, SDK 는 연결이 끊기면
        // `EndDiscovery` 로 스스로 멈춘다(ARTEL-417). 여기서 보내면 매 절단마다 실패 경고만 쌓인다.
        try {
            closeQuietly(failed.agentSessionId)
        } finally {
            streamManager.complete(failed.qaTryId)
        }
    }

    suspend fun agentDisconnected(qaTryId: Long) =
        fail(qaTryId, "QA Agent connection closed.", closeAgent = false)

    /**
     * Ends a run because the operator asked. `false` when it had already ended.
     *
     * The Agent is told with a CANCEL frame so it can stop mid-step and drop its
     * session, but neither that send nor the close is allowed to fail the
     * cancellation — the run is already cancelled in the database by then.
     */
    suspend fun cancelled(qaTryId: Long, reason: String): Boolean {
        val cancelled = persistence.cancelActiveById(qaTryId, reason) ?: return false
        logService.publish(cancelled.error)
        logService.publish(cancelled.status)
        citationService.finalizeTry(qaTryId)
        grader.grade(qaTryId)
        // 상태 전이가 커밋된 뒤라야 `stopIfIdle` 이 방금 끝낸 시도를 끝난 것으로 읽는다 (ARTEL-507).
        readings.stopIfIdle(cancelled.gameInstanceId)
        val sessionId = cancelled.agentSessionId
        try {
            if (sessionId != null) {
                try {
                    agentPort.send(
                        sessionId,
                        QaAgentEnvelope(
                            messageId = UUID.randomUUID().toString(),
                            type = "CANCEL",
                            qaTryId = qaTryId.toString(),
                            timestamp = Instant.now(clock),
                            payload = objectMapper.createObjectNode().put("reason", reason)
                        )
                    )
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    // best effort: the run is already cancelled in the database
                }
                closeQuietly(sessionId)
            }
        } finally {
            streamManager.complete(qaTryId)
        }
        return true
    }

    /**
     * 이미 DB에서 닫힌 런의 Agent 세션을 끊는다. 상태는 건드리지 않는다.
     *
     * [cancelled]는 활성 try의 `agent_session_id`로 세션을 찾는데, 런에는 활성 try가 없는 창이
     * 있다(시나리오 사이, 그리고 세션이 붙기 전). 그 창을 메우려면 **런이 들고 있는** 세션 id로
     * 끊어야 한다. 여기서는 CANCEL 프레임을 보내지 않고 소켓만 닫는다 — CANCEL 봉투는 실재하는
     * qa_try_id를 요구하는데 이 경로에는 그런 try가 없고, 소켓이 닫히면 Agent는 어차피 그 런을
     * 취소한다.
     *
     * 이미 닫힌 세션이면 아무 일도 하지 않는다(중복 호출 안전).
     */
    suspend fun releaseAgentSession(sessionId: String?) = closeQuietly(sessionId)

    suspend fun failStarting(qaTryId: Long, reason: String) =
        fail(qaTryId, reason, closeAgent = true)

    /**
     * 채점은 종단 경로 **전부**에서 돈다(ARTEL-301). 정상 종료에만 걸면 소켓이 죽거나 취소된 런이
     * 영영 채점되지 않고, 그러면 잘 죽는 모델의 최악 런이 통째로 집계에서 빠져 그 모델이 실제보다
     * 좋아 보인다 — 커버리지를 세는 이유와 같은 편향이다. 미보고 스텝이 그 자체로 기록되는 것이
     * 요점이라, 판정을 하나도 못 낸 런에도 행이 선다.
     */
    private suspend fun fail(qaTryId: Long, reason: String, closeAgent: Boolean) {
        val failed = persistence.failActiveById(qaTryId, reason) ?: return
        logService.publish(failed.error)
        logService.publish(failed.status)
        citationService.finalizeTry(qaTryId)
        grader.grade(qaTryId)
        readings.stopIfIdle(failed.gameInstanceId)
        try {
            if (closeAgent) closeQuietly(failed.agentSessionId)
        } finally {
            streamManager.complete(failed.qaTryId)
        }
    }

    private suspend fun closeQuietly(sessionId: String?) {
        if (sessionId == null) return
        try {
            agentPort.close(sessionId)
        } catch (error: CancellationException) {
            throw error
        } catch (_: Exception) {
            // best effort close
        }
    }
}
