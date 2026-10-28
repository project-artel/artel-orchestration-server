package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.delay
import kr.artel.orchestration.qa.review.QaRecording
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.StreamStartMessage
import kr.artel.orchestration.stream.dto.StreamRenewMessage
import kr.artel.orchestration.stream.dto.StreamStopMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
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
    fun `viewer가 종료되어도 진행 중인 QA 녹화의 peer는 유지한다`() = runBlocking<Unit> {
        val fixture = Fixture()
        fixture.relay.prepare()
        fixture.relay.openViewer("game", UUID.randomUUID().toString())
        val id = fixture.states.keys.single()
        val viewer = UUID.randomUUID().toString()
        fixture.relay.openViewer("game", viewer)
        fixture.relay.closeViewer("game", viewer)
        assertThat(fixture.states.keys).containsExactly(id)
        assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).isEmpty()
        fixture.active = emptyList()
        fixture.relay.prepare()
        assertThat(fixture.states).isEmpty()
        assertThat(fixture.messages().filterIsInstance<StreamStopMessage>()).hasSize(1)
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
    private class Fixture {
        val recording = QaRecording(UUID.randomUUID(), "game", Instant.now(), null, null, null)
        var active = listOf(recording)
        var failStart = false
        var nativeUnavailable = false
        var failClose = false
        var closeCount = 0
        var closeGate: CountDownLatch? = null
        val closeStarted = CountDownLatch(1)
        val otherRenews = AtomicInteger()
        val states = linkedMapOf<UUID, String>()
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
        val relay = QaMediaRelay(sessions, ViewerSessionRegistry(ObjectMapper()), StreamProperties(), recordings, engine)
        init {
            Mockito.`when`(sessions.hasSession("game")).thenReturn(true)
            runBlocking { Mockito.`when`(recordings.active()).thenAnswer { active } }
        }
        fun messages() = Mockito.mockingDetails(sessions).invocations.filter { it.method.name == "send" }.map { it.arguments[1] }
    }
}
