package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.test.StepVerifier
import java.time.Duration
import java.util.concurrent.atomic.AtomicInteger

/**
 * 같은 인스턴스의 새 연결이 앞 세션의 자리를 넘겨받는 조건(ARTEL-1234).
 *
 * 앞 세션이 ping 에 답하면 지금처럼 거절하고, 답하지 않으면 앞 세션을 닫고 새 연결을 받는다.
 */
class SessionReplacementTest {

    private val sessionManager = SessionManager(ObjectMapper())
    private val probeTimeout = Duration.ofMillis(200)

    @Test
    fun `refuses the new connection while the incumbent answers the ping`(): Unit = runBlocking {
        val incumbent = closableSession()
        val newcomer = closableSession()
        val probes = AtomicInteger()
        sessionManager.registerReplacingSilent("1", incumbent, { probes.incrementAndGet(); true }, probeTimeout)

        val registration = sessionManager.registerReplacingSilent("1", newcomer, { true }, probeTimeout)

        assertThat(registration).isNull()
        assertThat(probes.get()).isEqualTo(1)
        assertThat(sessionManager.getSession("1")).isSameAs(incumbent)
        verify(incumbent, never()).close(any())
    }

    @Test
    fun `replaces an incumbent that does not answer and closes it with 4009`(): Unit = runBlocking {
        val incumbent = closableSession()
        val newcomer = closableSession()
        val incumbentOutbound = sessionManager.registerReplacingSilent("1", incumbent, { false }, probeTimeout)!!.outbound

        val registration = sessionManager.registerReplacingSilent("1", newcomer, { true }, probeTimeout)

        assertThat(registration).isNotNull()
        assertThat(registration!!.replaced).isTrue()
        assertThat(sessionManager.getSession("1")).isSameAs(newcomer)
        verify(incumbent).close(SESSION_REPLACED)
        // 앞 세션의 전송 큐는 닫힌다. 그 세션의 전송 파이프라인이 끝나야 그 handler 도 끝난다.
        StepVerifier.create(incumbentOutbound).verifyComplete()
        // 늦게 끝난 앞 세션의 정리가 새 세션의 자리를 비우지 않는다.
        sessionManager.removeSession("1", incumbent)
        assertThat(sessionManager.getSession("1")).isSameAs(newcomer)
    }

    @Test
    fun `an incumbent registered without liveness is never replaced`(): Unit = runBlocking {
        val incumbent = closableSession()
        sessionManager.register("1", incumbent)

        val registration = sessionManager.registerReplacingSilent("1", closableSession(), { true }, probeTimeout)

        assertThat(registration).isNull()
        assertThat(sessionManager.getSession("1")).isSameAs(incumbent)
    }

    @Test
    fun `a heartbeat answers a probe only when a frame arrives after the ping`(): Unit = runBlocking {
        val session = closableSession()
        val heartbeat = SdkHeartbeat("1", session, Duration.ofSeconds(15), Duration.ofSeconds(45))
        val sent = mutableListOf<WebSocketMessage>()
        val subscription = heartbeat.pings(Mono.never()).subscribe { sent += it }

        assertThat(heartbeat.answersPing(probeTimeout)).isFalse()
        assertThat(sent).hasSize(1)

        val waiting = async { heartbeat.answersPing(Duration.ofSeconds(2)) }
        delay(50)
        heartbeat.seen()
        val answered: Boolean = waiting.await()
        assertThat(answered).isTrue()
        assertThat(sent).hasSize(2)
        subscription.dispose()
    }

    @Test
    fun `a heartbeat whose pings ended cannot answer`(): Unit = runBlocking {
        val heartbeat = SdkHeartbeat("1", closableSession(), Duration.ofSeconds(15), Duration.ofSeconds(45))
        heartbeat.pings(Mono.empty()).subscribe()

        assertThat(heartbeat.answersPing(probeTimeout)).isFalse()
    }

    private fun closableSession(): WebSocketSession = mock(WebSocketSession::class.java).also {
        `when`(it.pingMessage(any())).thenReturn(mock(WebSocketMessage::class.java))
        `when`(it.close(any())).thenReturn(Mono.empty())
    }
}
