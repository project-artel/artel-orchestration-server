package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.qa.review.QaRecordingStore
import kr.artel.orchestration.stream.service.QaMediaRelay
import org.springframework.http.CacheControl
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController
import java.util.concurrent.TimeUnit

@RestController
class InternalQaStreamFrameController(
    private val recordings: QaRecordingStore,
    private val media: QaMediaRelay,
) {
    @GetMapping("/internal/qa-tries/{tryId}/stream-frame")
    suspend fun latestFrame(@PathVariable tryId: Long): ResponseEntity<ByteArray> {
        val recording = recordings.findActiveTryRecording(tryId)
        val frame = recording?.let { media.latestFrame(it.instanceId, it.id, TimeUnit.SECONDS.toNanos(1)) }
            ?: return ResponseEntity.noContent().cacheControl(CacheControl.noStore()).build()
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).contentType(MediaType.IMAGE_JPEG).body(frame)
    }
}
