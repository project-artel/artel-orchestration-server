package kr.artel.orchestration.stream.service

import reactor.core.publisher.Flux
import reactor.core.publisher.FluxSink

/** 느린 subscriber의 replay cursor에 과거 frame이 남지 않도록 pending 값 하나만 보관한다. */
internal class LatestMediaMessages {
    private val gate = Any()
    private var subscriber: FluxSink<String>? = null
    private var pending: String? = null
    private var closed = false
    val flux: Flux<String> = Flux.create({ sink ->
        synchronized(gate) {
            if (subscriber != null) sink.error(IllegalStateException("미디어 송신은 한 번만 구독할 수 있습니다."))
            else {
                subscriber = sink
                pending?.let(sink::next)
                pending = null
                if (closed) sink.complete()
            }
        }
    }, FluxSink.OverflowStrategy.LATEST)

    fun emit(value: String): Boolean = synchronized(gate) {
        if (closed) return false
        val sink = subscriber
        if (sink == null) pending = value else sink.next(value)
        true
    }

    fun close() = synchronized(gate) {
        closed = true
        pending = null
        subscriber?.complete()
    }
}
