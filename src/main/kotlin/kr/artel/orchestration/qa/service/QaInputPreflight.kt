package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.withTimeoutOrNull
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.SessionManager
import org.springframework.boot.context.properties.ConfigurationProperties
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import org.springframework.web.reactive.socket.WebSocketSession
import java.util.concurrent.ConcurrentHashMap
import java.util.Collections
import java.util.WeakHashMap

@ConfigurationProperties("artel.qa.input-preflight")
data class QaInputPreflightProperties(
    /** SDK 권한 검사 응답을 기다리는 상한. 사용자 권한 승인을 자동으로 기다리지는 않는다. */
    val timeoutMillis: Long = 10_000,
) {
    init { require(timeoutMillis > 0) }
}

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InputPreflightResult(
    val requestId: Long? = null,
    val results: List<InputPreflightActionResult> = emptyList(),
)

@JsonIgnoreProperties(ignoreUnknown = true)
internal data class InputPreflightActionResult(
    val id: Long? = null,
    val success: Boolean? = null,
    val error: String? = null,
)

/** preflight 결과만 requestId와 현재 SDK session으로 구분해 기다리는 시작 경계. */
@Service
@EnableConfigurationProperties(QaInputPreflightProperties::class)
class QaInputPreflight(
    private val sessions: SessionManager,
    private val databaseClient: DatabaseClient,
    private val mapper: ObjectMapper,
    private val properties: QaInputPreflightProperties,
) {
    private data class Pending(
        val instanceId: String,
        val session: WebSocketSession,
        val result: CompletableDeferred<InputPreflightActionResult>,
    )

    private val pending = ConcurrentHashMap<Long, Pending>()
    private val confirmedSessions = Collections.synchronizedMap(WeakHashMap<WebSocketSession, String>())

    suspend fun requireAvailable(gameInstanceId: Long, mode: QaInputMode, force: Boolean = false): WebSocketSession? {
        // 구 SDK를 사용하는 기존 virtual run에는 새 action을 보내지 않는다.
        if (mode == QaInputMode.VIRTUAL) return null
        val instanceId = gameInstanceId.toString()
        val session = sessions.getSession(instanceId) ?: throw SdkDisconnectedException()
        if (!force && confirmedSessions[session] == instanceId) return session
        confirmedSessions.remove(session)
        val requestId = databaseClient.sql("SELECT nextval(pg_get_serial_sequence('qa_log', 'id')) AS id")
            .map { row -> requireNotNull(row.get("id", Long::class.javaObjectType)) }
            .one().awaitSingle()
        awaitResult(instanceId, session, requestId, mode)
        return session
    }

    internal suspend fun awaitResult(
        instanceId: String, session: WebSocketSession, requestId: Long, mode: QaInputMode,
    ) {
        val request = Pending(instanceId, session, CompletableDeferred())
        check(pending.putIfAbsent(requestId, request) == null)
        try {
            if (sessions.getSession(instanceId) !== session) throw SdkDisconnectedException()
            val sent = sessions.sendAction(instanceId, ActionResponseDto(
                id = requestId,
                actions = listOf(ActionItemDto(
                    id = requestId, method = "configure_input", params = listOf(InputModeConfiguration(mode.wire)),
                )),
            ), expectedSession = session)
            if (!sent || sessions.getSession(instanceId) !== session) throw SdkDisconnectedException()
            val result = withTimeoutOrNull(properties.timeoutMillis) { request.result.await() }
                ?: throw UpstreamUnavailableException(
                    "SDK did not confirm OS input availability before the deadline.", code = "sdk_input_timeout",
                )
            if (result.success != true) throw BadRequestException(
                "SDK rejected OS input: ${result.error ?: "invalid configure_input result"}",
                code = "sdk_input_unavailable",
            )
            if (sessions.getSession(instanceId) !== session) throw SdkDisconnectedException()
            confirmedSessions[session] = instanceId
        } finally {
            pending.remove(requestId, request)
            request.result.cancel()
        }
    }

    internal data class InputModeConfiguration(val mode: String)

    fun handle(instanceId: String, payloadText: String, session: WebSocketSession): Boolean {
        if (pending.isEmpty()) return false
        val response = runCatching { mapper.readValue(payloadText, InputPreflightResult::class.java) }.getOrNull()
            ?: return false
        val requestId = response.requestId ?: return false
        val request = pending[requestId] ?: return false
        if (request.instanceId != instanceId || request.session !== session || sessions.getSession(instanceId) !== session)
            return false
        val result = response.results.singleOrNull()?.takeIf { it.id == requestId }
            ?: InputPreflightActionResult(error = "Invalid configure_input result")
        request.result.complete(result)
        return true
    }

    internal fun pendingCount(): Int = pending.size

    fun forget(gameInstanceId: Long) {
        val instanceId = gameInstanceId.toString()
        synchronized(confirmedSessions) {
            confirmedSessions.entries.removeIf { it.value == instanceId }
        }
    }
}
