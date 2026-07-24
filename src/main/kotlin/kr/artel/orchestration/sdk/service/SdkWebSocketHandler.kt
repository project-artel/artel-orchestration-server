package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.sdk.dto.BaseMessage
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val INSTANCE_KEY_PARAM = "instanceKey"

/**
 * 실시간 웹소켓 연결 수립, 핸드셰이크 시점의 인증(instanceKey), 메시지 수신 및 분기를 처리하는
 * 핵심 핸들러
 *
 * 자격증명은 instanceKey지만 그 뒤로는 게임 인스턴스 id만 들고 다닌다. 키는 인증에만 쓰고
 * 세션 맵이나 로그에는 남기지 않는다.
 */
@Component
class SdkWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val instanceRepository: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val qaExecutionFailureService: QaExecutionFailureService,
    handlers: List<SdkMessageHandler>
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(SdkWebSocketHandler::class.java)
    private val handlerMap = handlers.associateBy { it.messageType }

    override fun handle(session: WebSocketSession): Mono<Void> {
        val instanceKey = instanceKeyFrom(session)

        if (instanceKey.isNullOrBlank()) {
            logger.warn("웹소켓 연결 거부: instanceKey가 없습니다.")
            return session.close(CloseStatus(4001, "Missing instance key"))
        }

        return instanceRepository.findActiveByInstanceKey(instanceKey)
            .map { requireNotNull(it.id).toString() }
            // singleOptional로 "찾음/못 찾음"을 값으로 바꾼다. 여기서 switchIfEmpty를 쓰면
            // 연결이 정상 종료됐을 때(Mono<Void>는 항상 비어 있다) 거절 경로가 다시 돈다.
            .singleOptional()
            .flatMap { found ->
                if (found.isPresent) {
                    open(session, found.get())
                } else {
                    logger.warn("웹소켓 연결 거부: 유효하지 않은 instanceKey")
                    session.close(CloseStatus(4001, "Invalid or unauthorized instance key"))
                }
            }
    }

    /**
     * 이미 연결된 인스턴스면 새 연결을 거절한다.
     *
     * 앞 연결을 밀어내지 않는 이유는, 진행 중인 QA 세션이 우연한 두 번째 실행 때문에 소켓을
     * 빼앗기는 쪽이 더 나쁘기 때문이다. 거절당한 쪽은 닫힘 코드로 이유를 알 수 있다.
     */
    private fun open(session: WebSocketSession, instanceId: String): Mono<Void> {
        val outbound = sessionManager.register(instanceId, session)

        if (outbound == null) {
            logger.warn("웹소켓 연결 거부: 이미 연결된 게임 인스턴스 - instanceId: $instanceId")
            return session.close(CloseStatus(4002, "Instance already connected"))
        }

        logger.info("웹소켓 연결 성공 - instanceId: $instanceId")

        // concatMap: 한 세션의 프레임을 순서대로 하나씩 처리한다. flatMap이면 프레임이 동시에
        // 처리되어, Agent로 나가는 unicast sink에 동시 tryEmitNext가 걸려 FAIL_NON_SERIALIZED로
        // 드롭되거나 GAME_STATE 순서가 뒤집힌다.
        val receive = session.receive()
            .concatMap { message ->
                val payloadText = message.payloadAsText
                try {
                    val base = objectMapper.readValue(payloadText, BaseMessage::class.java)
                    val handler = handlerMap[base.type]

                    if (handler != null) {
                        handler.handle(instanceId, payloadText, session)
                            .onErrorResume { err ->
                                logger.error("메시지 처리 최종 실패 [type=${base.type}, instanceId=$instanceId]: ${err.message}")
                                Mono.empty()
                            }
                    } else {
                        logger.warn("정의되지 않은 메시지 타입 수신 [instanceId: $instanceId]: ${base.type}")
                        Mono.empty()
                    }
                } catch (e: Exception) {
                    logger.error("메시지 처리 에러 [instanceId: $instanceId]: ${e.message}", e)
                    Mono.empty()
                }
            }
            .doOnError { error ->
                logger.error("웹소켓 에러 발생 [instanceId: $instanceId]: ${error.message}", error)
            }
            .then()
            // 정리는 수신이 끝나는 시점에 건다. 전송 쪽이 끝나기를 기다리는 자리에 두면,
            // 전송은 세션이 지워지며 큐가 닫혀야 끝나므로 서로를 기다리다 멈춘다.
            .doFinally {
                // 자기 세션일 때만 지운다. 늦게 끊긴 좀비 연결이 살아 있는 연결의 자리를
                // 비우면, SDK는 연결된 채로 액션을 받지 못한다.
                sessionManager.removeSession(instanceId, session)
                qaExecutionFailureService.sdkDisconnected(instanceId.toLong())
                    .subscribe(
                        {},
                        { error -> logger.error("QA SDK 연결 종료 처리 실패 [instanceId=$instanceId]", error) }
                    )
                logger.info("웹소켓 연결 종료 - instanceId: $instanceId")
            }

        // 전송과 수신을 함께 물려 둘 다 끝나야 핸들러가 끝난다. Spring이 문서에서 보여 주는
        // 형태 그대로다.
        return session.send(outbound.map(session::textMessage)).and(receive)
    }

    private fun instanceKeyFrom(session: WebSocketSession): String? =
        UriComponentsBuilder.fromUri(session.handshakeInfo.uri)
            .build()
            .queryParams
            .getFirst(INSTANCE_KEY_PARAM)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
}
