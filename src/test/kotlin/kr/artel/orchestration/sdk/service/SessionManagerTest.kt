package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession

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

        assertThat(sessionManager.registerSession("1", first)).isTrue()
        assertThat(sessionManager.registerSession("1", second)).isFalse()
        assertThat(sessionManager.getSession("1")).isSameAs(first)
    }

    @Test
    fun `a stale session cannot evict the live one`() {
        val stale = Mockito.mock(WebSocketSession::class.java)
        val live = Mockito.mock(WebSocketSession::class.java)

        sessionManager.registerSession("1", stale)
        sessionManager.removeSession("1", stale)
        sessionManager.registerSession("1", live)

        // 늦게 끊긴 좀비 연결이 자기 종료 처리를 다시 돌려도 살아 있는 자리를 비워선 안 된다.
        sessionManager.removeSession("1", stale)

        assertThat(sessionManager.hasSession("1")).isTrue()
        assertThat(sessionManager.getSession("1")).isSameAs(live)
    }

    @Test
    fun `frees the slot when the session that owns it goes away`() {
        val session = Mockito.mock(WebSocketSession::class.java)

        sessionManager.registerSession("1", session)
        sessionManager.removeSession("1", session)

        assertThat(sessionManager.hasSession("1")).isFalse()
    }
}
