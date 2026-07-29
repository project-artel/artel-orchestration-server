package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
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
            FailureLogs(qaTryId, active.agentSessionId, errorLog, statusLog)
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
            FailureLogs(qaTryId, active.agentSessionId, errorLog, statusLog)
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
            FailureLogs(qaTryId, active.agentSessionId, requestLog, statusLog)
        }
}

data class FailureLogs(
    val qaTryId: Long,
    val agentSessionId: String?,
    val error: QaLogAppendResult,
    val status: QaLogAppendResult
)

@Service
class QaExecutionFailureService(
    private val persistence: QaExecutionFailurePersistence,
    private val logService: QaLogService,
    private val streamManager: QaLogStreamManager,
    private val agentPort: QaAgentPort,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    suspend fun sdkDisconnected(gameInstanceId: Long) {
        val failed = persistence.failActiveByInstance(gameInstanceId, "SDK connection closed.") ?: return
        logService.publish(failed.error)
        logService.publish(failed.status)
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

    suspend fun failStarting(qaTryId: Long, reason: String) =
        fail(qaTryId, reason, closeAgent = true)

    private suspend fun fail(qaTryId: Long, reason: String, closeAgent: Boolean) {
        val failed = persistence.failActiveById(qaTryId, reason) ?: return
        logService.publish(failed.error)
        logService.publish(failed.status)
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
