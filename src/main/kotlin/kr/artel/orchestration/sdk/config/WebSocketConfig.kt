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
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter as SpringWebSocketHandlerAdapter
import org.springframework.core.Ordered

/**
 * Spring WebFlux 상에서 웹소켓을 동작시키기 위한 스프링 설정 클래스
 *
 * 두 경로가 한 매핑에 들어 있다. 매핑 빈을 나눠 등록하면 같은 순위를 가진 HandlerMapping이
 * 둘이 되어 어느 쪽이 먼저 조회되는지가 등록 순서에 달리게 된다.
 */
@Configuration
@EnableConfigurationProperties(StreamProperties::class)
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
     * WebFlux의 반응형 아키텍처 상에서 웹소켓 핸들러가 원활히 동작하도록 돕는 어댑터 빈 등록
     */
    @Bean
    fun handlerAdapter(): SpringWebSocketHandlerAdapter {
        return SpringWebSocketHandlerAdapter()
    }
}
