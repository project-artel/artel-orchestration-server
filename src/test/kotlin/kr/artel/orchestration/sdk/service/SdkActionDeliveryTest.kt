package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.sdk.dto.ActionAcceptedDto
import kr.artel.orchestration.sdk.dto.ActionDeliveryResultDto
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.ArgumentMatchers.any
import org.mockito.Mockito.mock
import org.mockito.Mockito.verify
import org.mockito.Mockito.`when`
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset

class SdkActionDeliveryTest {
    private val mapper = jacksonObjectMapper()
    private val sessions = SessionManager(mapper)
    private val tries = mock(QaTryRepository::class.java)
    private val failures = mock(QaExecutionFailureService::class.java)
    private val delivery = SdkActionDelivery(sessions, tries, mapper, failures)
    private val processId = "11111111-1111-1111-1111-111111111111"
    private val messages = mutableListOf<String>()
    private var now = 1_800_000_000_000L
    private lateinit var socket: WebSocketSession
    private val action = ActionResponseDto(id = 45228, actions = listOf(
        ActionItemDto(id = 1, method = "reset_game", params = listOf(mapOf("clearPlayerPrefs" to true)))
    ))
    private val running = QaTryEntity(id = 11, testScenarioId = 1, gameInstanceId = 4, startedBy = 1,
        status = "RUNNING", startedAt = Instant.ofEpochMilli(now))

    @BeforeEach
    fun setup(): Unit = runBlocking {
        `when`(tries.findById(11)).thenReturn(running)
        setTime(0)
        socket = connect(processId)
    }

    private fun setTime(advance: Long) {
        now += advance
        delivery.clock = Clock.fixed(Instant.ofEpochMilli(now), ZoneOffset.UTC)
    }

    private suspend fun connect(process: String?): WebSocketSession {
        val next = mock(WebSocketSession::class.java)
        `when`(next.close(any())).thenReturn(Mono.empty())
        sessions.registerReplacingSilent("4", next, { false }, Duration.ZERO, process)!!.outbound
            .subscribe { messages += it }
        return next
    }

    @Test
    fun `ACK 유실 시 같은 requestId와 본문으로 재전송한다`(): Unit = runBlocking {
        assertThat(delivery.send("4", 11, action)).isTrue()
        setTime(1999)
        delivery.retryDue()
        assertThat(messages).hasSize(1)
        setTime(1)
        delivery.retryDue()
        assertThat(messages).hasSize(2)
        assertThat(messages[1]).isEqualTo(messages[0])
        val wire = mapper.readTree(messages[0])
        assertThat(wire["id"].asLong()).isEqualTo(45228)
        assertThat(wire["delivery"]["processId"].asText()).isEqualTo(processId)
    }

