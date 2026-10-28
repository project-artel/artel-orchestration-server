package kr.artel.orchestration.stream.service

import dev.onvoid.webrtc.media.video.VideoFrame
import jakarta.annotation.PreDestroy
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.qa.review.VideoGapRequest
import kr.artel.orchestration.qa.review.VideoSegmentRequest
import kr.artel.orchestration.stream.config.StreamProperties
import org.bytedeco.ffmpeg.global.avutil.AV_PIX_FMT_YUV420P
import org.bytedeco.javacv.FFmpegFrameRecorder
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.nio.ByteBuffer
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.ScheduledThreadPoolExecutor
import java.util.concurrent.ThreadPoolExecutor
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** 수신 callback을 기다리게 하지 않고 orch 프로세스 안에서만 변환·저장한다. */
@Service
class QaVideoRecorder(private val recordings: QaRecordingStore, private val properties: StreamProperties) {
    private val logger = LoggerFactory.getLogger(javaClass)
    private val streams = ConcurrentHashMap<UUID, Encoder>()
    private val stopping = AtomicBoolean(false)
    private val uploadLock = Object()
    private var pendingSegments = 0
    private var pendingBytes = 0L
    private val uploads = ThreadPoolExecutor(2, 2, 0, TimeUnit.SECONDS, ArrayBlockingQueue(10),
        { task -> Thread(task, "qa-video-upload").apply { isDaemon = true } })
    private val gaps = LinkedHashMap<UUID, MissingFrames>()
    private val gapWriter = ScheduledThreadPoolExecutor(1) { task -> Thread(task, "qa-video-gaps").apply { isDaemon = true } }
        .apply { scheduleWithFixedDelay({ flushGaps() }, 1, 1, TimeUnit.SECONDS) }

    fun update(streamId: UUID, recordingId: UUID?) {
        check(!stopping.get()) { "영상 저장 서비스가 종료 중입니다." }
        if (recordingId == null && !streams.containsKey(streamId)) return
        synchronized(streams) {
            check(!stopping.get()) { "영상 저장 서비스가 종료 중입니다." }
            if (!streams.containsKey(streamId)) {
                check(streams.size < properties.mediaMaxIngress) { "동시 영상 저장 상한에 도달했습니다." }
                streams[streamId] = Encoder(streamId)
            }
            streams.getValue(streamId).recordingId = recordingId
        }
    }

    /** frame은 빌려 쓴다. 대기열에 들어갈 때만 retain하고 encoder가 반드시 release한다. */
    fun accept(streamId: UUID, frame: VideoFrame) {
        streams[streamId]?.offer(frame)
    }

    suspend fun closeStream(streamId: UUID) = withContext(Dispatchers.IO) {
        val encoder = streams[streamId] ?: return@withContext
        synchronized(encoder) { encoder.closing.set(true) }
        // JNI 호출을 interrupt하거나 처리 중인 buffer를 다른 thread에서 해제하지 않는다.
        encoder.finished.get(120, TimeUnit.SECONDS)
        // 업로드 작업은 파일과 byte array만 소유하므로 peer와 frame 해제를 기다리게 하지 않는다.
        streams.remove(streamId, encoder)
    }

