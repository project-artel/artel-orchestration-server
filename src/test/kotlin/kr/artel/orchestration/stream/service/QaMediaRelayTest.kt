package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import com.sun.net.httpserver.HttpServer
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.qa.review.QaRecording
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.StreamStartMessage
import kr.artel.orchestration.stream.dto.StreamStopMessage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import java.net.InetSocketAddress
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList

class QaMediaRelayTest {
    @Test
    fun `최초 START 전송에 실패하면 SDK 재접속 후 START를 다시 보내고 정상 연결은 갱신한다`() = runBlocking<Unit> {
        WorkerServer().use { worker ->
            worker.newState = "new"
            var failStart = true
            val sessions = Mockito.mock(SessionManager::class.java) { invocation ->
                if (failStart && invocation.method.name == "send" && invocation.arguments[1] is StreamStartMessage)
                    throw IllegalArgumentException("SDK disconnected before START")
                Mockito.RETURNS_DEFAULTS.answer(invocation)
            }
            val recordings = Mockito.mock(QaRecordingStore::class.java)
            val relay = QaMediaRelay(sessions, ViewerSessionRegistry(ObjectMapper()), StreamProperties(mediaWorkerUrl = worker.url), recordings)
            val active = recording("reconnected-instance")
            Mockito.`when`(sessions.hasSession(active.instanceId)).thenReturn(true)
            Mockito.`when`(recordings.active()).thenReturn(listOf(active))
            relay.prepare()

            failStart = false
            Mockito.clearInvocations(sessions)
            relay.prepare()
            relay.prepare()
            val messages = Mockito.mockingDetails(sessions).invocations.filter { it.method.name == "send" }.map { it.arguments[1] }
            assertThat(messages.filterIsInstance<StreamStartMessage>()).hasSize(1)
            assertThat(messages.filterIsInstance<kr.artel.orchestration.stream.dto.StreamRenewMessage>()).hasSize(1)
        }
    }

    @Test
    fun `실패한 ingress는 새 stream ID로 교체하고 이전 SDK 시그널을 무시한다`() = runBlocking<Unit> {
        WorkerServer().use { worker ->
            val sessions = Mockito.mock(SessionManager::class.java)
            val recordings = Mockito.mock(QaRecordingStore::class.java)
            val relay = QaMediaRelay(sessions, ViewerSessionRegistry(ObjectMapper()), StreamProperties(mediaWorkerUrl = worker.url), recordings)
            val active = recording("failed-instance")
            Mockito.`when`(sessions.hasSession(active.instanceId)).thenReturn(true)
            Mockito.`when`(recordings.active()).thenReturn(listOf(active))
            relay.prepare()
            val previous = worker.recordingStreams.getValue(active.id.toString())
            worker.states[previous] = "failed"
            relay.prepare()
            val replacement = worker.recordingStreams.getValue(active.id.toString())
            assertThat(replacement).isNotEqualTo(previous)
            worker.requests.clear()
            relay.sdkSignal(active.instanceId, previous, """{"type":"STREAM_STATE","state":"STOPPED"}""")
            assertThat(worker.requests).isEmpty()
            relay.prepare()
            assertThat(worker.requests).containsExactly("PUT /internal/streams/$replacement")
        }
    }

    @Test
    fun `SDK가 먼저 끊긴 owner를 정리해도 다른 녹화의 갱신과 만료 처리를 계속한다`() = runBlocking {
        assertOwnerCleanup(false)
    }

    @Test
    fun `worker 종료 요청이 실패해도 다른 녹화를 갱신하고 종료된 owner를 다시 처리하지 않는다`() = runBlocking {
        assertOwnerCleanup(true)
    }

    private suspend fun assertOwnerCleanup(deleteFails: Boolean) {
        WorkerServer().use { worker ->
            val sessions = Mockito.mock(SessionManager::class.java)
            val recordings = Mockito.mock(QaRecordingStore::class.java)
            val viewers = ViewerSessionRegistry(ObjectMapper())
            val relay = QaMediaRelay(sessions, viewers, StreamProperties(mediaWorkerUrl = worker.url), recordings)
            val ended = recording("ended-instance")
            val active = recording("active-instance")
            Mockito.`when`(sessions.hasSession(ended.instanceId)).thenReturn(true)
            Mockito.`when`(sessions.hasSession(active.instanceId)).thenReturn(true)
            Mockito.`when`(recordings.active()).thenReturn(listOf(ended, active))
            relay.prepare()
            val activeStream = worker.recordingStreams.getValue(active.id.toString())
            val endedStream = worker.recordingStreams.getValue(ended.id.toString())

            Mockito.`when`(recordings.active()).thenReturn(listOf(active))
            Mockito.`when`(sessions.hasSession(ended.instanceId)).thenReturn(false)
            Mockito.doThrow(IllegalArgumentException("SDK session disconnected"))
                .`when`(sessions).send(ended.instanceId, StreamStopMessage(endedStream))
            worker.failDelete = deleteFails
            worker.requests.clear()
            relay.prepare()
            relay.prepare()

            assertThat(worker.requests.count { it == "PUT /internal/streams/$activeStream" }).isEqualTo(2)
            assertThat(worker.requests.count { it.startsWith("DELETE ") }).isEqualTo(1)
            Mockito.verify(recordings, Mockito.times(3)).expire()
        }
    }

