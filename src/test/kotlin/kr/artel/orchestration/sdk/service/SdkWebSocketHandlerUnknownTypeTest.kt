package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.reactive.socket.WebSocketSession

class SdkWebSocketHandlerUnknownTypeTest {
    @Test
    fun `unknown type completes normally and next frame is processed`() {
        var handled=0
        val known=object:SdkMessageHandler {
            override val messageType="KNOWN"
            override suspend fun handle(instanceId:String,payloadText:String,session:WebSocketSession){ handled++ }
        }
        val handler=SdkWebSocketHandler(
            ObjectMapper().registerKotlinModule(),mock(GameInstanceRepository::class.java),
            mock(SessionManager::class.java),mock(QaExecutionFailureService::class.java),
            mock(ReactiveJwtDecoder::class.java),mock(SessionUserResolver::class.java),listOf(known)
        )
        val session=mock(WebSocketSession::class.java)

        handler.dispatch("7","""{"type":"UNKNOWN"}""",session).block()
        handler.dispatch("7","""{"type":"KNOWN"}""",session).block()

        assertThat(handled).isEqualTo(1)
    }
}
