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
import java.util.UUID

class InProcessMediaServiceTest {
    @Test
    fun `native 수신 연결을 종료하면 같은 서버의 동시 연결 자리를 다시 사용할 수 있다`() = runBlocking<Unit> {
        val recorder = Mockito.mock(QaVideoRecorder::class.java)
        val service = service(recorder, StreamProperties(mediaMaxIngress = 1))
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        try {
            assertThat(service.configure(first, "game", null, emptyList()).created).isTrue()
            assertThat(service.configure(first, "game", null, emptyList()).created).isFalse()
            assertThrows<TooManyRequestsException> { service.configure(second, "another", null, emptyList()) }

            service.sdkSignal(first, """{"type":"STREAM_STATE","state":"FAILED"}""")
            assertThat(service.configure(first, "game", null, emptyList()).state).isEqualTo("failed")
            service.closeStream(first)
            service.closeStream(first)

            assertThat(service.configure(second, "another", null, emptyList()).created).isTrue()
            Mockito.verify(recorder).closeStream(first)
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `SDP보다 먼저 도착한 ICE 후보가 상한을 넘으면 더 쌓지 않는다`() = runBlocking<Unit> {
        val service = service(Mockito.mock(QaVideoRecorder::class.java), StreamProperties())
        val id = UUID.randomUUID()
        try {
            service.configure(id, "game", null, emptyList())
            val candidate = """{"type":"WEBRTC_ICE","candidate":{"candidate":"candidate:1 1 UDP 1 127.0.0.1 1234 typ host","sdpMid":"0","sdpMLineIndex":0}}"""
            repeat(64) { service.sdkSignal(id, candidate) }
            assertThrows<TooManyRequestsException> { service.sdkSignal(id, candidate) }
        } finally {
            service.shutdown()
        }
    }

    @Test
    fun `encoder 종료 실패는 연결 자리를 보존하고 재시도에서 정리한다`() = runBlocking<Unit> {
        val recorder = Mockito.mock(QaVideoRecorder::class.java)
        val service = service(recorder, StreamProperties(mediaMaxIngress = 1))
        val first = UUID.randomUUID()
        val second = UUID.randomUUID()
        Mockito.`when`(recorder.closeStream(first)).thenThrow(IllegalStateException("encoder still active")).thenReturn(Unit)
        try {
            service.configure(first, "game", UUID.randomUUID(), emptyList())
            assertThrows<IllegalStateException> { service.closeStream(first) }
            assertThrows<TooManyRequestsException> { service.configure(second, "another", null, emptyList()) }
            service.closeStream(first)
            assertThat(service.configure(second, "another", null, emptyList()).created).isTrue()
        } finally {
            service.shutdown()
        }
    }

    private fun service(recorder: QaVideoRecorder, properties: StreamProperties): InProcessMediaService {
        val mapper = jacksonObjectMapper()
        return InProcessMediaService(recorder, Mockito.mock(SessionManager::class.java), ViewerSessionRegistry(mapper), mapper, properties)
    }
}
