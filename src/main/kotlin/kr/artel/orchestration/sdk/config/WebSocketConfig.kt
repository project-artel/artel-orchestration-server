package kr.artel.orchestration.sdk.config

import kr.artel.orchestration.sdk.service.SdkWebSocketHandler
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.service.ViewerWebSocketHandler
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.config.WebFluxConfigurer
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
    private val streamProperties: StreamProperties,
    private val socketProperties: SdkSocketProperties
) : WebFluxConfigurer {

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
     * **`@Bean` 이 아니라 이 오버라이드다.** 이것이 [WebFluxConfigurer] 의 메서드인 것이 요점이고,
     * 전에는 같은 값을 `@Bean WebSocketService` 로 냈다가 **그 빈이 한 번도 쓰이지 않았다**
     * (ARTEL-682). 상한은 처음부터 Reactor Netty 기본값 65536 이었고, 전투 씬 판독이 그것을
     * 넘긴 날에야 드러났다.
     *
     * ```
     * WebFluxConfigurationSupport
     *   public  WebSocketHandlerAdapter webFluxWebSocketHandlerAdapter()
     *   protected WebSocketService      getWebSocketService()
     * ```
     *
     * 어댑터는 컨텍스트에서 `WebSocketService` 빈을 조회하지 않는다. `getWebSocketService()` 로만
     * 받고, 아무도 그것을 구현하지 않으면 기본 `HandshakeWebSocketService` 를 스스로 만든다 —
     * 우리 [WebsocketServerSpec] 이 없는 것으로. 빈은 만들어지고, 주입되지 않고, 조용하다.
     *
     * **어댑터를 직접 만드는 것은 여전히 하지 않는다.** 같은 이름의 빈이 둘이 되어 기동이
     * 거절되거나, 다른 이름으로 두면 어느 쪽이 쓰이는지가 등록 순서에 달린다. 이 경로는 Spring 이
     * 정해 둔 확장점이라 어댑터는 그대로 Spring 이 하나만 만든다.
     *
     * `WebFluxConfigurer` 를 구현한 빈이 이 앱에 둘이다(`WebFluxArgumentResolverConfig`). 합성은
     * `WebFluxConfigurerComposite` 가 하고, 이 메서드는 **하나만** 값을 내야 한다 — 둘이 내면
     * 기동이 거절된다. 웹소켓 설정을 여기 두는 이유가 그것이다.
     *
     * 무엇에 맞춰 값을 잡았는지는 [SdkSocketProperties] 가 적어 두었다.
     *
     * 두 경로(`/ws/sdk`, `/ws/viewer`)가 이 서비스를 나눠 쓴다. 뷰어가 보내는 것은 갱신과
     * 시그널링뿐이라 이 상한이 필요하지 않지만, 갈라 두면 값이 둘이 되고 한쪽만 고쳐지는 날이
     * 온다. 뷰어에 더 낮은 상한이 필요해지면 그때 갈라야 할 이유가 생긴다.
     */
    override fun getWebSocketService(): WebSocketService {
        val strategy = ReactorNettyRequestUpgradeStrategy {
            WebsocketServerSpec.builder().maxFramePayloadLength(socketProperties.maxMessageBytes)
        }

        return HandshakeWebSocketService(strategy)
    }
}
