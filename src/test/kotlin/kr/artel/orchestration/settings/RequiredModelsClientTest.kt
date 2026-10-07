package kr.artel.orchestration.settings

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.settings.config.AgentModelsProperties
import kr.artel.orchestration.settings.service.RequiredModelsClient
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.Test
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.atomic.AtomicInteger

/** [RequiredModelsClient] 가 성공한 응답만 TTL 동안 들고 있는지, 느린 agent 를 짧은 timeout 으로 끊는지 본다. */
class RequiredModelsClientTest {

    private class MutableClock(private var now: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC
        override fun withZone(zone: java.time.ZoneId?) = this
        override fun instant(): Instant = now
        fun advance(duration: Duration) { now = now.plus(duration) }
    }

    private val calls = AtomicInteger()
    private var status = 200
    private var delay = Duration.ZERO
    private val server: DisposableServer = HttpServer.create().port(0).route { routes ->
        routes.get("/internal/models/required") { _, response ->
            calls.incrementAndGet()
            response.status(status).header("Content-Type", "application/json")
                .sendString(Mono.just("""{"slugs":["a/one","b/two","a/one"]}""").delayElement(delay))
        }
    }.bindNow()

    @AfterEach
    fun stop() = server.disposeNow()

    private fun client(clock: Clock, timeout: Duration = Duration.ofSeconds(3)) = RequiredModelsClient(
        AgentModelsProperties(
            baseUrl = "http://localhost:${server.port()}/internal",
            requiredModelsTimeout = timeout,
            requiredModelsTtl = Duration.ofMinutes(5)
        ),
        clock
    )

    @Test
    fun `keeps a successful answer for the ttl and asks again after it`(): Unit = runBlocking {
        val clock = MutableClock(Instant.parse("2026-10-06T00:00:00Z"))
        val client = client(clock)

        assertThat(client.slugs()).containsExactly("a/one", "b/two")
        assertThat(client.slugs()).containsExactly("a/one", "b/two")
        assertThat(calls.get()).isEqualTo(1)

        clock.advance(Duration.ofMinutes(5).plusSeconds(1))
        assertThat(client.slugs()).containsExactly("a/one", "b/two")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `does not keep a failure`(): Unit = runBlocking {
        val client = client(MutableClock(Instant.parse("2026-10-06T00:00:00Z")))
        status = 500

        assertThat(client.slugs()).isNull()
        status = 200
        assertThat(client.slugs()).containsExactly("a/one", "b/two")
        assertThat(calls.get()).isEqualTo(2)
    }

    @Test
    fun `gives up on an agent that answers slower than the timeout`(): Unit = runBlocking {
        val client = client(MutableClock(Instant.parse("2026-10-06T00:00:00Z")), timeout = Duration.ofMillis(200))
        delay = Duration.ofSeconds(2)

        assertThat(client.slugs()).isNull()
    }
}
