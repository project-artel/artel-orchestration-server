package kr.artel.orchestration.stream.service

import dev.onvoid.webrtc.PeerConnectionFactory
import dev.onvoid.webrtc.media.audio.HeadlessAudioDeviceModule
import dev.onvoid.webrtc.media.video.NativeI420Buffer
import dev.onvoid.webrtc.media.video.VideoFrame
import dev.onvoid.webrtc.media.video.I420Buffer
import dev.onvoid.webrtc.media.video.VideoFrameBuffer
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.qa.review.VideoSegmentRequest
import kr.artel.orchestration.stream.config.StreamProperties
import org.assertj.core.api.Assertions.assertThat
import org.bytedeco.javacv.FFmpegFrameGrabber
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.mockito.Mockito
import org.mockito.stubbing.Answer
import java.nio.file.Files
import java.nio.ByteBuffer
import java.nio.file.Path
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

class QaVideoRecorderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `입력이 멈춘 시간도 MP4 PTS와 저장 구간에 남는다`() = runBlocking<Unit> {
        withNativeFactory {
            val fixture = fixture()
            val stream = UUID.randomUUID()
            fixture.recorder.update(stream, UUID.randomUUID())
            send(fixture.recorder, stream)
            // 프레임 수가 아닌 실제 도착 간격으로 저장되는지 확인한다.
            Thread.sleep(800)
            send(fixture.recorder, stream)
            fixture.recorder.closeStream(stream)
            fixture.recorder.closeStream(stream)
            fixture.recorder.shutdown()
            assertThat(fixture.saved).hasSize(1)
            val segment = fixture.saved.single()
            val file = directory.resolve("decoded.mp4")
            Files.write(file, segment.content)
            FFmpegFrameGrabber(file.toFile()).use { reader ->
                reader.start()
                val timestamps = mutableListOf<Long>()
                while (reader.grabImage() != null) timestamps.add(reader.timestamp)
                assertThat(timestamps).hasSize(2)
                assertThat(timestamps.last() - timestamps.first()).isGreaterThan(700_000)
                val metadataMicros = Duration.between(segment.request.startedAt, segment.request.endedAt).toNanos() / 1000
                assertThat(kotlin.math.abs(metadataMicros - reader.lengthInTime)).isLessThan(100_000)
            }
            assertThat(Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("qa-") }.count() }).isZero()
        }
    }

    @Test
    fun `S3 업로드가 기다리는 중에도 stream 종료는 native frame을 정리하고 반환한다`() = runBlocking<Unit> {
        withNativeFactory {
            val uploadStarted = CountDownLatch(1)
            val uploadRelease = CountDownLatch(1)
            val fixture = fixture(onUpload = {
                uploadStarted.countDown()
                check(uploadRelease.await(10, TimeUnit.SECONDS))
            })
            val stream = UUID.randomUUID()
            val executor = Executors.newSingleThreadExecutor()
            try {
                fixture.recorder.update(stream, UUID.randomUUID())
                send(fixture.recorder, stream)
                val close = executor.submit { runBlocking { fixture.recorder.closeStream(stream) } }
                check(uploadStarted.await(5, TimeUnit.SECONDS))
                close.get(2, TimeUnit.SECONDS)
                assertThat(uploadRelease.count).isEqualTo(1)
            } finally {
                uploadRelease.countDown()
                executor.shutdown()
                executor.awaitTermination(10, TimeUnit.SECONDS)
                fixture.recorder.shutdown()
            }
            assertThat(fixture.saved).hasSize(1)
        }
    }

    @Test
    fun `저장 실패는 세 번 시도한 뒤 누락을 남기고 파일을 제거한다`() = runBlocking<Unit> {
        withNativeFactory {
            val fixture = fixture(failUpload = true)
            val stream = UUID.randomUUID()
            fixture.recorder.update(stream, UUID.randomUUID())
            send(fixture.recorder, stream)
            fixture.recorder.closeStream(stream)
            fixture.recorder.shutdown()
            assertThat(fixture.saved).hasSize(3)
            val calls = Mockito.mockingDetails(fixture.store).invocations
            assertThat(calls.any { it.method.name == "gap" }).isTrue()
            assertThat(Files.list(directory).use { it.count() }).isZero()
        }
    }

    @Test
    fun `인코딩보다 많은 입력을 보내도 retain한 프레임은 세 개를 넘지 않는다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        val buffer = CountingBuffer()
        fixture.recorder.update(stream, UUID.randomUUID())
        repeat(2_000) { fixture.recorder.accept(stream, VideoFrame(buffer, System.nanoTime())) }
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(buffer.peak.get()).isLessThanOrEqualTo(4)
        assertThat(buffer.references.get()).isEqualTo(1)
        assertThat(fixture.saved).isNotEmpty()
        assertThat(Mockito.mockingDetails(fixture.store).invocations.any { it.method.name == "gap" }).isTrue()
    }

    @Test
    fun `마지막 MP4 파일이 유실돼도 인코더를 종료하고 같은 slot을 다시 쓸 수 있다`() = runBlocking<Unit> {
        withNativeFactory {
            val fixture = fixture()
            val stream = UUID.randomUUID()
            fixture.recorder.update(stream, UUID.randomUUID())
            send(fixture.recorder, stream)
            val deadline = System.nanoTime() + 5_000_000_000L
            var segment: Path? = null
            while (segment == null && System.nanoTime() < deadline) {
                segment = Files.list(directory).use { files -> files.filter { Files.size(it) > 0 }.findFirst().orElse(null) }
                if (segment == null) Thread.sleep(10)
            }
            assertThat(segment).isNotNull()
            Files.delete(segment!!)
            fixture.recorder.closeStream(stream)
            fixture.recorder.closeStream(stream)
            fixture.recorder.update(stream, UUID.randomUUID())
            send(fixture.recorder, stream)
            fixture.recorder.closeStream(stream)
            fixture.recorder.shutdown()
            assertThat(fixture.saved).hasSize(1)
            assertThat(Mockito.mockingDetails(fixture.store).invocations.any { it.method.name == "gap" }).isTrue()
        }
    }

    @Test
    fun `허용 해상도를 넘는 프레임은 인코더에 들어가지 않는다`() = runBlocking<Unit> {
        withNativeFactory {
            val fixture = fixture()
            val stream = UUID.randomUUID()
            fixture.recorder.update(stream, UUID.randomUUID())
            send(fixture.recorder, stream, 1922, 1080)
            fixture.recorder.closeStream(stream)
            fixture.recorder.shutdown()
            assertThat(fixture.saved).isEmpty()
            assertThat(Mockito.mockingDetails(fixture.store).invocations.any { it.method.name == "gap" }).isTrue()
        }
    }

    private suspend fun fixture(failUpload: Boolean = false, onUpload: () -> Unit = {}): Fixture {
        val saved = CopyOnWriteArrayList<SavedSegment>()
        val store = Mockito.mock(QaRecordingStore::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "saveSegment") {
                onUpload()
                saved.add(SavedSegment(invocation.getArgument(2), invocation.getArgument<ByteArray>(3).copyOf()))
                if (failUpload) error("storage unavailable")
            }
            Unit
        })
        return Fixture(store, QaVideoRecorder(store, StreamProperties(mediaDirectory = directory.toString())), saved)
    }

    private fun send(recorder: QaVideoRecorder, stream: UUID, width: Int = 320, height: Int = 240) {
        val buffer = NativeI420Buffer.allocate(width, height)
        for (plane in listOf(buffer.dataY, buffer.dataU, buffer.dataV)) while (plane.hasRemaining()) plane.put(128.toByte())
        val frame = VideoFrame(buffer, System.nanoTime())
        try { recorder.accept(stream, frame) } finally { frame.release() }
    }

    private suspend fun withNativeFactory(block: suspend () -> Unit) {
        val audio = HeadlessAudioDeviceModule()
        val factory = PeerConnectionFactory(audio)
        try { block() } finally { factory.dispose(); audio.dispose() }
    }

    private data class SavedSegment(val request: VideoSegmentRequest, val content: ByteArray)
    private data class Fixture(val store: QaRecordingStore, val recorder: QaVideoRecorder, val saved: List<SavedSegment>)

    private class CountingBuffer : I420Buffer {
        val references = AtomicInteger(1)
        val peak = AtomicInteger(1)
        private val y = ByteBuffer.allocateDirect(1280 * 720)
        private val u = ByteBuffer.allocateDirect(640 * 360)
        private val v = ByteBuffer.allocateDirect(640 * 360)
        override fun retain() { val count = references.incrementAndGet(); peak.accumulateAndGet(count, ::maxOf) }
        override fun release() { check(references.decrementAndGet() >= 1) }
        override fun getWidth() = 1280
        override fun getHeight() = 720
        override fun getStrideY() = 1280
        override fun getStrideU() = 640
        override fun getStrideV() = 640
        override fun getDataY(): ByteBuffer = y.duplicate()
        override fun getDataU(): ByteBuffer = u.duplicate()
        override fun getDataV(): ByteBuffer = v.duplicate()
        override fun toI420(): I420Buffer = this
        override fun cropAndScale(x: Int, y: Int, width: Int, height: Int, scaledWidth: Int, scaledHeight: Int): VideoFrameBuffer =
            error("이 입력은 crop 또는 scale을 요청하지 않습니다.")
    }
}
