package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.qa.dto.QaStatusPayload
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant

@Service
class QaExecutionFailurePersistence(
    private val tryRepository: QaTryRepository,
    private val logService: QaLogService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    @Transactional
    fun failActiveByInstance(gameInstanceId: Long, reason: String): Mono<FailureLogs> {
        val completedAt = Instant.now(clock)
        return tryRepository.findActiveByGameInstanceId(gameInstanceId)
            .flatMap { active ->
                tryRepository.failActiveByGameInstanceId(gameInstanceId, completedAt)
                    .filter { it == 1 }
                    .flatMap {
                        val qaTryId = requireNotNull(active.id)
                        logService.append(
                            qaTryId = qaTryId,
                            direction = "ORCHE_INTERNAL",
                            type = "ERROR",
                            message = reason,
                            payload = objectMapper.createObjectNode().put("reason", reason)
                        ).flatMap { errorLog ->
                            logService.append(
                                qaTryId = qaTryId,
                                direction = "ORCHE_INTERNAL",
                                type = "STATUS",
                                message = "QA execution failed because its connection closed.",
                                payload = objectMapper.valueToTree(QaStatusPayload("FAILED", completedAt))
                            ).map { statusLog -> FailureLogs(qaTryId, active.agentSessionId, errorLog, statusLog) }
                        }
                    }
            }
    }

    @Transactional
    fun failActiveById(qaTryId: Long, reason: String): Mono<FailureLogs> {
        val completedAt = Instant.now(clock)
        return tryRepository.findById(qaTryId)
            .filter { it.status == "STARTING" || it.status == "RUNNING" }
            .flatMap { active ->
                tryRepository.failActiveById(qaTryId, completedAt)
                    .filter { it == 1 }
                    .flatMap {
                        logService.append(
                            qaTryId = qaTryId,
                            direction = "ORCHE_INTERNAL",
                            type = "ERROR",
                            message = reason,
                            payload = objectMapper.createObjectNode().put("reason", reason)
                        ).flatMap { errorLog ->
                            logService.append(
                                qaTryId = qaTryId,
                                direction = "ORCHE_INTERNAL",
                                type = "STATUS",
                                message = "QA execution failed.",
                                payload = objectMapper.valueToTree(QaStatusPayload("FAILED", completedAt))
                            ).map {
                                FailureLogs(qaTryId, active.agentSessionId, errorLog, it)
                            }
                        }
                    }
            }
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
    private val agentPort: QaAgentPort
) {
    fun sdkDisconnected(gameInstanceId: Long): Mono<Void> =
        persistence.failActiveByInstance(gameInstanceId, "SDK connection closed.")
            .flatMap { failed ->
                logService.publish(failed.error)
                logService.publish(failed.status)
                val close = failed.agentSessionId?.let(agentPort::close) ?: Mono.empty()
                close.onErrorResume { Mono.empty() }
                    .doFinally { streamManager.complete(failed.qaTryId) }
            }
            .then()

    fun agentDisconnected(qaTryId: Long): Mono<Void> =
        fail(qaTryId, "QA Agent connection closed.", closeAgent = false)

    fun failStarting(qaTryId: Long, reason: String): Mono<Void> =
        fail(qaTryId, reason, closeAgent = true)

    private fun fail(qaTryId: Long, reason: String, closeAgent: Boolean): Mono<Void> =
        persistence.failActiveById(qaTryId, reason)
            .flatMap { failed ->
                logService.publish(failed.error)
                logService.publish(failed.status)
                val close = if (closeAgent && failed.agentSessionId != null) {
                    agentPort.close(failed.agentSessionId).onErrorResume { Mono.empty() }
                } else Mono.empty()
                close.doFinally { streamManager.complete(failed.qaTryId) }
            }
            .then()
}