    private inner class Encoder(val streamId: UUID) {
        @Volatile var recordingId: UUID? = null
        val closing = AtomicBoolean(false)
        val finished = CompletableFuture<Unit>()
        val frames = ArrayBlockingQueue<ReceivedFrame>(3)
        private var retainedBytes = 0L
        private var retainedCount = 0
        private val thread = Thread({ encode() }, "qa-video-encode-$streamId").apply { isDaemon = true; start() }

        fun offer(frame: VideoFrame) {
            val recording = recordingId ?: return
            val now = Instant.now()
            val size = frame.buffer.width.toLong() * frame.buffer.height +
                2L * ((frame.buffer.width + 1) / 2) * ((frame.buffer.height + 1) / 2)
            if (frame.buffer.width !in 2..1920 || frame.buffer.height !in 2..1080) {
                missing(recording, now, now.plusNanos(FRAME_NANOS), "FRAME_DIMENSIONS_EXCEEDED")
                return
            }
            synchronized(this) {
                if (closing.get() || stopping.get()) return
                if (retainedCount >= 3 || retainedBytes + size > MAX_FRAME_BYTES) {
                    missing(recording, now, now.plusNanos(FRAME_NANOS), "ENCODER_BACKPRESSURE")
                    return
                }
                frame.retain()
                retainedCount++
                retainedBytes += size
                if (!frames.offer(ReceivedFrame(frame, recording, now, System.nanoTime(), size))) {
                    retainedCount--; retainedBytes -= size; frame.release()
                    missing(recording, now, now.plusNanos(FRAME_NANOS), "ENCODER_BACKPRESSURE")
                }
            }
        }

        private fun encode() {
            var writer: Mp4Segment? = null
            try {
                while (!closing.get() || frames.isNotEmpty()) {
                    val received = frames.poll(100, TimeUnit.MILLISECONDS)
                    if (received == null) {
                        if (writer != null && (writer.recordingId != recordingId || System.nanoTime() - writer.lastFrameNanos > TimeUnit.SECONDS.toNanos(2))) {
                            enqueue(writer.finish()); writer = null
                        }
                        continue
                    }
                    try {
                        if (writer != null && (writer.recordingId != received.recordingId || writer.width != received.frame.buffer.width ||
                            writer.height != received.frame.buffer.height || writer.rotation != received.frame.rotation ||
                            received.nanoTime - writer.startNanos >= TimeUnit.SECONDS.toNanos(60) || Files.size(writer.path) >= MAX_SEGMENT_BYTES - 1024 * 1024)) {
                            enqueue(writer.finish()); writer = null
                        }
                        if (writer == null) writer = Mp4Segment(received, Path.of(properties.mediaDirectory))
                        writer.write(received)
                    } catch (failure: Throwable) {
                        writer?.abort()
                        missing(received.recordingId, writer?.startedAt ?: received.instant, received.instant.plusNanos(FRAME_NANOS), "ENCODING_FAILED")
                        writer = null
                        logger.warn("QA 영상 인코딩 실패 [streamId={}]", streamId, failure)
                    } finally {
                        received.frame.release()
                        synchronized(this) { retainedCount--; retainedBytes -= received.bytes }
                    }
                }
                writer?.let { enqueue(it.finish()) }
            } catch (failure: Throwable) {
                try { writer?.abort() } catch (cleanup: Throwable) { failure.addSuppressed(cleanup) }
                writer?.let { missing(it.recordingId, it.startedAt, Instant.now(), "ENCODING_INTERRUPTED") }
                logger.warn("QA 영상 인코더 종료 실패 [streamId={}]", streamId, failure)
            } finally {
                synchronized(this) { closing.set(true) }
                try {
                    while (true) {
                        val received = frames.poll() ?: break
                        try { received.frame.release() } catch (failure: Throwable) { logger.warn("QA 영상 프레임 해제 실패", failure) }
                        missing(received.recordingId, received.instant, received.instant.plusNanos(FRAME_NANOS), "ENCODING_INTERRUPTED")
                    }
                } finally {
                    // 인코딩 실패 여부와 native 객체 해제 가능 시점을 분리한다.
                    finished.complete(Unit)
                }
            }
        }
    }

