package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.TooManyRequestsException
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows
import org.mockito.Mockito
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Base64
import java.util.UUID
import javax.imageio.ImageIO

class InProcessMediaServiceTest {
    private val mapper = jacksonObjectMapper()
    private val jpeg: String = ByteArrayOutputStream().also {
        ImageIO.write(BufferedImage(320, 240, BufferedImage.TYPE_3BYTE_BGR), "jpeg", it)
    }.toByteArray().let { Base64.getEncoder().encodeToString(it) }

    @Test
    fun `연결을 종료하면 동시 영상 수신 자리를 다시 사용할 수 있다`() = runBlocking<Unit> {
        val f = fixture(StreamProperties(mediaMaxIngress = 1))
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        try {
            assertThat(f.service.configure(first, "game", null, emptyList()).created).isTrue()
            assertThrows<TooManyRequestsException> { f.service.configure(second, "game", null, emptyList()) }
            f.service.closeStream(first)
            f.service.closeStream(first)
            assertThat(f.service.configure(second, "game", null, emptyList()).created).isTrue()
        } finally { f.service.shutdown() }
    }

    @Test
    fun `올바른 JPEG를 녹화와 현재 viewer에 전달하고 수신 확인을 보낸다`() = runBlocking<Unit> {
        val f = fixture()
        val stream = UUID.randomUUID()
        val outbound = f.sessions.register("game", Mockito.mock(WebSocketSession::class.java))!!
        val viewer = f.viewers.admit("game", "viewer", Mockito.mock(WebSocketSession::class.java)).viewer
        try {
            f.service.configure(stream, "game", UUID.randomUUID(), emptyList())
            f.service.openViewer(stream, "viewer")
            f.service.sdkSignal(stream, frame(1))
            assertThat(f.service.configure(stream, "game", null, emptyList()).state).isEqualTo("connected")
            assertThat(Mockito.mockingDetails(f.recorder).invocations.count { it.method.name == "accept" }).isEqualTo(1)
            StepVerifier.create(viewer.outbound().take(1)).assertNext {
                val value = mapper.readTree(it)
                assertThat(value["streamId"].asText()).isEqualTo("viewer")
                assertThat(value["frameId"].asLong()).isEqualTo(1)
            }.verifyComplete()
            StepVerifier.create(outbound.take(1)).assertNext { assertThat(mapper.readTree(it)["accepted"].asBoolean()).isTrue() }.verifyComplete()
        } finally { f.service.shutdown(); f.viewers.release(viewer) }
    }

    @Test
    fun `잘못된 JPEG와 중복 ID와 전송률 초과는 녹화하지 않는다`() = runBlocking<Unit> {
        val f = fixture()
        val stream = UUID.randomUUID()
        f.sessions.register("game", Mockito.mock(WebSocketSession::class.java))
        try {
            f.service.configure(stream, "game", null, emptyList())
            f.service.sdkSignal(stream, """{"type":"STREAM_FRAME","frameId":0,"jpeg":"invalid"}""")
            f.service.sdkSignal(stream, frame(1))
            f.service.sdkSignal(stream, frame(1))
            f.service.sdkSignal(stream, frame(2))
            f.service.sdkSignal(stream, frame(3))
            assertThat(Mockito.mockingDetails(f.recorder).invocations.count { it.method.name == "accept" }).isLessThanOrEqualTo(2)
        } finally { f.service.shutdown() }
    }

    @Test
    fun `이전 SDK 세션의 frame을 새 연결에 적용하지 않는다`() = runBlocking<Unit> {
        val f = fixture()
        val stream = UUID.randomUUID()
        val old = Mockito.mock(WebSocketSession::class.java)
        f.sessions.register("game", old)
        try {
            f.service.configure(stream, "game", null, emptyList())
            f.sessions.removeSession("game", old)
            f.sessions.register("game", Mockito.mock(WebSocketSession::class.java))
            f.service.sdkSignal(stream, frame(1))
            assertThat(f.service.configure(stream, "game", null, emptyList()).state).isEqualTo("failed")
            assertThat(Mockito.mockingDetails(f.recorder).invocations.none { it.method.name == "accept" }).isTrue()
        } finally { f.service.shutdown() }
    }

