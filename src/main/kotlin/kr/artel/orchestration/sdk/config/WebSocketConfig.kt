package kr.artel.orchestration.sdk.config

import kr.artel.orchestration.sdk.controller.SdkWebSocketHandler
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import org.springframework.web.reactive.HandlerMapping
import org.springframework.web.reactive.handler.SimpleUrlHandlerMapping
import org.springframework.web.reactive.socket.server.support.WebSocketHandlerAdapter as SpringWebSocketHandlerAdapter
import org.springframework.core.Ordered

/**
 * Spring WebFlux 상에서 웹소켓을 동작시키기 위한 스프링 설정 클래스
 */
@Configuration
class WebSocketConfig(private val sdkWebSocketHandler: SdkWebSocketHandler) {

    /**
     * 특정 URL 경로와 이를 처리할 웹소켓 핸들러를 바인딩하는 설정
     */
    @Bean
    fun webSocketHandlerMapping(): HandlerMapping {
        val map = mapOf("/ws/sdk" to sdkWebSocketHandler)
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
