package kr.artel.orchestration.demonstration

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.reactor.mono
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.ApiException
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.*
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import reactor.core.publisher.Sinks
import java.time.Duration
import java.util.UUID
import java.util.concurrent.ArrayBlockingQueue

@Component
class DemonstrationWebSocketHandler(private val service: DemonstrationService,private val repo: DemonstrationRepository,
    private val runtime: DemonstrationRuntime,private val resolver: SessionUserResolver,private val mapper: ObjectMapper) : WebSocketHandler {
    override fun handle(socket: WebSocketSession): Mono<Void> {
        val inbound=socket.receive()
        return mono {
            val principal=socket.handshakeInfo.principal.awaitSingleOrNull() as? Authentication
            val jwt=principal?.principal as? Jwt
            val user=jwt?.let { resolver.resolve(it)?.userId }
            val value=UriComponentsBuilder.fromUri(socket.handshakeInfo.uri).build().queryParams.getFirst("sessionId")
            val id=runCatching { UUID.fromString(value) }.getOrNull()
            if(user == null || id == null) { socket.close(CloseStatus.POLICY_VIOLATION).awaitSingleOrNull(); return@mono }
            service.owned(id,user)
            if(!runtime.claim(id.toString(),socket.id)) { socket.close(CloseStatus(4409,"Control already connected")).awaitSingleOrNull(); return@mono }
            val sink=Sinks.many().unicast().onBackpressureBuffer<String>(ArrayBlockingQueue(64))
            fun reply(value: DemonstrationReply) {
                if(sink.tryEmitNext(mapper.writeValueAsString(value)).isFailure) throw IllegalStateException("시연 응답 큐가 가득 찼습니다.")
            }
            try {
                service.acquire(id,user)
                withTimeout(4000) {
                    while(repo.find(id)?.controlReady != true) delay(40)
                }
                reply(DemonstrationReply("READY"))
                val receive=inbound.timeout(Duration.ofSeconds(8)).concatMap { message ->
                    val text=message.payloadAsText
                    mono {
                        if(text.length > 16384) throw IllegalArgumentException("입력 크기를 확인해 주세요.")
                        val frame=mapper.readValue(text,DemonstrationInput::class.java)
                        when(frame.type) {
                            "RENEW" -> service.renew(id,user)
                            "INPUT" -> {
                                reply(inputReply(id,user,frame))
                            }
                            "RELEASE" -> { service.stop(id,user,true); socket.close().awaitSingleOrNull() }
                            else -> throw IllegalArgumentException("지원하지 않는 시연 명령입니다.")
                        }
                    }
                }.then().doFinally { sink.tryEmitComplete() }
                socket.send(sink.asFlux().map(socket::textMessage)).and(receive).awaitSingleOrNull()
            } catch(e: kotlinx.coroutines.CancellationException) { throw e }
            catch(e: Exception) {
                // 내부 예외 원문을 웹소켓으로 보내지 않는다.
                sink.tryEmitNext(mapper.writeValueAsString(DemonstrationReply("ERROR",code="control_ended",message=if(e is ApiException) e.message else "시연 연결이 종료되었습니다.")))
                socket.close(CloseStatus(4410,"Control ended")).awaitSingleOrNull()
            } finally {
                runtime.release(id.toString(),socket.id)
                sink.tryEmitComplete()
                withContext(NonCancellable) { service.stop(id,user,true) }
            }
        }.then()
    }

    internal suspend fun inputReply(id: UUID, userId: Long, frame: DemonstrationInput): DemonstrationReply {
        val event=service.input(id,userId,frame)
        service.awaitInputCompletion(id,userId,event.id)
        // ACK는 성공 판정이 아니라 SDK의 해당 입력 처리가 끝났다는 흐름 제어 신호다.
        return DemonstrationReply("ACK",frame.sequence,event.id)
    }
}
