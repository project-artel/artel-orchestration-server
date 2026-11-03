package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.sdk.dto.ActionAcceptedDto
import kr.artel.orchestration.sdk.dto.ActionDeliveryDto
import kr.artel.orchestration.sdk.dto.ActionDeliveryResultDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.slf4j.LoggerFactory
import org.springframework.scheduling.annotation.Scheduled
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Clock

/** DB commit 뒤에 등록된 QA ACTION만 같은 SDK 프로세스에 제한 재전송한다. */
@Service
class SdkActionDelivery(
    private val sessions: SessionManager,
    private val tries: QaTryRepository,
    private val mapper: ObjectMapper,
) {
    internal var clock: Clock = Clock.systemUTC()
    private val logger = LoggerFactory.getLogger(javaClass)
    private val mutex = Mutex()
    private val pending = LinkedHashMap<Pair<String, Long>, Pending>()

    private data class Pending(
        val instanceId: String,
        val qaTryId: Long,
        val requestId: Long,
        val processId: String,
        val json: String,
        val expiresAt: Long,
        var session: Any?,
        var attempts: Int = 1,
        var lastSentAt: Long,
        var accepted: Boolean = false,
    )

    suspend fun send(
        instanceId: String,
        qaTryId: Long,
        action: ActionResponseDto,
        expiresAt: Long = clock.millis() + LIFETIME_MILLIS
    ): Boolean = mutex.withLock {
        val now = clock.millis()
        if (now >= expiresAt) return@withLock false
        val processId = sessions.deliveryProcessId(instanceId)
            ?: return@withLock sessions.sendAction(instanceId, action)
        val key = instanceId to action.id
        check(key !in pending) { "QA ACTION requestId is already pending" }
        // 상한에 닿으면 새 효과를 보내지 않는다. 진행 중 요청을 지우면 중복 실행 방지가 깨진다.
        if (pending.size >= MAX_PENDING) return@withLock false
        val json = mapper.writeValueAsString(action.copy(delivery = ActionDeliveryDto(processId, expiresAt)))
        val delivery = Pending(instanceId, qaTryId, action.id, processId, json, expiresAt,
            sessions.sessionIdentity(instanceId), lastSentAt = now)
        pending[key] = delivery
        val queued = sessions.sendRawToSession(instanceId, delivery.session, processId, json)
        logger.info("SDK ACTION 복구 등록 [instanceId={}, requestId={}, queued={}]", instanceId, action.id, queued)
        // 세션 교체나 닫힌 큐와 경합해도 pending은 유지한다. 같은 프로세스가 돌아온 뒤 복구할 수 있다.
        true
    }

    suspend fun accepted(instanceId: String, frame: ActionAcceptedDto, session: WebSocketSession) = mutex.withLock {
        if (sessions.getSession(instanceId) !== session) return@withLock
        val delivery = pending[instanceId to frame.requestId] ?: return@withLock
        if (frame.processId != delivery.processId) return@withLock
        delivery.accepted = true
        logger.info("SDK ACTION 수신 확인 [instanceId={}, requestId={}, attempts={}]",
            instanceId, frame.requestId, delivery.attempts)
    }

    suspend fun acceptsResult(instanceId: String, frame: ActionDeliveryResultDto, session: WebSocketSession): Boolean =
        mutex.withLock {
            if (sessions.getSession(instanceId) !== session) return@withLock false
            val delivery = frame.requestId?.let { pending[instanceId to it] }
            if (delivery != null) return@withLock delivery.processId == frame.processId
            frame.processId == null || frame.processId == sessions.deliveryProcessId(instanceId)
        }

    suspend fun completed(instanceId: String, frame: ActionDeliveryResultDto) = mutex.withLock {
        val key = instanceId to frame.requestId
        if (pending[key]?.processId == frame.processId) pending.remove(key)
    }

    suspend fun onReconnect(instanceId: String) = retryDue(instanceId)

    fun isCurrentSession(instanceId: String, session: WebSocketSession): Boolean =
        sessions.getSession(instanceId) === session

    suspend fun disconnectedTries(instanceId: String, processId: String?): List<Long> = mutex.withLock {
        if (processId == null) return@withLock emptyList()
        pending.values.filter { it.instanceId == instanceId && it.processId == processId }
            .map { it.qaTryId }.distinct()
    }

    @Scheduled(fixedDelay = 1000)
    fun retryScheduled() = runBlocking {
        try {
            retryDue()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn("SDK ACTION 재전송 확인 실패", error)
        }
    }

    internal suspend fun retryDue(onlyInstance: String? = null) = mutex.withLock {
        val now = clock.millis()
        val iterator = pending.iterator()
        while (iterator.hasNext()) {
            val (_, delivery) = iterator.next()
            if (onlyInstance != null && delivery.instanceId != onlyInstance) continue
            val qaTry = tries.findById(delivery.qaTryId)
            if (now >= delivery.expiresAt || qaTry == null || qaTry.status !in setOf("STARTING", "RUNNING")) {
                iterator.remove()
                logger.info("SDK ACTION 복구 종료 [instanceId={}, requestId={}, accepted={}]",
                    delivery.instanceId, delivery.requestId, delivery.accepted)
                continue
            }
            val current = sessions.sessionIdentity(delivery.instanceId) ?: continue
            if (sessions.deliveryProcessId(delivery.instanceId) != delivery.processId) {
                iterator.remove()
                logger.warn("SDK 프로세스 변경으로 ACTION 자동 재실행 중단 [instanceId={}, requestId={}]",
                    delivery.instanceId, delivery.requestId)
                continue
            }
            val sessionChanged = current !== delivery.session
            val interval = if (delivery.accepted) RESULT_RETRY_MILLIS else ACK_RETRY_MILLIS
            if (delivery.attempts >= MAX_ATTEMPTS || (!sessionChanged && now - delivery.lastSentAt < interval)) continue
            delivery.session = current
            delivery.lastSentAt = now
            delivery.attempts++
            // queue 성공은 ACK가 아니다. 같은 JSON과 id를 유지해 게임 쪽 journal이 중복을 가른다.
            val queued = sessions.sendRawToSession(delivery.instanceId, current, delivery.processId, delivery.json)
            logger.info("SDK ACTION 재전송 [instanceId={}, requestId={}, attempt={}, accepted={}, queued={}]",
                delivery.instanceId, delivery.requestId, delivery.attempts, delivery.accepted, queued)
        }
    }

    internal suspend fun pendingCount(): Int = mutex.withLock { pending.size }

    internal companion object {
        const val LIFETIME_MILLIS = 25_000L
        const val ACK_RETRY_MILLIS = 2_000L
        const val RESULT_RETRY_MILLIS = 5_000L
        const val MAX_ATTEMPTS = 6
        const val MAX_PENDING = 1024
    }
}
