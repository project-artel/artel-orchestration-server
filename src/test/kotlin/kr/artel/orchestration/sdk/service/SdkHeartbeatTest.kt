package kr.artel.orchestration.sdk.service

import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.never
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import reactor.test.StepVerifier
import java.time.Duration

/**
 * close frame 없이 끊긴 SDK 세션을 서버가 정리하는지 본다(ARTEL-1111).
 *
 * 그런 세션이 남으면 인스턴스는 계속 연결됨으로 보이고 SDK 의 재연결은 4002 로 거절된다.
 */
class SdkHeartbeatTest {

    private val interval = Duration.ofSeconds(15)
    private val timeout = Duration.ofSeconds(45)

    private val session = mock(WebSocketSession::class.java).also {
        `when`(it.pingMessage(any())).thenReturn(mock(WebSocketMessage::class.java))
        `when`(it.close(any())).thenReturn(Mono.empty())
    }

    @Test
    fun `closes a session that sends nothing for the idle timeout`() {
        StepVerifier.withVirtualTime {
            SdkHeartbeat("1", session, interval, timeout).pings(Mono.never())
        }
            .thenAwait(Duration.ofSeconds(30))
            .expectNextCount(2)
            .thenAwait(Duration.ofSeconds(15))
            .verifyComplete()

        verify(session).close(HEARTBEAT_TIMEOUT)
    }

    @Test
    fun `keeps a session that answers every ping`() {
        lateinit var heartbeat: SdkHeartbeat
        StepVerifier.withVirtualTime {
            heartbeat = SdkHeartbeat("1", session, interval, timeout)
            heartbeat.pings(Mono.never())
        }
            .thenAwait(Duration.ofSeconds(15))
            .expectNextCount(1)
            .then { heartbeat.seen() }
            .thenAwait(Duration.ofSeconds(30))
            .expectNextCount(2)
            .then { heartbeat.seen() }
            .thenAwait(Duration.ofSeconds(30))
            .expectNextCount(2)
            .thenCancel()
            .verify()

        verify(session, never()).close(any())
    }

    /** 이 Flux 가 끝나지 않으면 `session.send` 가 끝나지 않아 핸들러가 반환되지 않는다. */
    @Test
    fun `stops pinging once the receive side has ended`() {
        val receiveEnded = Sinks.empty<Void>()
        StepVerifier.withVirtualTime {
            SdkHeartbeat("1", session, interval, timeout).pings(receiveEnded.asMono())
        }
            .thenAwait(Duration.ofSeconds(15))
            .expectNextCount(1)
            .then { receiveEnded.tryEmitEmpty() }
            .verifyComplete()

        verify(session, never()).close(any())
    }
}
