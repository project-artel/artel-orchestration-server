package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.AccountStateService
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.contentmap.scan.ScanResultRouter
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.qa.service.QaRunStatusNotifier
import kr.artel.orchestration.qa.service.QaSdkReconnectService
import kr.artel.orchestration.sdk.config.SdkSocketProperties
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.junit.jupiter.api.Test
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Duration

class SdkActionReconnectTest {
    private val mapper = jacksonObjectMapper()
    private val sessions = SessionManager(mapper)
    private val failures = mock(QaExecutionFailureService::class.java)
    private val delivery = SdkActionDelivery(sessions, mock(QaTryRepository::class.java), mapper)
    private val processId = "11111111-1111-1111-1111-111111111111"
    private val handler = SdkWebSocketHandler(
        mapper, mock(GameInstanceRepository::class.java), sessions, failures,
        mock(QaRunStatusNotifier::class.java), mock(ReactiveJwtDecoder::class.java),
        mock(SessionUserResolver::class.java), mock(AccountStateService::class.java),
        SdkSocketProperties(), ProjectChangeStreamManager(), mock(ScanResultRouter::class.java),
        mock(QaSdkReconnectService::class.java), emptyList(), delivery,
    )

    private suspend fun disconnectedPending() {
        val socket = mock(WebSocketSession::class.java)
        sessions.registerReplacingSilent("4", socket, { false }, Duration.ZERO, processId)
        delivery.send("4", 11, ActionResponseDto(id = 45228, actions = listOf(
            ActionItemDto(id = 1, method = "reset_game", params = emptyList())
        )))
        sessions.removeSession("4", socket)
    }

    @Test
    fun `handler는 같은 프로세스가 grace 동안 돌아오면 QA를 실패시키지 않는다`(): Unit = runBlocking {
        disconnectedPending()
        handler.handleSdkDisconnect("4", processId) {
            sessions.registerReplacingSilent("4", mock(WebSocketSession::class.java), { false }, Duration.ZERO, processId)
        }
        verifyNoInteractions(failures)
    }

    @Test
    fun `handler는 재접속이 없으면 원래 try만 종료한다`(): Unit = runBlocking {
        disconnectedPending()
        handler.handleSdkDisconnect("4", processId) { }
        verify(failures).sdkReconnectExpired(11)
    }

    @Test
    fun `다른 프로세스로 돌아오면 원래 try의 복구를 중단한다`(): Unit = runBlocking {
        disconnectedPending()
        handler.handleSdkDisconnect("4", processId) {
            sessions.registerReplacingSilent("4", mock(WebSocketSession::class.java), { false }, Duration.ZERO, "other")
        }
        verify(failures).sdkReconnectExpired(11)
    }

    @Test
    fun `지원하지 않는 SDK는 기존 연결 종료 처리를 유지한다`(): Unit = runBlocking {
        handler.handleSdkDisconnect("4", null) { error("구버전은 grace를 기다리지 않는다") }
        verify(failures).sdkDisconnected(4)
    }
}
