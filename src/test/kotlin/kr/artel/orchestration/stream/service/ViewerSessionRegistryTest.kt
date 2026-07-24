package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier

/**
 * 뷰어 자리는 인스턴스당 하나고, 새 연결이 앞 연결을 밀어낸다.
 *
 * 그래서 밀려난 세션의 종료 처리는 자기를 대신한 세션이 이미 등록된 뒤에 돈다. 교체가 예외가
 * 아니라 정상 경로인 정책이라, "자기 자리일 때만 비운다"가 지켜지는지는 주석이 아니라 테스트가
 * 붙들어야 한다.
 */
class ViewerSessionRegistryTest {

    private val registry = ViewerSessionRegistry(ObjectMapper())

    @Test
    fun `the newest viewer takes the instance and hands back the displaced one`() {
        val first = Mockito.mock(WebSocketSession::class.java)
        val second = Mockito.mock(WebSocketSession::class.java)

        val incumbent = registry.admit("1", "stream-a", first)
        assertThat(incumbent.displaced).isNull()

        val takeover = registry.admit("1", "stream-b", second)
        assertThat(takeover.displaced).isSameAs(incumbent.viewer)
    }

    @Test
    fun `a displaced viewer cleaning up does not free its replacement's slot`() {
        val first = Mockito.mock(WebSocketSession::class.java)
        val second = Mockito.mock(WebSocketSession::class.java)

        val displaced = registry.admit("1", "stream-a", first).viewer
        val live = registry.admit("1", "stream-b", second).viewer

        // 밀려난 쪽의 정리가 뒤늦게 돈다. 여기서 자리를 비우면 연결은 되어 있는데 서버가
        // 찾지 못하는 브라우저가 남는다.
        assertThat(registry.release(displaced)).isFalse()

        assertThat(registry.findByStream("stream-b")).isSameAs(live)
        assertThat(registry.relayToStream("stream-b", """{"type":"WEBRTC_ICE"}""")).isTrue()
    }

    /**
     * 돌려주는 값이 "방금까지 현재 뷰어였는가"인 이유는, 호출자가 이 값으로 SDK에 스트림 중단을
     * 보낼지 정하기 때문이다. 밀려난 세션이 중단을 보내면 방금 시작한 스트림이 꺼진다.
     */
    @Test
    fun `releasing the current viewer reports that the instance is now free`() {
        val session = Mockito.mock(WebSocketSession::class.java)
        val viewer = registry.admit("1", "stream-a", session).viewer

        assertThat(registry.release(viewer)).isTrue()
        assertThat(registry.findByStream("stream-a")).isNull()
    }

    /**
     * 교체 직후에는 밀려난 쪽의 ICE 후보가 아직 날아오고 있다. 그것이 새 협상에 섞이면 연결이
     * 조용히 깨지므로, 자리에서 내려온 streamId 앞으로 온 시그널링은 버려야 한다.
     */
    @Test
    fun `drops signalling addressed to a stream that no longer has a viewer`() {
        val first = Mockito.mock(WebSocketSession::class.java)
        val second = Mockito.mock(WebSocketSession::class.java)

        registry.admit("1", "stream-a", first)
        registry.admit("1", "stream-b", second)

        assertThat(registry.relayToStream("stream-a", """{"type":"WEBRTC_ICE"}""")).isFalse()
    }

    @Test
    fun `queues outbound messages on the viewer's single stream`() {
        val session = Mockito.mock(WebSocketSession::class.java)
        val viewer = registry.admit("1", "stream-a", session).viewer

        StepVerifier.create(viewer.outbound())
            .then {
                registry.send(viewer, mapOf("type" to "STREAM_READY"))
                registry.relayToStream("stream-a", """{"type":"WEBRTC_OFFER"}""")
                registry.release(viewer)
            }
            .expectNext("""{"type":"STREAM_READY"}""")
            .expectNext("""{"type":"WEBRTC_OFFER"}""")
            .verifyComplete()
    }
}
