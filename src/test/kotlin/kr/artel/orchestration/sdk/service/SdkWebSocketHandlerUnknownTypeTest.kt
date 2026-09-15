package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.DeserializationFeature
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.registerKotlinModule
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.qa.service.QaRunStatusNotifier
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * ARTEL-372 Scope 1 — 모르는 `type`이 와도 연결이 끊기거나 오류가 나지 않아야 한다.
 *
 * SDK가 서버보다 먼저 배포되면 서버가 모르는 타입이 **초당 한 번** 들어온다. 그 경로가
 * 프레임 하나를 버리는 데서 끝나지 않으면 QA 실행이 통째로 죽는다.
 */
class SdkWebSocketHandlerUnknownTypeTest {

    /**
     * 미지 필드에 엄격한 매퍼를 일부러 쓴다.
     *
     * 운영에서는 Spring Boot가 `FAIL_ON_UNKNOWN_PROPERTIES`를 꺼 주지만, 그 기본값에 기대면
     * 설정 한 줄로 이 경로가 뒤집힌다. 엄격한 매퍼로도 통과해야 [BaseMessage]의
     * `@JsonIgnoreProperties`가 실제로 값을 하고 있다는 뜻이 된다.
     */
    private val strictMapper = ObjectMapper()
        .registerKotlinModule()
        .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)

    private var handled = 0

    private val knownHandler = object : SdkMessageHandler {
        override val messageType = "KNOWN"
        override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
            handled++
        }
    }

    private fun handler(mapper: ObjectMapper) = SdkWebSocketHandler(
        mapper,
        mock(GameInstanceRepository::class.java),
        mock(SessionManager::class.java),
        mock(QaExecutionFailureService::class.java),
        mock(QaRunStatusNotifier::class.java),
        mock(ReactiveJwtDecoder::class.java),
        mock(SessionUserResolver::class.java),
        listOf(knownHandler)
    )

    @Test
    fun `unknown type is dropped and the next frame is still processed`() {
        val handler = handler(strictMapper)
        val session = mock(WebSocketSession::class.java)

        handler.dispatch("7", """{"type":"UNKNOWN"}""", session).block()
        handler.dispatch("7", """{"type":"KNOWN"}""", session).block()

        assertThat(handled).isEqualTo(1)
    }

    /**
     * 실제로 오는 미지 프레임은 `type`만 있는 것이 아니라 본문이 통째로 딸려 온다.
     * 본문 필드 때문에 파싱이 깨지면 미지 타입 경로가 아니라 손상 프레임 경로로 떨어진다.
     */
    @Test
    fun `unknown type carrying a full body is dropped without breaking the connection`() {
        val handler = handler(strictMapper)
        val session = mock(WebSocketSession::class.java)
        val futureSdkFrame = """
            {"type":"NOT_YET_SUPPORTED","id":12,
             "frameTimes":{"frameCount":59,"sampledMs":998.4,"meanMs":16.92},
             "status":{"isFocused":true,"batteryStatus":"Charging"},
             "somethingInventedLater":{"nested":[1,2,3]}}
        """.trimIndent()

        handler.dispatch("7", futureSdkFrame, session).block()
        handler.dispatch("7", """{"type":"KNOWN"}""", session).block()

        assertThat(handled).isEqualTo(1)
    }

    /** 손상된 JSON도 그 프레임만 버린다. */
    @Test
    fun `malformed frame is dropped and the next frame is still processed`() {
        val handler = handler(strictMapper)
        val session = mock(WebSocketSession::class.java)

        handler.dispatch("7", """{"type":""", session).block()
        handler.dispatch("7", """{"type":"KNOWN"}""", session).block()

        assertThat(handled).isEqualTo(1)
    }

    /** 핸들러가 던져도 소켓은 살아 있어야 한다. 성능 저장 실패가 QA 실행을 멈추면 안 된다. */
    @Test
    fun `handler failure does not stop the next frame`() {
        val exploding = object : SdkMessageHandler {
            override val messageType = "BOOM"
            override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) =
                throw IllegalStateException("저장 실패")
        }
        val handler = SdkWebSocketHandler(
            strictMapper,
            mock(GameInstanceRepository::class.java),
            mock(SessionManager::class.java),
            mock(QaExecutionFailureService::class.java),
            mock(QaRunStatusNotifier::class.java),
            mock(ReactiveJwtDecoder::class.java),
            mock(SessionUserResolver::class.java),
            listOf(knownHandler, exploding)
        )
        val session = mock(WebSocketSession::class.java)

        handler.dispatch("7", """{"type":"BOOM"}""", session).block()
        handler.dispatch("7", """{"type":"KNOWN"}""", session).block()

        assertThat(handled).isEqualTo(1)
    }
}
