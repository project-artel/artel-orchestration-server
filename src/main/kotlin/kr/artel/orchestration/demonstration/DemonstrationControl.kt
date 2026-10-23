package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.springframework.stereotype.Component
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap

data class ConnectedDemonstrationSdk(val connectionId: String, val capabilities: DemonstrationCapabilities)

@Component
class DemonstrationRuntime {
    private val sdk = ConcurrentHashMap<String, ConnectedDemonstrationSdk>()
    private val controllers = ConcurrentHashMap<String, String>()
    fun announce(instanceId: String, connectionId: String, capabilities: DemonstrationCapabilities) {
        sdk[instanceId] = ConnectedDemonstrationSdk(connectionId, capabilities)
    }
    fun connection(instanceId: String, connectionId: String?): ConnectedDemonstrationSdk? =
        sdk[instanceId]?.takeIf { it.connectionId == connectionId && it.capabilities.protocolVersion == 1 }
    fun disconnected(instanceId: String, connectionId: String) { sdk.computeIfPresent(instanceId) { _, old -> old.takeUnless { it.connectionId == connectionId } } }
    fun claim(sessionId: String, socketId: String): Boolean = controllers.putIfAbsent(sessionId, socketId) == null
    fun release(sessionId: String, socketId: String) { controllers.remove(sessionId, socketId) }
}

@Service
class DemonstrationGuard(private val repo: DemonstrationRepository, private val transactions: TransactionalOperator) {
    suspend fun requireQaAvailable(instanceId: Long) {
        repo.lockInstance(instanceId)
        if (repo.active(instanceId) != null) throw ConflictException("사람이 게임을 조작하고 있습니다.")
    }
    suspend fun <T> dispatch(instanceId: Long, identity: DemonstrationIdentity?, action: suspend () -> T): T =
        transactions.executeAndAwait {
            repo.lockInstance(instanceId)
            val current = repo.active(instanceId)
            if (identity == null) {
                if (current != null) throw ConflictException("시연 중에는 다른 입력을 보낼 수 없습니다.")
            } else if (current == null || current.status != "RECORDING" || !current.controlReady || current.id.toString() != identity.sessionId || current.generation != identity.generation) {
                throw ConflictException("시연 제어권이 만료되었습니다.")
            }
            action()
        }
}

@Component
class DemonstrationCapabilitiesHandler(private val mapper: ObjectMapper, private val runtime: DemonstrationRuntime) : SdkMessageHandler {
    override val messageType = "DEMONSTRATION_CAPABILITIES"
    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val value = mapper.readValue(payloadText, DemonstrationCapabilities::class.java)
        runtime.announce(instanceId, session.id, value)
    }
}

@Component
class DemonstrationControlResultHandler(private val mapper: ObjectMapper, private val service: DemonstrationService) : SdkMessageHandler {
    override val messageType = "DEMONSTRATION_CONTROL_RESULT"
    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        service.controlResult(instanceId.toLong(), session.id, mapper.readValue(payloadText, DemonstrationControlResult::class.java))
    }
}
