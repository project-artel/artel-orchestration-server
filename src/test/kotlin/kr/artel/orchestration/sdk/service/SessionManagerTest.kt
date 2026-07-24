package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier

/**
 * 한 게임 인스턴스에 두 연결이 겹치는 순간을 다룬다. 만료 없는 자격증명으로 재연결이 잦아지면
 * 이 상황이 예외가 아니라 일상이 된다.
 */
class SessionManagerTest {

    private val sessionManager = SessionManager(ObjectMapper())

    @Test
    fun `refuses to register a second session for the same instance`() {
        val first = Mockito.mock(WebSocketSession::class.java)
        val second = Mockito.mock(WebSocketSession::class.java)

        assertThat(sessionManager.register("1", first)).isNotNull()
        assertThat(sessionManager.register("1", second)).isNull()
        assertThat(sessionManager.getSession("1")).isSameAs(first)
    }

    @Test
    fun `a stale session cannot evict the live one`() {
        val stale = Mockito.mock(WebSocketSession::class.java)
        val live = Mockito.mock(WebSocketSession::class.java)

        sessionManager.register("1", stale)
        sessionManager.removeSession("1", stale)
        sessionManager.register("1", live)

        // 늦게 끊긴 좀비 연결이 자기 종료 처리를 다시 돌려도 살아 있는 자리를 비워선 안 된다.
        sessionManager.removeSession("1", stale)

        assertThat(sessionManager.hasSession("1")).isTrue()
        assertThat(sessionManager.getSession("1")).isSameAs(live)
    }

    @Test
    fun `frees the slot when the session that owns it goes away`() {
        val session = Mockito.mock(WebSocketSession::class.java)

        sessionManager.register("1", session)
        sessionManager.removeSession("1", session)

        assertThat(sessionManager.hasSession("1")).isFalse()
    }

    /**
     * 전송 큐는 세션당 하나뿐이다. 여러 발신자가 같은 세션에 동시에 write하지 못하게 막는
     * 장치이므로, 보낸 순서가 그대로 나오는지가 이 큐의 유일한 계약이다.
     */
    @Test
    fun `queues outbound messages in order on the session's single stream`() {
        val session = Mockito.mock(WebSocketSession::class.java)
        val outbound = requireNotNull(sessionManager.register("1", session))

        StepVerifier.create(outbound)
            .then {
                sessionManager.send("1", mapOf("type" to "FIRST")).block()
                sessionManager.send("1", mapOf("type" to "SECOND")).block()
                sessionManager.removeSession("1", session)
            }
            .expectNext("""{"type":"FIRST"}""")
            .expectNext("""{"type":"SECOND"}""")
            .verifyComplete()
    }

    /**
     * 끊긴 인스턴스로 보내는 것은 조용히 버려질 일이 아니다. 액션이든 시그널링이든 상대가
     * 없다는 사실을 호출자가 알아야 재시도나 상태 표시를 결정할 수 있다.
     */
    @Test
    fun `fails when nothing is connected for the instance`() {
        StepVerifier.create(sessionManager.send("nobody", mapOf("type" to "ACTION")))
            .verifyError(IllegalArgumentException::class.java)
    }
}
