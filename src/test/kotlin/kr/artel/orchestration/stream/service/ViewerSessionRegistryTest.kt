package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.TooManyRequestsException
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class ViewerSessionRegistryTest {
    private val registry = ViewerSessionRegistry(ObjectMapper())

    @Test
    fun `같은 게임의 viewer들이 각자 stream ID로 frame을 받는다`() {
        val first = registry.admit("game", "viewer-a", socket()).viewer
        val second = registry.admit("game", "viewer-b", socket()).viewer
        registry.sendFrame("viewer-a", 7, "frame-a")
        registry.sendFrame("viewer-b", 7, "frame-b")
        val mapper = ObjectMapper()
        StepVerifier.create(first.outbound().take(1)).assertNext {
            assertThat(mapper.readTree(it)["streamId"].asText()).isEqualTo("viewer-a")
            assertThat(mapper.readTree(it)["jpeg"].asText()).isEqualTo("frame-a")
        }.verifyComplete()
        StepVerifier.create(second.outbound().take(1)).assertNext {
            assertThat(mapper.readTree(it)["streamId"].asText()).isEqualTo("viewer-b")
            assertThat(mapper.readTree(it)["jpeg"].asText()).isEqualTo("frame-b")
        }.verifyComplete()
        assertThat(registry.findByStream("viewer-a")).isSameAs(first)
        assertThat(registry.findByStream("viewer-b")).isSameAs(second)
    }

    @Test
    fun `viewer 하나를 닫아도 다른 viewer는 frame을 계속 받는다`() {
        val first = registry.admit("game", "viewer-a", socket()).viewer
        val second = registry.admit("game", "viewer-b", socket()).viewer
        assertThat(registry.release(first)).isTrue()
        assertThat(registry.findByStream("viewer-a")).isNull()
        assertThat(registry.findByStream("viewer-b")).isSameAs(second)
        assertThat(registry.sendFrame("viewer-a", 8, "stale")).isFalse()
        assertThat(registry.sendFrame("viewer-b", 8, "current")).isTrue()
        StepVerifier.create(second.outbound().take(1)).assertNext {
            assertThat(ObjectMapper().readTree(it)["frameId"].asLong()).isEqualTo(8)
        }.verifyComplete()
    }

    @Test
    fun `늦은 close가 같은 ID로 새로 등록된 소켓을 지우지 않는다`() {
        val old = registry.admit("game", "viewer", socket()).viewer
        assertThat(registry.release(old)).isTrue()
        val current = registry.admit("game", "viewer", socket()).viewer
        assertThat(registry.release(old)).isFalse()
        assertThat(registry.findByStream("viewer")).isSameAs(current)
        assertThat(registry.sendFrame("viewer", 1, "current")).isTrue()
    }

    @Test
    fun `동시 접속 8개를 넘으면 새 접속만 거절하고 기존 viewer는 유지한다`() {
        val viewers = (1..8).map { registry.admit("game", "viewer-$it", socket()).viewer }
        val rejected = socket()
        assertThrows<TooManyRequestsException> { registry.admit("game", "viewer-9", rejected) }
        viewers.forEach {
            assertThat(registry.findByStream(it.streamId)).isSameAs(it)
            Mockito.verify(it.session, Mockito.never()).close(Mockito.any())
        }
        assertThat(registry.findByStream("viewer-9")).isNull()
        assertThat(registry.admit("other-game", "other-viewer", socket()).viewer.instanceId).isEqualTo("other-game")
    }

    @Test
    fun `동시 admission과 해제에서도 인스턴스 상한을 지킨다`() {
        val pool = Executors.newFixedThreadPool(16)
        val start = CountDownLatch(1)
        try {
            val pending = (1..32).map { id -> pool.submit<ViewerSessionRegistry.ViewerSession?> {
                start.await()
                try { registry.admit("game", "concurrent-$id", socket()).viewer }
                catch (_: TooManyRequestsException) { null }
            } }
            start.countDown()
            val viewers = pending.mapNotNull { it.get(5, TimeUnit.SECONDS) }
            assertThat(viewers).hasSize(8)
            val closing = viewers.map { viewer -> pool.submit<Boolean> { registry.release(viewer) } }
            assertThat(closing.map { it.get(5, TimeUnit.SECONDS) }).containsOnly(true)
            viewers.forEach { assertThat(registry.findByStream(it.streamId)).isNull() }
            val replacements = (1..8).map { registry.admit("game", "replacement-$it", socket()).viewer }
            viewers.forEach { assertThat(registry.release(it)).isFalse() }
            replacements.forEach { assertThat(registry.findByStream(it.streamId)).isSameAs(it) }
        } finally { pool.shutdownNow() }
    }

    @Test
    fun `viewer의 control과 frame queue는 서로 독립적이다`() {
        val viewer = registry.admit("game", "viewer", socket()).viewer
        StepVerifier.create(viewer.outbound())
            .then {
                registry.send(viewer, mapOf("type" to "STREAM_READY"))
                registry.relayToStream("viewer", """{"type":"STREAM_ERROR"}""")
                registry.release(viewer)
            }
            .expectNext("""{"type":"STREAM_READY"}""")
            .expectNext("""{"type":"STREAM_ERROR"}""")
            .verifyComplete()
    }

    private fun socket(): WebSocketSession = Mockito.mock(WebSocketSession::class.java)
}
