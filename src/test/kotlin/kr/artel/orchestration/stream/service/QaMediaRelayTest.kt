package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kr.artel.orchestration.qa.review.QaRecording
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.StreamStartMessage
import kr.artel.orchestration.stream.dto.StreamRenewMessage
import kr.artel.orchestration.stream.dto.StreamStopMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

class QaMediaRelayTest {
    @Test
    fun `최초 START 전송 실패 뒤 SDK 재접속하면 START를 재시도한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.failStart = true
        fixture.relay.prepare()
        fixture.failStart = false
        Mockito.clearInvocations(fixture.sessions)
        fixture.relay.prepare()
        fixture.relay.prepare()
        assertThat(fixture.messages().filterIsInstance<StreamStartMessage>()).hasSize(1)
        assertThat(fixture.messages().filterIsInstance<StreamRenewMessage>()).hasSize(1)
    }

    @Test
    fun `실패한 내부 peer를 새 ID로 교체하고 이전 시그널은 무시한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.relay.prepare()
        val previous = fixture.states.keys.single()
        fixture.states[previous] = "failed"
        fixture.relay.prepare()
        val replacement = fixture.states.keys.single()
        assertThat(replacement).isNotEqualTo(previous)
        Mockito.clearInvocations(fixture.engine)
        fixture.relay.sdkSignal("game", previous.toString(), """{"type":"STREAM_STATE","state":"STOPPED"}""")
        Mockito.verifyNoInteractions(fixture.engine)
        fixture.relay.prepare()
        assertThat(fixture.states.keys).containsExactly(replacement)
    }

    @Test
    fun `모든 viewer가 종료되어도 진행 중인 QA 녹화는 유지한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.relay.prepare()
        val first = UUID.randomUUID().toString()
        fixture.relay.openViewer("game", first)
        val id = fixture.states.keys.single()
        val viewer = UUID.randomUUID().toString()
        fixture.relay.openViewer("game", viewer)
        fixture.relay.closeViewer("game", first)
        fixture.relay.closeViewer("game", viewer)
        assertThat(fixture.states.keys).containsExactly(id)
        assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).isEmpty()
        fixture.active = emptyList()
        fixture.relay.prepare()
        assertThat(fixture.states).isEmpty()
        assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).hasSize(1)
    }

    @Test
    fun `녹화가 없으면 마지막 viewer가 닫힐 때만 SDK 송출을 중단한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.active = emptyList()
        try {
            fixture.relay.openViewer("game", "viewer-a")
            fixture.relay.openViewer("game", "viewer-b")
            val stream = fixture.states.keys.single()
            Mockito.clearInvocations(fixture.engine)
            fixture.relay.closeViewer("game", "viewer-a")
            fixture.relay.closeViewer("game", "viewer-a")
            assertThat(fixture.states.keys).containsExactly(stream)
            assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).isEmpty()
            Mockito.verify(fixture.engine, Mockito.times(1)).closeViewer(stream, "viewer-a")
            Mockito.verify(fixture.engine, Mockito.never()).closeViewer(stream, "viewer-b")
            fixture.relay.closeViewer("game", "viewer-b")
            assertThat(fixture.states).isEmpty()
            assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).hasSize(1)
        } finally { fixture.relay.shutdown() }
    }

    @Test
    fun `owner의 viewer 상한 초과가 기존 시청자를 제거하지 않는다`() = runBlocking<Unit> {
        val fixture = Fixture(StreamProperties(mediaMaxViewers = 2))
        fixture.active = emptyList()
        try {
            fixture.relay.openViewer("game", "viewer-a")
            fixture.relay.openViewer("game", "viewer-b")
            assertThrows<TooManyRequestsException> { fixture.relay.openViewer("game", "viewer-c") }
            val stream = fixture.states.keys.single()
            Mockito.verify(fixture.engine, Mockito.never()).closeViewer(stream, "viewer-a")
            Mockito.verify(fixture.engine, Mockito.never()).closeViewer(stream, "viewer-b")
            fixture.relay.closeViewer("game", "viewer-a")
            assertThat(fixture.states.keys).containsExactly(stream)
            fixture.relay.closeViewer("game", "viewer-b")
            assertThat(fixture.states).isEmpty()
        } finally { fixture.relay.shutdown() }
    }

    @Test
    fun `SDK 송출 재시작 후 등록된 모든 viewer를 새 연결에 다시 연결한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        val first = fixture.viewers.admit("game", "viewer-a", Mockito.mock(WebSocketSession::class.java)).viewer
        val second = fixture.viewers.admit("game", "viewer-b", Mockito.mock(WebSocketSession::class.java)).viewer
        try {
            fixture.relay.prepare()
            fixture.relay.openViewer("game", first.streamId)
            fixture.relay.openViewer("game", second.streamId)
            val previous = fixture.states.keys.single()
            fixture.states[previous] = "failed"
            fixture.relay.prepare()
            val replacement = fixture.states.keys.single()
            assertThat(replacement).isNotEqualTo(previous)
            fixture.states[replacement] = "connected"
            Mockito.clearInvocations(fixture.engine)
            fixture.relay.prepare()
            Mockito.verify(fixture.engine).openViewer(replacement, first.streamId)
            Mockito.verify(fixture.engine).openViewer(replacement, second.streamId)
            for (viewer in listOf(first, second)) {
                StepVerifier.create(viewer.outbound().take(1)).assertNext {
                    val value = ObjectMapper().readTree(it)
                    assertThat(value["type"].asText()).isEqualTo("STREAM_READY")
                    assertThat(value["streamId"].asText()).isEqualTo(viewer.streamId)
                }.verifyComplete()
            }
        } finally {
            fixture.viewers.release(first)
            fixture.viewers.release(second)
            fixture.relay.shutdown()
        }
    }

    @Test
    fun `native 초기화가 실패해도 만료 정리와 QA 오류 표시를 계속한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.nativeUnavailable = true
        fixture.relay.prepare()
        Mockito.verify(fixture.recordings).error(fixture.recording.id, "영상 연결 또는 저장 서버에 오류가 발생했습니다.")
        Mockito.verify(fixture.recordings).expire()
        assertThat(fixture.messages()).isEmpty()
    }

    @Test
    fun `종료 실패 owner를 재시도하면서 다른 녹화를 갱신한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        val other = fixture.recording.copy(id = UUID.randomUUID(), instanceId = "other")
        fixture.active = listOf(fixture.recording, other)
        Mockito.`when`(fixture.sessions.hasSession("other")).thenReturn(true)
        fixture.relay.prepare()
        fixture.active = listOf(other)
        fixture.failClose = true
        fixture.relay.prepare()
        fixture.relay.prepare()
        assertThat(fixture.closeCount).isEqualTo(2)
        Mockito.verify(fixture.recordings, Mockito.times(3)).expire()
        assertThat(fixture.messages().filterIsInstance<StreamRenewMessage>()).hasSize(2)
    }

    @Test
    fun `한 녹화의 종료가 지연되어도 다른 SDK의 lease를 계속 갱신한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        val other = fixture.recording.copy(id = UUID.randomUUID(), instanceId = "other")
        fixture.active = listOf(fixture.recording, other)
        Mockito.`when`(fixture.sessions.hasSession("other")).thenReturn(true)
        fixture.relay.prepare()
        fixture.active = listOf(other)
        fixture.closeGate = CountDownLatch(1)
        try {
            fixture.relay.prepare(awaitOwners = false)
            withTimeout(2000) {
                while (fixture.closeStarted.count > 0 || fixture.otherRenews.get() < 1) delay(10)
            }
            withTimeout(2000) {
                while (fixture.otherRenews.get() < 2) {
                    fixture.relay.prepare(awaitOwners = false)
                    delay(10)
                }
            }
            assertThat(fixture.closeGate!!.count).isEqualTo(1)
        } finally {
            fixture.closeGate!!.countDown()
            fixture.relay.shutdown()
        }
    }
    private class Fixture(properties: StreamProperties = StreamProperties()) {
        val recording = QaRecording(UUID.randomUUID(), "game", Instant.now(), null, null, null)
        @Volatile var active = listOf(recording)
        var failStart = false
        var nativeUnavailable = false
        var failClose = false
        var closeCount = 0
        @Volatile var closeGate: CountDownLatch? = null
        val closeStarted = CountDownLatch(1)
        val otherRenews = AtomicInteger()
        val states = java.util.concurrent.ConcurrentHashMap<UUID, String>()
        val sessions = Mockito.mock(SessionManager::class.java) { invocation ->
            if (failStart && invocation.method.name == "send" && invocation.arguments[1] is StreamStartMessage) error("SDK disconnected")
            if (invocation.method.name == "send" && invocation.arguments[0] == "other" && invocation.arguments[1] is StreamRenewMessage) otherRenews.incrementAndGet()
            Mockito.RETURNS_DEFAULTS.answer(invocation)
        }
        val recordings = Mockito.mock(QaRecordingStore::class.java)
        val engine = Mockito.mock(InProcessMediaService::class.java) { invocation ->
            when (invocation.method.name) {
                "configure" -> {
                    if (nativeUnavailable) error("Native initialization failed")
                    val id = invocation.arguments[0] as UUID
                    val created = !states.containsKey(id)
                    states.putIfAbsent(id, "new")
                    MediaLease(created, states.getValue(id))
                }
                "closeStream" -> {
                    closeCount++
                    closeStarted.countDown()
                    closeGate?.await(5, TimeUnit.SECONDS)
                    states.remove(invocation.arguments[0] as UUID)
                    if (failClose) error("Native close failed")
                    Unit
                }
                else -> Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
        }
        val viewers = ViewerSessionRegistry(ObjectMapper(), properties)
        val relay = QaMediaRelay(sessions, viewers, properties, recordings, engine)
        init {
            Mockito.`when`(sessions.hasSession("game")).thenReturn(true)
            runBlocking { Mockito.`when`(recordings.active()).thenAnswer { active } }
        }
        fun messages() = Mockito.mockingDetails(sessions).invocations.filter { it.method.name == "send" }.map { it.arguments[1] }
    }
}
