package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.sdk.service.SessionManager
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.web.reactive.socket.WebSocketSession

class QaInputPreflightTest {
    private val mapper = jacksonObjectMapper()
    private val sessions = SessionManager(mapper)
    private val socket = mock(WebSocketSession::class.java)
    private val database = mock(DatabaseClient::class.java)
    private val sent = mutableListOf<String>()

    private fun preflight(timeoutMillis: Long = 1000): QaInputPreflight {
        sessions.register("4", socket)!!.subscribe { sent += it }
        return QaInputPreflight(sessions, database, mapper, QaInputPreflightProperties(timeoutMillis))
    }

    @Test
    fun `virtual은 SDK 왕복과 pending을 만들지 않는다`(): Unit = runBlocking {
        val preflight = preflight()
        preflight.requireAvailable(4, QaInputMode.VIRTUAL)
        assertThat(sent).isEmpty()
        assertThat(preflight.pendingCount()).isZero()
    }

    @Test
    fun `현재 session의 일치하는 성공 응답 뒤에만 preflight를 완료한다`(): Unit = runBlocking {
        val preflight = preflight()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            preflight.awaitResult("4", socket, 71, QaInputMode.OS)
        }
        assertThat(result.isCompleted).isFalse()
        val action = mapper.readTree(sent.single())
        assertThat(action.path("actions").first().path("method").asText()).isEqualTo("configure_input")
        assertThat(action.path("actions").first().path("params").first().path("mode").asText()).isEqualTo("os")
        assertThat(preflight.handle("4", response(71, true), socket)).isTrue()
        result.await()
        assertThat(preflight.pendingCount()).isZero()
    }

    @Test
    fun `다른 instance와 request와 이전 session의 결과는 완료시키지 않는다`(): Unit = runBlocking {
        val preflight = preflight()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            preflight.awaitResult("4", socket, 72, QaInputMode.OS)
        }
        assertThat(preflight.handle("5", response(72, true), socket)).isFalse()
        assertThat(preflight.handle("4", response(73, true), socket)).isFalse()
        assertThat(preflight.handle("4", response(72, true), mock(WebSocketSession::class.java))).isFalse()
        assertThat(result.isCompleted).isFalse()
        result.cancelAndJoin()
        assertThat(preflight.pendingCount()).isZero()
    }

    @Test
    fun `구 SDK의 Unsupported method는 os 사용 불가로 반환한다`(): Unit = runBlocking {
        val preflight = preflight()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            runCatching { preflight.awaitResult("4", socket, 74, QaInputMode.OS) }.exceptionOrNull()
        }
        preflight.handle("4", response(74, false, "Unsupported method: configure_input"), socket)
        val failure = result.await() as ApiException
        assertThat(failure.code).isEqualTo("sdk_input_unavailable")
        assertThat(failure.message).contains("Unsupported method")
        assertThat(preflight.pendingCount()).isZero()
    }

    @Test
    fun `결과가 없으면 timeout 오류로 끝내고 pending을 지운다`(): Unit = runBlocking {
        val preflight = preflight(timeoutMillis = 1)
        val failure = runCatching { preflight.awaitResult("4", socket, 75, QaInputMode.OS) }.exceptionOrNull() as ApiException
        assertThat(failure.code).isEqualTo("sdk_input_timeout")
        assertThat(preflight.pendingCount()).isZero()
    }

    @Test
    fun `session이 교체되면 앞 session의 성공 응답도 거절한다`(): Unit = runBlocking {
        val preflight = preflight()
        val result = async(start = CoroutineStart.UNDISPATCHED) {
            preflight.awaitResult("4", socket, 76, QaInputMode.OS)
        }
        sessions.removeSession("4", socket)
        val replacement = mock(WebSocketSession::class.java)
        sessions.register("4", replacement)!!.subscribe()
        assertThat(preflight.handle("4", response(76, true), socket)).isFalse()
        assertThat(preflight.handle("4", response(76, true), replacement)).isFalse()
        assertThat(result.isCompleted).isFalse()
        result.cancelAndJoin()
        assertThat(preflight.pendingCount()).isZero()
    }

    private fun response(id: Long, success: Boolean, error: String? = null): String = mapper.writeValueAsString(
        InputPreflightResult(id, listOf(InputPreflightActionResult(id, success, error))),
    )
}
