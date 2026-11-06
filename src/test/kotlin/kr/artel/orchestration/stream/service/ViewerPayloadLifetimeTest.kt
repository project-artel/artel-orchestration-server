package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import io.netty.buffer.PooledByteBufAllocator
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.core.io.buffer.NettyDataBufferFactory
import org.springframework.http.HttpHeaders
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.HandshakeInfo
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import java.net.URI
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicReference

/**
 * **뷰어 메시지의 페이로드는 프레임이 살아 있는 동안 읽어야 한다** (ARTEL-526).
 *
 * 이 파일이 지키는 것 하나다. `payloadAsText` 를 `mono { }` **안에서** 읽으면 그 읽기가 다음
 * 틱으로 밀리고, 그 사이 Netty 가 프레임 버퍼를 반납해
 * `IllegalReferenceCountException: refCnt: 0` 이 난다. 뷰어가 처음 보내는 것이 10초 주기의
 * `RENEW` 라, stage 에서는 연결 **144밀리초** 만에 소켓이 닫혔다.
 *
 * **진짜 소켓을 붙이는 통합 테스트로는 못 잡는다.** 실제로 써 봤고 통과했다 — 반납 시점이
 * 플랫폼에 달렸기 때문이다. 사고가 난 stage 는 epoll 에 `PooledUnsafeDirectByteBuf` 였고,
 * 로컬 테스트는 그 조합이 아니라 경합이 안 생긴다. 그래서 타이밍에 기대는 대신 **여기서
 * 직접 반납한다** — 수신 흐름이 메시지를 내보낸 직후 버퍼를 놓아, 어느 플랫폼에서 돌든
 * 늦은 읽기는 반드시 실패한다.
 */
class ViewerPayloadLifetimeTest {

    private val allocator = PooledByteBufAllocator.DEFAULT
    private val bufferFactory = NettyDataBufferFactory(allocator)

    @Test
    fun `프레임이 반납된 뒤에 페이로드를 읽지 않는다`(): Unit = runBlocking {
        val handler = newHandler(Mockito.mock(StreamSignalRelay::class.java))
        val failure = AtomicReference<Throwable>()

        val session = ReleasingSession(
            uri = URI("ws://localhost/ws/viewer?instanceId=7"),
            payload = """{"type":"RENEW"}""",
        )

        handler.handle(session)
            .doOnError(failure::set)
            .onErrorResume { Mono.empty() }
            .block(Duration.ofSeconds(5))

        // 반납된 버퍼를 읽었다면 여기서 잡힌다. 소켓이 닫히는 것이 그다음이다.
        assertThat(failure.get())
            .withFailMessage(
                "반납된 프레임 버퍼를 읽었다 — 페이로드는 mono { } 밖에서 뽑아야 한다: %s",
                failure.get(),
            )
            .isNull()

    }

    private fun newHandler(relay: StreamSignalRelay): ViewerWebSocketHandler {
        val instances = Mockito.mock(GameInstanceRepository::class.java)
        val sessions = Mockito.mock(SessionManager::class.java)
        // resolve 는 실제 구현을 쓴다. Mockito.any() 는 코틀린의 non-null 파라미터에서
        // null 을 돌려주어 NPE 가 나고, 이 클래스는 jwt.subject 를 읽는 것이 전부다.
        val resolver = SessionUserResolver()

        runBlocking {
            Mockito.`when`(instances.findAccessibleByIdForMember(Mockito.eq(7L), Mockito.eq(42L)))
                .thenReturn(Mockito.mock(GameInstanceEntity::class.java))
        }
        Mockito.`when`(sessions.hasSession("7")).thenReturn(true)

        return ViewerWebSocketHandler(
            ObjectMapper(),
            StreamProperties(),
            instances,
            resolver,
            sessions,
            ViewerSessionRegistry(ObjectMapper()),
            relay,
        )
    }

    /**
     * 메시지를 하나 내보내고 **곧바로 그 버퍼를 반납하는** 세션.
     *
     * Netty 가 하는 일을 앞당겨 확정적으로 만든 것이다. 실제로는 반납이 조금 뒤에 오지만,
     * "언제 오는가"는 플랫폼의 사정이고 "그때까지 읽었어야 한다"가 이 코드의 계약이다.
     */
    private inner class ReleasingSession(
        private val uri: URI,
        private val payload: String,
    ) : WebSocketSession {

        private val info = HandshakeInfo(
            uri,
            HttpHeaders(),
            Mono.just(authentication()),
            null,
        )

        override fun getId(): String = "test-viewer"

        override fun getHandshakeInfo(): HandshakeInfo = info

        override fun bufferFactory() = bufferFactory

        override fun getAttributes(): MutableMap<String, Any> = mutableMapOf()

        private val message = WebSocketMessage(
            WebSocketMessage.Type.TEXT,
            bufferFactory.wrap(payload.toByteArray()),
        )

        /**
         * 메시지 하나를 내보내고 **그다음 신호에서** 버퍼를 놓는다.
         *
         * `concatWith` 가 순서를 확정한다: 아래 연산자의 매퍼가 메시지를 받아 돌아온 뒤에야
         * 이 `fromRunnable` 이 구독된다. 그 지점이 Netty 가 프레임을 놓는 자리와 같다 —
         * 핸들러 체인이 한 바퀴 돈 직후다.
         *
         * 그래서 매퍼 안에서 바로 읽은 구현은 값을 얻고, 코루틴으로 미룬 구현은 놓친다.
         */
        override fun receive(): Flux<WebSocketMessage> =
            Flux.just(message)
                .concatWith(
                    Mono.fromRunnable<WebSocketMessage> {
                        NettyDataBufferFactory.toByteBuf(message.payload).release()
                    }
                )

        override fun send(messages: org.reactivestreams.Publisher<WebSocketMessage>): Mono<Void> =
            Flux.from(messages).then()

        override fun closeStatus(): Mono<CloseStatus> = Mono.empty()

        override fun close(status: CloseStatus): Mono<Void> = Mono.empty()

        override fun isOpen(): Boolean = true

        override fun textMessage(payload: String): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.TEXT, bufferFactory.wrap(payload.toByteArray()))

        override fun binaryMessage(
            payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>
        ): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.BINARY, payloadFactory.apply(bufferFactory))

        override fun pingMessage(
            payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>
        ): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PING, payloadFactory.apply(bufferFactory))

        override fun pongMessage(
            payloadFactory: java.util.function.Function<org.springframework.core.io.buffer.DataBufferFactory, org.springframework.core.io.buffer.DataBuffer>
        ): WebSocketMessage =
            WebSocketMessage(WebSocketMessage.Type.PONG, payloadFactory.apply(bufferFactory))
    }

    private fun authentication(): Authentication {
        val jwt = Jwt.withTokenValue("token")
            .header("alg", "HS256")
            .subject("42")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(3600))
            .build()
        val authentication = Mockito.mock(Authentication::class.java)
        Mockito.`when`(authentication.principal).thenReturn(jwt)
        return authentication
    }
}
