package kr.artel.orchestration.sdk.config

import kr.artel.orchestration.sdk.service.SdkWebSocketHandler
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.service.ViewerWebSocketHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.server.WebSocketService
import org.springframework.web.reactive.socket.server.support.HandshakeWebSocketService
import org.springframework.web.reactive.socket.server.upgrade.ReactorNettyRequestUpgradeStrategy
import reactor.netty.http.server.WebsocketServerSpec
import org.springframework.core.Ordered

/**
 * Spring WebFlux 상에서 웹소켓을 동작시키기 위한 스프링 설정 클래스
 *
 * 두 경로가 한 매핑에 들어 있다. 매핑 빈을 나눠 등록하면 같은 순위를 가진 HandlerMapping이
 * 둘이 되어 어느 쪽이 먼저 조회되는지가 등록 순서에 달리게 된다.
 */
@Configuration
@EnableConfigurationProperties(StreamProperties::class, SdkSocketProperties::class)
class WebSocketConfig(
    private val sdkWebSocketHandler: SdkWebSocketHandler,
    private val viewerWebSocketHandler: ViewerWebSocketHandler,
    private val streamProperties: StreamProperties
) {

    /**
     * 특정 URL 경로와 이를 처리할 웹소켓 핸들러를 바인딩하는 설정
     *
     * 스트리밍이 꺼져 있으면 `/ws/viewer`를 아예 매핑하지 않는다. 핸들러 안에서 거절하는
     * 방식은 꺼진 기능을 향해 브라우저가 계속 재연결을 시도하게 두는데, 매핑이 없으면 그
     * 경로는 그냥 존재하지 않는다.
     */
    @Bean
    fun webSocketHandlerMapping(): HandlerMapping {
        val map = buildMap<String, WebSocketHandler> {
            put("/ws/sdk", sdkWebSocketHandler)
            if (streamProperties.enabled) {
                put("/ws/viewer", viewerWebSocketHandler)
            }
        }

        val handlerMapping = SimpleUrlHandlerMapping()
        handlerMapping.urlMap = map
        handlerMapping.order = Ordered.HIGHEST_PRECEDENCE
        return handlerMapping
    }

    /**
     * 웹소켓 핸드셰이크를 맡는 서비스. 프레임 상한이 여기서 정해진다.
     *
     * **어댑터를 직접 만들지 않는다.** WebFlux 자동설정이 `WebSocketService` 빈이 있으면 그것을
     * 집어 어댑터를 만들어 준다(`WebFluxAutoConfiguration`). 어댑터까지 만들면 같은 이름의 빈이
     * 둘이 되어 기동이 거절되고, 다른 이름으로 두면 어느 쪽이 쓰이는지가 등록 순서에 달린다 —
     * 실제로 그렇게 만들어 봤고 우리 것이 이겼지만, 그것은 확인이지 보장이 아니다.
     *
     * 정하지 않으면 Reactor Netty 기본값 65536이 걸리고, 그 값에 **정상 동작이 부딪혔다.**
     * 무엇에 맞춰 이 값을 잡았는지는 [SdkSocketProperties]가 적어 두었다.
     *
     * 두 경로(`/ws/sdk`, `/ws/viewer`)가 이 서비스를 나눠 쓴다. 뷰어가 보내는 것은 갱신과
     * 시그널링뿐이라 이 상한이 필요하지 않지만, 갈라 두면 값이 둘이 되고 한쪽만 고쳐지는 날이
     * 온다. 뷰어에 더 낮은 상한이 필요해지면 그때 갈라야 할 이유가 생긴다.
     */
    @Bean
    fun webSocketService(properties: SdkSocketProperties): WebSocketService {
        val strategy = ReactorNettyRequestUpgradeStrategy {
            WebsocketServerSpec.builder().maxFramePayloadLength(properties.maxMessageBytes)
        }

        return HandshakeWebSocketService(strategy)
    }
}
