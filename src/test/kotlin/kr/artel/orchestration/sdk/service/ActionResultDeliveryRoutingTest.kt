package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.capture.ScreenCaptureResultRouter
import kr.artel.orchestration.contentmap.scan.ScanResultRouter
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import kr.artel.orchestration.sdk.service.handler.ActionAcceptedMessageHandler
import kr.artel.orchestration.sdk.service.handler.ActionResultMessageHandler
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.anyLong
import org.mockito.ArgumentMatchers.anyString
import org.mockito.Mockito.mock
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoInteractions
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.socket.WebSocketSession
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class ActionResultDeliveryRoutingTest {
    private val mapper = jacksonObjectMapper()
    private val sessions = SessionManager(mapper)
    private val tries = mock(QaTryRepository::class.java)
    private val delivery = SdkActionDelivery(sessions, tries, mapper)
    private val scans = mock(ScanResultRouter::class.java)
    private val captures = mock(ScreenCaptureResultRouter::class.java)
    private val bridge = mock(QaSdkBridgeService::class.java)
    private val handler = ActionResultMessageHandler(scans, captures, bridge, mapper, delivery)
    private val acknowledgements = ActionAcceptedMessageHandler(mapper, delivery)
    private val socket = mock(WebSocketSession::class.java)
    private val processId = "11111111-1111-1111-1111-111111111111"
    private val sent = mutableListOf<String>()
    private val startedAt = Instant.ofEpochMilli(1_800_000_000_000L)

    @BeforeEach
    fun setup(): Unit = runBlocking {
        delivery.clock = Clock.fixed(startedAt, ZoneOffset.UTC)
        `when`(scans.handle(anyLong(), anyString())).thenReturn(false)
        `when`(captures.handle(anyLong(), anyString())).thenReturn(false)
        `when`(tries.findById(11)).thenReturn(QaTryEntity(
            id = 11, testScenarioId = 1, gameInstanceId = 4, startedBy = 1, status = "RUNNING", startedAt = startedAt,
        ))
        sessions.registerReplacingSilent("4", socket, { false }, Duration.ZERO, processId)!!.outbound
            .subscribe { sent += it }
        delivery.send("4", 11, ActionResponseDto(id = 45228, actions = listOf(
            ActionItemDto(id = 1, method = "reset_game", params = emptyList()),
        )))
    }

    @Test
    fun `QA와 ID가 같은 구버전 scan 결과는 원래 router로 보내고 QA pending을 유지한다`(): Unit = runBlocking {
        val result = result(process = null, action = "scan_evidence")
        `when`(scans.handle(4, result)).thenReturn(true)

        handler.handle("4", result, socket)

        verify(scans).handle(4, result)
        verifyNoInteractions(captures, bridge)
        assertThat(delivery.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `QA와 ID가 같은 구버전 screen capture 결과도 QA pending을 완료하지 않는다`(): Unit = runBlocking {
        val result = result(process = null, action = "capture_screen")
        `when`(captures.handle(4, result)).thenReturn(true)

        handler.handle("4", result, socket)

        verify(scans).handle(4, result)
        verify(captures).handle(4, result)
        verifyNoInteractions(bridge)
        assertThat(delivery.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `같은 프로세스의 QA 결과를 중계한 뒤 pending을 완료한다`(): Unit = runBlocking {
        val result = result(processId)
        `when`(bridge.routeActionResult(4, result)).thenReturn(true)

        handler.handle("4", result, socket)

        verify(bridge).routeActionResult(4, result)
        assertThat(delivery.pendingCount()).isZero()
        delivery.clock = Clock.fixed(startedAt.plusSeconds(5), ZoneOffset.UTC)
        delivery.retryDue()
        assertThat(sent).hasSize(1)
    }

    @Test
    fun `이전 세션의 결과는 모든 router에서 제외한다`(): Unit = runBlocking {
        handler.handle("4", result(processId), mock(WebSocketSession::class.java))

        verifyNoInteractions(scans, captures, bridge)
        assertThat(delivery.pendingCount()).isEqualTo(1)
    }

    @Test
    fun `다른 프로세스의 ACK를 무시하고 수신 미확인 요청을 재전송한다`(): Unit = runBlocking {
        acknowledgements.handle("4", """{"type":"ACTION_ACCEPTED","requestId":45228,"processId":"other"}""", socket)
        delivery.clock = Clock.fixed(startedAt.plusSeconds(2), ZoneOffset.UTC)

        delivery.retryDue()

        assertThat(sent).hasSize(2)
        assertThat(sent[1]).isEqualTo(sent[0])
        assertThat(delivery.pendingCount()).isEqualTo(1)
        verifyNoInteractions(scans, captures, bridge)
    }

    @Test
    fun `ACK 뒤 agent 중계가 실패해도 pending을 유지하고 동일 결과 중계를 다시 시도한다`(): Unit = runBlocking {
        val result = result(processId)
        acknowledgements.handle("4", """{"type":"ACTION_ACCEPTED","requestId":45228,"processId":"$processId"}""", socket)
        `when`(bridge.routeActionResult(4, result))
            .thenThrow(IllegalStateException("agent relay unavailable"))
            .thenReturn(true)

        assertThatThrownBy { runBlocking { handler.handle("4", result, socket) } }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessage("agent relay unavailable")
        assertThat(delivery.pendingCount()).isEqualTo(1)
        delivery.clock = Clock.fixed(startedAt.plusSeconds(5), ZoneOffset.UTC)
        delivery.retryDue()
        assertThat(sent).hasSize(2)
        assertThat(sent[1]).isEqualTo(sent[0])

        handler.handle("4", result, socket)

        verify(bridge, times(2)).routeActionResult(4, result)
        assertThat(delivery.pendingCount()).isZero()
    }

    private fun result(process: String?, action: String = "reset_game"): String {
        val frame = mapper.createObjectNode()
            .put("type", "ACTION_RESULT")
            .put("id", 9)
            .put("requestId", 45228)
            .put("frame", 100)
        if (process != null) frame.put("processId", process)
        frame.putArray("results").addObject()
            .put("id", 1).put("success", true).put("action", action)
        return mapper.writeValueAsString(frame)
    }
}
