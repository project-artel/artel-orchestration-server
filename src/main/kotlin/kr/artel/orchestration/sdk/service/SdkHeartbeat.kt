package kr.artel.orchestration.sdk.service

import org.slf4j.LoggerFactory
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketMessage
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono
import reactor.core.scheduler.Scheduler
import reactor.core.scheduler.Schedulers
import java.time.Duration
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/** 4001~4003 은 handshake 거절에 쓴다. SDK 는 4001 이 아닌 닫힘에서는 다시 연결한다. */
internal val HEARTBEAT_TIMEOUT = CloseStatus(4008, "Heartbeat timeout")

/**
 * SDK 세션 하나의 생존 확인.
 *
 * close frame 없이 끊긴 소켓은 서버 쪽 receive 가 끝나지 않는다. 그러면 세션이 [SessionManager] 에
 * 서버가 재시작될 때까지 남고, 그 인스턴스는 계속 연결됨으로 보이며, SDK 의 재연결은
 * 4002 `Instance already connected` 로 매번 거절된다(ARTEL-1111).
 *
 * 그래서 서버가 [pingInterval] 마다 ping frame 을 보내고, [idleTimeout] 동안 아무 frame 도 받지
 * 못하면 세션을 닫는다. 닫힘은 receive 를 끝내므로 기존 정리 경로(`removeSession`,
 * `sdkDisconnected`)가 그대로 실행된다.
 *
 * - pong 만이 아니라 받은 frame 전부를 생존 신호로 본다. 큰 PULSE 를 받는 동안에도 세션은 살아 있다.
 * - SDK 가 30초마다 보내는 ping 은 신호가 되지 못한다. Reactor Netty 가 그 ping 에 직접 pong 을
 *   돌려주고 애플리케이션에 넘기지 않는다. 반대로 서버가 보낸 ping 의 pong 은 receive 로 들어온다.
 * - websocket-sharp 는 ping 에 pong 을 자동으로 돌려주므로 SDK 쪽 변경은 없다.
 *
 * 시각은 [scheduler] 에서 읽는다. 테스트가 virtual time 으로 시간을 넘길 수 있게 하기 위해서다.
 */
class SdkHeartbeat(
    private val instanceId: String,
    private val session: WebSocketSession,
    private val pingInterval: Duration,
    private val idleTimeout: Duration,
    private val scheduler: Scheduler = Schedulers.parallel(),
) {
    private val logger = LoggerFactory.getLogger(SdkHeartbeat::class.java)
    private val lastSeenMillis = AtomicLong(now())

    /** frame 을 하나 받을 때마다 부른다. */
    fun seen() {
        lastSeenMillis.set(now())
    }

    /**
     * 세션으로 보낼 ping frame 들.
     *
     * [idleTimeout] 을 넘기면 세션을 닫고 끝난다. [until] 이 끝나도 함께 끝난다. 이 Flux 가 끝나지
     * 않으면 이것을 물고 있는 `session.send` 도 끝나지 않아 핸들러가 영영 반환되지 않는다.
     */
    fun pings(until: Mono<Void>): Flux<WebSocketMessage> =
        Flux.interval(pingInterval, scheduler)
            .takeUntilOther(until)
            .handle { _, sink ->
                val silentMillis = now() - lastSeenMillis.get()
                if (silentMillis >= idleTimeout.toMillis()) {
                    logger.warn("SDK 세션 heartbeat 시간 초과로 닫음 [instanceId: $instanceId]: ${silentMillis}ms 동안 받은 frame 없음")
                    session.close(HEARTBEAT_TIMEOUT).subscribe(
                        {},
                        { error -> logger.error("heartbeat 시간 초과 세션 닫기 실패, 세션이 남을 수 있음 [instanceId: $instanceId]", error) }
                    )
                    sink.complete()
                } else {
                    sink.next(session.pingMessage { factory -> factory.wrap(ByteArray(0)) })
                }
            }

    private fun now(): Long = scheduler.now(TimeUnit.MILLISECONDS)
}