    @Test
    fun `ACK 뒤 결과 유실은 5초 후 같은 요청으로 결과를 다시 조회한다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        delivery.accepted("4", ActionAcceptedDto("ACTION_ACCEPTED", action.id, processId), socket)
        setTime(2000)
        delivery.retryDue()
        assertThat(messages).hasSize(1)
        setTime(3000)
        delivery.retryDue()
        assertThat(messages).hasSize(2)
        assertThat(messages[1]).isEqualTo(messages[0])
        val result = ActionDeliveryResultDto(action.id, processId)
        assertThat(delivery.acceptsResult("4", result, socket)).isTrue()
        delivery.completed("4", result)
        setTime(5000)
        delivery.retryDue()
        assertThat(delivery.pendingCount()).isZero()
        assertThat(messages).hasSize(2)
    }

    @Test
    fun `세션 교체 직전 등록한 요청은 새 세션에서 즉시 같은 ID로 복구한다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        val previous = socket
        socket = connect(processId)
        sessions.removeSession("4", previous)
        delivery.onReconnect("4")
        assertThat(messages).hasSize(2)
        assertThat(messages[1]).isEqualTo(messages[0])
        assertThat(delivery.acceptsResult("4", ActionDeliveryResultDto(action.id, processId), previous)).isFalse()
    }

    @Test
    fun `OS 요청은 같은 프로세스가 새 session으로 연결해도 재전송하지 않는다`(): Unit = runBlocking {
        val osAction = action.copy(inputMode = "os")
        val previous = socket
        assertThat(delivery.send("4", 11, osAction, expectedSession = previous)).isTrue()
        socket = connect(processId)

        assertThat(delivery.acceptsResult("4", ActionDeliveryResultDto(action.id, processId), socket)).isFalse()
        delivery.onReconnect("4")

        assertThat(messages).hasSize(1)
        assertThat(delivery.pendingCount()).isZero()
        verify(failures).sdkInputSessionChanged(11)
    }

    @Test
    fun `다른 프로세스의 재접속에는 초기화를 재실행하지 않는다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        socket = connect("22222222-2222-2222-2222-222222222222")
        delivery.onReconnect("4")
        assertThat(messages).hasSize(1)
        assertThat(delivery.pendingCount()).isZero()
    }

    @Test
    fun `구버전 SDK는 delivery 필드와 재전송 없이 기존 요청만 받는다`(): Unit = runBlocking {
        socket = connect(null)
        delivery.send("4", 11, action)
        setTime(3000)
        delivery.retryDue()
        assertThat(messages).hasSize(1)
        assertThat(mapper.readTree(messages[0]).has("delivery")).isFalse()
        assertThat(delivery.pendingCount()).isZero()
    }

    @Test
    fun `끊긴 동안 보관한 요청은 같은 프로세스가 돌아올 때 복구한다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        sessions.removeSession("4", socket)
        setTime(2000)
        delivery.retryDue()
        assertThat(messages).hasSize(1)
        socket = connect(processId)
        delivery.onReconnect("4")
        assertThat(messages).hasSize(2)
    }

    @Test
    fun `만료된 요청은 재접속해도 전송하지 않는다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        setTime(25_000)
        socket = connect(processId)
        delivery.onReconnect("4")
        assertThat(messages).hasSize(1)
        assertThat(delivery.pendingCount()).isZero()
    }

    @Test
    fun `DB commit 대기 중 만료된 요청은 최초 전송도 하지 않는다`(): Unit = runBlocking {
        assertThat(delivery.send("4", 11, action, now)).isFalse()
        assertThat(messages).isEmpty()
        assertThat(delivery.pendingCount()).isZero()
    }

    @Test
    fun `QA가 끝나면 요청을 재전송하지 않는다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        `when`(tries.findById(11)).thenReturn(running.copy(status = "COMPLETED"))
        setTime(2000)
        delivery.retryDue()
        assertThat(messages).hasSize(1)
        assertThat(delivery.pendingCount()).isZero()
    }

    @Test
    fun `재접속을 반복해도 최대 6회까지만 전송한다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        repeat(10) {
            socket = connect(processId)
            delivery.onReconnect("4")
        }
        assertThat(messages).hasSize(6)
        assertThat(messages.distinct()).hasSize(1)
    }

    @Test
    fun `이전 세션과 다른 프로세스의 ACK는 인정하지 않는다`(): Unit = runBlocking {
        delivery.send("4", 11, action)
        delivery.accepted("4", ActionAcceptedDto("ACTION_ACCEPTED", action.id, "other"), socket)
        delivery.accepted("4", ActionAcceptedDto("ACTION_ACCEPTED", action.id, processId), mock(WebSocketSession::class.java))
        setTime(2000)
        delivery.retryDue()
        assertThat(messages).hasSize(2)
        assertThat(delivery.acceptsResult("4", ActionDeliveryResultDto(action.id, null), socket)).isFalse()
        assertThat(delivery.acceptsResult("4", ActionDeliveryResultDto(action.id, "other"), socket)).isFalse()
    }

    @Test
    fun `세션 검사 뒤 구버전으로 교체되어도 이전 요청을 새 큐에 넣지 않는다`(): Unit = runBlocking {
        val previous = socket
        socket = connect(null)
        assertThat(sessions.sendRawToSession("4", previous, processId, mapper.writeValueAsString(action))).isFalse()
        assertThat(messages).isEmpty()
    }
}
