package kr.artel.orchestration.stream.service

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
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration
import java.time.Instant
import java.util.UUID
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import javax.imageio.ImageIO

class QaVideoRecorderTest {
    @TempDir lateinit var directory: Path

    @Test
    fun `JPEG 입력이 멈춘 시간도 MP4 PTS와 저장 구간에 남는다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        val first = frame()
        fixture.recorder.update(stream, UUID.randomUUID())
        assertThat(fixture.recorder.accept(stream, first)).isTrue()
        assertThat(fixture.recorder.accept(stream, first.copy(instant = first.instant.plusMillis(800), nanoTime = first.nanoTime + 800_000_000))).isTrue()
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
            assertThat(reader.imageWidth).isEqualTo(320)
            assertThat(reader.imageHeight).isEqualTo(240)
            val metadataMicros = Duration.between(segment.request.startedAt, segment.request.endedAt).toNanos() / 1000
            assertThat(kotlin.math.abs(metadataMicros - reader.lengthInTime)).isLessThan(100_000)
        }
        assertThat(Files.list(directory).use { files -> files.filter { it.fileName.toString().startsWith("qa-") }.count() }).isZero()
    }

    @Test
    fun `S3 업로드가 기다리는 중에도 stream 종료는 반환하고 같은 slot을 다시 쓴다`() = runBlocking<Unit> {
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
            fixture.recorder.accept(stream, frame())
            val close = executor.submit { runBlocking { fixture.recorder.closeStream(stream) } }
            check(uploadStarted.await(5, TimeUnit.SECONDS))
            close.get(2, TimeUnit.SECONDS)
            assertThat(uploadRelease.count).isEqualTo(1)
            val next = UUID.randomUUID()
            fixture.recorder.update(next, UUID.randomUUID())
            assertThat(fixture.recorder.accept(next, frame())).isTrue()
            fixture.recorder.closeStream(next)
        } finally {
            uploadRelease.countDown()
            executor.shutdown()
            executor.awaitTermination(10, TimeUnit.SECONDS)
            fixture.recorder.shutdown()
        }
        assertThat(fixture.saved).hasSize(2)
    }

    @Test
    fun `저장 실패는 세 번 시도한 뒤 누락을 남기고 파일을 제거한다`() = runBlocking<Unit> {
        val fixture = fixture(failUpload = true)
        val stream = UUID.randomUUID()
        fixture.recorder.update(stream, UUID.randomUUID())
        fixture.recorder.accept(stream, frame())
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(fixture.saved).hasSize(3)
        assertGap(fixture)
        assertThat(Files.list(directory).use { it.count() }).isZero()
    }

    @Test
    fun `JPEG burst는 queue 포화 프레임을 버리고 누락을 남긴다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        val image = frame(1280, 720)
        fixture.recorder.update(stream, UUID.randomUUID())
        val accepted = (0 until 2_000).count { index ->
            fixture.recorder.accept(stream, image.copy(instant = image.instant.plusMillis(index.toLong()), nanoTime = image.nanoTime + index * 1_000_000L))
        }
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(accepted).isBetween(1, 1_999)
        assertThat(fixture.saved).isNotEmpty()
        assertGap(fixture)
    }

    @Test
    fun `마지막 MP4 파일이 유실돼도 인코더를 종료하고 같은 slot을 다시 쓸 수 있다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        fixture.recorder.update(stream, UUID.randomUUID())
        fixture.recorder.accept(stream, frame())
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
        fixture.recorder.accept(stream, frame())
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(fixture.saved).hasSize(1)
        assertGap(fixture)
    }

    @Test
    fun `JPEG 크기와 예상 decode 메모리 상한은 encode 전에 거부한다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        val image = frame()
        fixture.recorder.update(stream, UUID.randomUUID())
        assertThat(fixture.recorder.accept(stream, image.copy(width = 1922, height = 1080))).isFalse()
        assertThat(fixture.recorder.accept(stream, image.copy(content = ByteArray(128 * 1024 + 1)))).isFalse()
        // 해상도는 허용 범위여도 JPEG와 decode·converter surface의 합이 8MiB를 넘으면 받지 않는다.
        assertThat(fixture.recorder.accept(stream, image.copy(width = 1920, height = 1080, content = ByteArray(128 * 1024)))).isFalse()
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(fixture.saved).isEmpty()
        assertGap(fixture)
    }

    @Test
    fun `잘못된 JPEG와 metadata 불일치는 누락을 남기고 다음 stream을 막지 않는다`() = runBlocking<Unit> {
        val fixture = fixture()
        for (bad in listOf(frame().copy(content = byteArrayOf(0xff.toByte(), 0xd8.toByte(), 0xff.toByte(), 0xd9.toByte())), frame(640, 480).copy(width = 320, height = 240))) {
            val stream = UUID.randomUUID()
            fixture.recorder.update(stream, UUID.randomUUID())
            assertThat(fixture.recorder.accept(stream, bad)).isTrue()
            fixture.recorder.closeStream(stream)
        }
        val next = UUID.randomUUID()
        fixture.recorder.update(next, UUID.randomUUID())
        assertThat(fixture.recorder.accept(next, frame())).isTrue()
        fixture.recorder.closeStream(next)
        fixture.recorder.shutdown()
        assertThat(fixture.saved).hasSize(1)
        assertGap(fixture)
        assertThat(Files.list(directory).use { it.count() }).isZero()
    }

    @Test
    fun `JPEG 해상도가 바뀌면 새 MP4 segment로 저장한다`() = runBlocking<Unit> {
        val fixture = fixture()
        val stream = UUID.randomUUID()
        val first = frame()
        fixture.recorder.update(stream, UUID.randomUUID())
        assertThat(fixture.recorder.accept(stream, first)).isTrue()
        assertThat(fixture.recorder.accept(stream, frame(640, 480).copy(instant = first.instant.plusMillis(100), nanoTime = first.nanoTime + 100_000_000))).isTrue()
        fixture.recorder.closeStream(stream)
        fixture.recorder.shutdown()
        assertThat(fixture.saved).hasSize(2)
    }

    private fun fixture(failUpload: Boolean = false, onUpload: () -> Unit = {}): Fixture {
        val saved = CopyOnWriteArrayList<SavedSegment>()
        val store = Mockito.mock(QaRecordingStore::class.java, Answer<Any?> { invocation ->
            if (invocation.method.name == "saveSegment") {
                onUpload()
                saved.add(SavedSegment(invocation.getArgument(2), invocation.getArgument<ByteArray>(3).copyOf()))
                if (failUpload) error("storage unavailable")
            }
            Unit
        })
        return Fixture(store, QaVideoRecorder(store, StreamProperties(mediaDirectory = directory.toString(), mediaMaxIngress = 1)), saved)
    }

    private fun frame(width: Int = 320, height: Int = 240): JpegVideoFrame {
        val image = BufferedImage(width, height, BufferedImage.TYPE_3BYTE_BGR)
        val graphics = image.createGraphics()
        try { graphics.color = Color(70, 130, 210); graphics.fillRect(0, 0, width, height) } finally { graphics.dispose() }
        val bytes = ByteArrayOutputStream().use { output -> check(ImageIO.write(image, "jpeg", output)); output.toByteArray() }
        image.flush()
        return JpegVideoFrame(bytes, width, height, Instant.now(), System.nanoTime())
    }

    private fun assertGap(fixture: Fixture) {
        assertThat(Mockito.mockingDetails(fixture.store).invocations.any { it.method.name == "gap" }).isTrue()
    }

    private data class SavedSegment(val request: VideoSegmentRequest, val content: ByteArray)
    private data class Fixture(val store: QaRecordingStore, val recorder: QaVideoRecorder, val saved: List<SavedSegment>)
}
