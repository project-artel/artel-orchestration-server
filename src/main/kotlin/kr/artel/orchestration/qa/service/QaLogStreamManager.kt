package kr.artel.orchestration.qa.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kr.artel.orchestration.qa.dto.QaLogResponse
import org.springframework.stereotype.Service
import java.util.concurrent.ConcurrentHashMap

/**
 * Live fan-out of freshly appended QA logs, keyed by run.
 *
 * A [MutableSharedFlow] replaces the former Reactor `Sinks.Many`: [publish] and
 * [complete] stay plain (non-suspend) so they can be called from inside the
 * append pipelines, and [tryEmit] never blocks. A SharedFlow has no terminal
 * event, so [complete] simply drops the run's flow from the registry — new
 * subscribers see nothing further, which is the completion the SSE reader needs.
 * The live path itself is currently unused (the SSE endpoint polls the table via
 * [QaLogService.stream]); it is kept as the push-based alternative.
 */
@Service
class QaLogStreamManager {
    private val streams = ConcurrentHashMap<Long, MutableSharedFlow<QaLogResponse>>()

    fun live(qaTryId: Long): Flow<QaLogResponse> =
        streams.computeIfAbsent(qaTryId) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }.asSharedFlow()

    fun publish(log: QaLogResponse) {
        streams[log.qaTryId.toLong()]?.tryEmit(log)
    }

    fun complete(qaTryId: Long) {
        streams.remove(qaTryId)
    }
}
