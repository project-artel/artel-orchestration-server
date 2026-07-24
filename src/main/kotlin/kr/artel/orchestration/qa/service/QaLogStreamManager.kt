package kr.artel.orchestration.qa.service

import kr.artel.orchestration.qa.dto.QaLogResponse
import org.springframework.stereotype.Service
import reactor.core.publisher.Flux
import reactor.core.publisher.Sinks
import java.util.concurrent.ConcurrentHashMap

@Service
class QaLogStreamManager {
    private val streams = ConcurrentHashMap<Long, Sinks.Many<QaLogResponse>>()

    fun live(qaTryId: Long): Flux<QaLogResponse> =
        streams.computeIfAbsent(qaTryId) {
            Sinks.many().multicast().onBackpressureBuffer()
        }.asFlux()

    fun publish(log: QaLogResponse) {
        streams[log.qaTryId.toLong()]?.tryEmitNext(log)
    }

    fun complete(qaTryId: Long) {
        streams.remove(qaTryId)?.tryEmitComplete()
    }
}