    private fun enqueue(segment: EncodedSegment) {
        val size = Files.size(segment.path)
        synchronized(uploadLock) {
            if (size !in 1..MAX_SEGMENT_BYTES || pendingSegments >= 10 || pendingBytes + size > MAX_UPLOAD_BYTES) {
                discard(segment.path)
                missing(segment.recordingId, segment.startedAt, segment.endedAt, "UPLOAD_BACKPRESSURE")
                return
            }
            pendingSegments++; pendingBytes += size
        }
        try {
            uploads.execute {
                try {
                    val content = Files.readAllBytes(segment.path)
                    var stored = false
                    for (attempt in 0..2) {
                        try {
                            runBlocking { withTimeout(30_000) { recordings.saveSegment(segment.recordingId, segment.id,
                                VideoSegmentRequest(segment.startedAt, segment.endedAt, size), content) } }
                            stored = true
                            break
                        } catch (failure: Exception) {
                            if (attempt < 2) Thread.sleep(1000L shl attempt)
                            else logger.warn("QA 영상 업로드 실패 [segmentId={}]", segment.id, failure)
                        }
                    }
                    if (!stored) missing(segment.recordingId, segment.startedAt, segment.endedAt, "UPLOAD_FAILED")
                } catch (failure: Exception) {
                    missing(segment.recordingId, segment.startedAt, segment.endedAt, "UPLOAD_FAILED")
                    logger.warn("QA 영상 업로드 준비 실패 [segmentId={}]", segment.id, failure)
                } finally {
                    discard(segment.path)
                    synchronized(uploadLock) { pendingSegments--; pendingBytes -= size }
                }
            }
        } catch (failure: Exception) {
            discard(segment.path)
            synchronized(uploadLock) { pendingSegments--; pendingBytes -= size }
            missing(segment.recordingId, segment.startedAt, segment.endedAt, "UPLOAD_INTERRUPTED")
        }
    }

    private fun discard(path: Path) {
        try { Files.deleteIfExists(path) } catch (failure: Exception) { logger.warn("QA 영상 임시 파일 삭제 실패 [file={}]", path, failure) }
    }

    private fun missing(recordingId: UUID, startedAt: Instant, endedAt: Instant, reason: String) {
        synchronized(gaps) {
            val previous = gaps[recordingId]
            if (previous == null && gaps.size >= 64) {
                logger.warn("QA 영상 누락 기록 대기 상한 [recordingId={}]", recordingId)
                return
            }
            gaps[recordingId] = if (previous == null) MissingFrames(UUID.randomUUID(), startedAt, endedAt, reason)
                else previous.copy(startedAt = minOf(previous.startedAt, startedAt), endedAt = maxOf(previous.endedAt, endedAt))
        }
    }