    @Test
    fun `worker가 복구되면 SDK 연결 후 기존 viewer에 ready와 새 offer를 한 번 보낸다`() = runBlocking<Unit> {
        WorkerServer().use { worker ->
            val sessions = Mockito.mock(SessionManager::class.java)
            val recordings = Mockito.mock(QaRecordingStore::class.java)
            val viewers = ViewerSessionRegistry(ObjectMapper())
            val relay = QaMediaRelay(sessions, viewers, StreamProperties(mediaWorkerUrl = worker.url), recordings)
            val active = recording("active-instance")
            Mockito.`when`(sessions.hasSession(active.instanceId)).thenReturn(true)
            Mockito.`when`(recordings.active()).thenReturn(listOf(active))
            val viewerId = UUID.randomUUID().toString()
            val viewer = viewers.admit(active.instanceId, viewerId, Mockito.mock(WebSocketSession::class.java)).viewer
            val messages = CopyOnWriteArrayList<String>()
            val subscription = viewer.outbound().subscribe { messages.add(it) }
            try {
                relay.prepare()
                relay.openViewer(active.instanceId, viewerId)
                messages.clear()
                worker.requests.clear()
                worker.states.clear()
                worker.newState = "connecting"

                relay.prepare()
                assertThat(messages).isEmpty()
                val starts = Mockito.mockingDetails(sessions).invocations.filter { it.method.name == "send" && it.arguments[1] is StreamStartMessage }
                assertThat(starts).hasSize(2)
                val previousId = (starts[0].arguments[1] as StreamStartMessage).streamId
                val replacementId = (starts[1].arguments[1] as StreamStartMessage).streamId
                assertThat(replacementId).isNotEqualTo(previousId)
                val beforeLateSignal = worker.requests.size
                relay.sdkSignal(active.instanceId, previousId, """{"type":"STREAM_STATE","state":"STOPPED"}""")
                assertThat(worker.requests).hasSize(beforeLateSignal)
                relay.prepare()
                assertThat(messages).isEmpty()
                assertThat(Mockito.mockingDetails(sessions).invocations.count { it.method.name == "send" && it.arguments[1] is StreamStartMessage }).isEqualTo(2)

                worker.states.replaceAll { _, _ -> "connected" }
                relay.prepare()
                relay.prepare()
                assertThat(messages).hasSize(2)
                assertThat(messages[0]).contains("STREAM_READY", viewerId)
                assertThat(messages[1]).contains("WEBRTC_OFFER", viewerId)
                assertThat(worker.requests.count { it.endsWith("/viewers/$viewerId") }).isEqualTo(1)
            } finally {
                subscription.dispose()
                viewers.release(viewer)
            }
        }
    }

    private fun recording(instanceId: String) = QaRecording(UUID.randomUUID(), instanceId, Instant.now(), null, null, null)

    private class WorkerServer : AutoCloseable {
        val requests = CopyOnWriteArrayList<String>()
        val states = ConcurrentHashMap<String, String>()
        val recordingStreams = ConcurrentHashMap<String, String>()
        @Volatile var failDelete = false
        @Volatile var newState = "connected"
        private val mapper = ObjectMapper()
        private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
            createContext("/internal/streams") { exchange ->
                val path = exchange.requestURI.path
                requests.add("${exchange.requestMethod} $path")
                val streamId = path.split('/')[3]
                var status = 200
                val response = when (exchange.requestMethod) {
                    "PUT" -> {
                        val configuration = mapper.readTree(exchange.requestBody)
                        configuration["recordingId"]?.takeUnless { it.isNull }?.asText()?.let { recordingStreams[it] = streamId }
                        val created = states.putIfAbsent(streamId, newState) == null
                        """{"created":$created,"state":"${states.getValue(streamId)}"}"""
                    }
                    "DELETE" -> {
                        if (failDelete) status = 503 else states.remove(streamId)
                        "{}"
                    }
                    "POST" -> """{"type":"WEBRTC_OFFER","streamId":"${path.substringAfterLast('/')}","sdp":"recovered-offer"}"""
                    else -> error("Unexpected worker request")
                }
                val bytes = response.toByteArray()
                exchange.responseHeaders.add("Content-Type", "application/json")
                exchange.sendResponseHeaders(status, bytes.size.toLong())
                exchange.responseBody.use { it.write(bytes) }
                exchange.close()
            }
            start()
        }
        val url get() = "http://127.0.0.1:${server.address.port}"
        override fun close() = server.stop(0)
    }
}