    @Test
    fun `구 WebRTC SDK를 조용히 기다리지 않고 업데이트 오류를 전달한다`() = runBlocking<Unit> {
        val f = fixture()
        val stream = UUID.randomUUID()
        val viewer = f.viewers.admit("game", "viewer", Mockito.mock(WebSocketSession::class.java)).viewer
        try {
            f.service.configure(stream, "game", null, emptyList())
            f.service.openViewer(stream, "viewer")
            f.service.sdkSignal(stream, """{"type":"WEBRTC_OFFER","sdp":"old"}""")
            StepVerifier.create(viewer.outbound().take(1)).assertNext { assertThat(mapper.readTree(it)["code"].asText()).isEqualTo("SDK_UPDATE_REQUIRED") }.verifyComplete()
        } finally { f.service.shutdown(); f.viewers.release(viewer) }
    }

    @Test
    fun `encoder 종료 실패는 연결 자리를 남겨 다시 정리한다`() = runBlocking<Unit> {
        val f = fixture(StreamProperties(mediaMaxIngress = 1))
        val first = UUID.randomUUID()
        Mockito.`when`(f.recorder.closeStream(first)).thenThrow(IllegalStateException("busy")).thenReturn(Unit)
        try {
            f.service.configure(first, "game", UUID.randomUUID(), emptyList())
            assertThrows<IllegalStateException> { f.service.closeStream(first) }
            assertThrows<TooManyRequestsException> { f.service.configure(UUID.randomUUID(), "game", null, emptyList()) }
            f.service.closeStream(first)
            assertThat(f.service.configure(UUID.randomUUID(), "game", null, emptyList()).created).isTrue()
        } finally { f.service.shutdown() }
    }

    @Test
    fun `수신 확인을 읽지 않는 SDK도 ACK 이력을 계속 쌓지 않는다`() {
        val sessions = SessionManager(mapper)
        val outbound = sessions.register("game", Mockito.mock(WebSocketSession::class.java))!!
        StepVerifier.create(outbound.take(2), 0)
            .then { repeat(10_000) { sessions.sendMediaAck("game", mapOf("frameId" to it)) } }
            .thenRequest(2)
            .assertNext { assertThat(mapper.readTree(it)["frameId"].asInt()).isZero() }
            .assertNext { assertThat(mapper.readTree(it)["frameId"].asInt()).isEqualTo(9_999) }
            .verifyComplete()
    }

    @Test
    fun `heartbeat와 합친 최종 송신에서도 ACK 대기는 고정 상한을 유지한다`() {
        val sessions = SessionManager(mapper)
        val outbound = sessions.register("game", Mockito.mock(WebSocketSession::class.java))!!
        val wire = reactor.core.publisher.Flux.merge(1, outbound, reactor.core.publisher.Flux.never<String>())
        StepVerifier.create(wire.take(3), 0)
            .then { repeat(10_000) { sessions.sendMediaAck("game", mapOf("frameId" to it)) } }
            .thenRequest(3)
            .expectNextCount(2)
            .assertNext { assertThat(mapper.readTree(it)["frameId"].asInt()).isEqualTo(9_999) }
            .verifyComplete()
    }

    @Test
    fun `느린 viewer에는 최신 frame 하나만 남긴다`() {
        val viewers = ViewerSessionRegistry(mapper)
        val viewer = viewers.admit("game", "viewer", Mockito.mock(WebSocketSession::class.java)).viewer
        StepVerifier.create(viewer.outbound().take(2), 0)
            .then { repeat(1_000) { viewers.sendFrame("viewer", it.toLong(), "jpeg") } }
            .thenRequest(2)
            .assertNext { assertThat(mapper.readTree(it)["frameId"].asInt()).isZero() }
            .assertNext { assertThat(mapper.readTree(it)["frameId"].asInt()).isEqualTo(999) }
            .verifyComplete()
    }

    private fun frame(id: Long): String {
        return mapper.writeValueAsString(mapOf("type" to "STREAM_FRAME", "frameId" to id, "jpeg" to jpeg))
    }
    private fun fixture(properties: StreamProperties = StreamProperties()): Fixture {
        val recorder = Mockito.mock(QaVideoRecorder::class.java)
        val sessions = SessionManager(mapper)
        val viewers = ViewerSessionRegistry(mapper)
        return Fixture(InProcessMediaService(recorder, sessions, viewers, mapper, properties), recorder, sessions, viewers)
    }
    private data class Fixture(val service: InProcessMediaService, val recorder: QaVideoRecorder, val sessions: SessionManager, val viewers: ViewerSessionRegistry)
}