    private fun flushGaps() {
        val pending = synchronized(gaps) { gaps.toMap().also { gaps.clear() } }
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5)
        for ((recordingId, gap) in pending) {
            val remainingMillis = TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())
            if (remainingMillis <= 0) { missing(recordingId, gap.startedAt, gap.endedAt, gap.reason); continue }
            try {
                runBlocking { withTimeout(remainingMillis.coerceAtMost(5_000)) { recordings.gap(recordingId, VideoGapRequest(gap.id, gap.startedAt, gap.endedAt, gap.reason)) } }
            } catch (failure: Exception) {
                logger.warn("QA 영상 누락 기록 실패 [recordingId={}]", recordingId, failure)
                missing(recordingId, gap.startedAt, gap.endedAt, gap.reason)
            }
        }
    }

    @PreDestroy
    fun shutdown() {
        if (!stopping.compareAndSet(false, true)) return
        val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(120)
        val closing = synchronized(streams) { streams.values.toList() }
        closing.forEach { synchronized(it) { it.closing.set(true) } }
        for (encoder in closing) {
            try {
                val remaining = deadline - System.nanoTime()
                if (remaining > 0) encoder.finished.get(remaining, TimeUnit.NANOSECONDS)
            } catch (failure: Exception) { logger.warn("QA 영상 저장 종료 지연", failure) }
        }
        uploads.shutdown()
        uploads.awaitTermination(100, TimeUnit.SECONDS)
        gapWriter.shutdown()
        gapWriter.awaitTermination(6, TimeUnit.SECONDS)
        flushGaps()
    }

    private data class ReceivedFrame(val frame: VideoFrame, val recordingId: UUID, val instant: Instant, val nanoTime: Long, val bytes: Long)
    private data class EncodedSegment(val id: UUID, val recordingId: UUID, val path: Path, val startedAt: Instant, val endedAt: Instant)
    private data class MissingFrames(val id: UUID, val startedAt: Instant, val endedAt: Instant, val reason: String)

    private class Mp4Segment private constructor(
        val recordingId: UUID, val startedAt: Instant, val startNanos: Long, val width: Int, val height: Int,
        val rotation: Int, directory: Path,
    ) {
        val path: Path
        private val id = UUID.randomUUID()
        private val recorder: FFmpegFrameRecorder
        private val pixels = ByteBuffer.allocateDirect(width * height + 2 * ((width + 1) / 2) * ((height + 1) / 2))
        private var lastPtsNanos = 0L
        private var lastTick = -1L
        var lastFrameNanos = startNanos
            private set
        constructor(frame: ReceivedFrame, directory: Path) : this(frame.recordingId, frame.instant, frame.nanoTime,
            frame.frame.buffer.width, frame.frame.buffer.height, frame.frame.rotation, directory)

        init {
            Files.createDirectories(directory)
            path = Files.createTempFile(directory, "qa-$id-", ".mp4")
            recorder = FFmpegFrameRecorder(path.toFile(), width + width % 2, height + height % 2).apply {
                format = "mp4"
                videoCodecName = "libx264"
                pixelFormat = AV_PIX_FMT_YUV420P
                frameRate = 30.0
                setVideoOption("threads", "1")
                setVideoOption("preset", "ultrafast")
                setVideoOption("crf", "28")
                setVideoOption("tune", "zerolatency")
                setOption("movflags", "+faststart")
                setDisplayRotation(rotation.toDouble())
            }
            try { recorder.start() } catch (failure: Throwable) { recorder.release(); Files.deleteIfExists(path); throw failure }
        }

        fun write(frame: ReceivedFrame) {
            val elapsed = maxOf(0L, frame.nanoTime - startNanos)
            val tick = kotlin.math.round(elapsed / 1_000_000_000.0 * 30).toLong()
            if (tick <= lastTick) return
            val buffer = frame.frame.buffer.toI420()
            try {
                pixels.clear()
                plane(buffer.dataY, buffer.strideY, width, height)
                plane(buffer.dataU, buffer.strideU, (width + 1) / 2, (height + 1) / 2)
                plane(buffer.dataV, buffer.strideV, (width + 1) / 2, (height + 1) / 2)
                pixels.flip()
                lastPtsNanos = elapsed
                lastTick = tick
                lastFrameNanos = frame.nanoTime
                recorder.timestamp = lastPtsNanos / 1000
                recorder.recordImage(width, height, 8, 1, width, AV_PIX_FMT_YUV420P, pixels)
            } finally {
                if (buffer !== frame.frame.buffer) buffer.release()
            }
        }

        private fun plane(source: ByteBuffer, stride: Int, width: Int, height: Int) {
            for (row in 0 until height) {
                val line = source.duplicate()
                line.position(row * stride).limit(row * stride + width)
                pixels.put(line)
            }
        }

        fun finish(): EncodedSegment {
            try { recorder.stop() } finally { recorder.release() }
            return EncodedSegment(id, recordingId, path, startedAt, startedAt.plusNanos(lastPtsNanos + FRAME_NANOS))
        }

        fun abort() {
            try { recorder.release() } catch (_: Exception) { }
            Files.deleteIfExists(path)
        }
    }

    companion object {
        private const val FRAME_NANOS = 33_333_333L
        private const val MAX_FRAME_BYTES = 8L * 1024 * 1024
        private const val MAX_SEGMENT_BYTES = 32L * 1024 * 1024
        private const val MAX_UPLOAD_BYTES = 256L * 1024 * 1024
    }
}
