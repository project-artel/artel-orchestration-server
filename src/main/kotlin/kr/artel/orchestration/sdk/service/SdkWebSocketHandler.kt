package kr.artel.orchestration.sdk.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.service.QaExecutionFailureService
import kr.artel.orchestration.sdk.dto.BaseMessage
import kr.artel.orchestration.sdk.service.handler.SdkMessageHandler
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Qualifier
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import org.springframework.web.util.UriComponentsBuilder
import reactor.core.publisher.Mono
import java.net.URLDecoder
import java.nio.charset.StandardCharsets

private const val TOKEN_PARAM = "token"
private const val INSTANCE_ID_PARAM = "instanceId"

/**
 * 실시간 웹소켓 연결 수립, 핸드셰이크 시점의 인증, 메시지 수신 및 분기를 처리하는 핵심 핸들러
 *
 * 자격증명은 SDK 토큰이고, 대상은 instanceId다. 토큰을 쿼리 파라미터로 받는 이유는 브라우저가
 * 아닌 클라이언트라도 웹소켓 핸드셰이크에 헤더를 싣기가 라이브러리마다 다르기 때문이다.
 * 헤더가 아니라 URL에 실리므로 시큐리티 필터 체인이 걸러낼 수 없고, 검증을 여기서 직접 한다.
 * 토큰은 인증에만 쓰고 세션 맵이나 로그에는 남기지 않는다.
 *
 * ponytail: 쿼리 토큰. 프록시 접근 로그에 남을 수 있다. WebSocketSharp 커스텀 헤더로 옮기려면
 * SDK도 같이 바꿔야 한다.
 */
@Component
class SdkWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val instanceRepository: GameInstanceRepository,
    private val sessionManager: SessionManager,
    private val qaExecutionFailureService: QaExecutionFailureService,
    // 디코더가 두 개다. 브라우저 쪽이 @Primary라 이름만으로는 그쪽이 주입된다.
    @Qualifier("sdkJwtDecoder") private val sdkJwtDecoder: ReactiveJwtDecoder,
    private val sessionUserResolver: SessionUserResolver,
    handlers: List<SdkMessageHandler>
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(SdkWebSocketHandler::class.java)
    private val handlerMap = handlers.associateBy { it.messageType }

    // 인터페이스가 Mono<Void>를 요구하므로 시그니처는 유지하고, 내부는 mono { } 코루틴
    // 빌더로 감싼다. 저장소 조회가 suspend 함수라 코루틴 컨텍스트가 필요하다. 못 찾음(null)은
    // 명시적으로 분기해 거절한다. switchIfEmpty를 쓰지 않는 이유는 open()이 반환하는
    // Mono<Void>가 항상 비어 있어 거절 경로가 다시 도는 문제를 피하기 위해서다.
    override fun handle(session: WebSocketSession): Mono<Void> = mono {
        val token = queryParam(session, TOKEN_PARAM)
        val requestedInstanceId = queryParam(session, INSTANCE_ID_PARAM)?.toLongOrNull()

        if (token.isNullOrBlank() || requestedInstanceId == null) {
            logger.warn("웹소켓 연결 거부: 토큰 또는 instanceId가 없습니다.")
            session.close(CloseStatus(4001, "Missing credentials")).awaitFirstOrNull()
            return@mono
        }

        // 서명·만료·issuer·audience 검증은 디코더가 한다. 실패는 예외로 오므로 거절로 옮긴다.
        val userId = runCatching { sdkJwtDecoder.decode(token).awaitFirstOrNull() }
            .getOrNull()
            ?.let(sessionUserResolver::resolve)
            ?.userId

        // 토큰이 유효해도 남의 인스턴스면 붙을 수 없다. 인스턴스에서 프로젝트를 거슬러 올라가
        // 참여자인지 확인하므로, 클라이언트가 보낸 두 값이 서로 맞는지 따로 볼 필요가 없다.
        val entity = userId?.let {
            instanceRepository.findAccessibleByIdForMember(requestedInstanceId, it)
        }
        if (entity == null) {
            logger.warn("웹소켓 연결 거부: 유효하지 않은 토큰이거나 접근할 수 없는 인스턴스입니다.")
            session.close(CloseStatus(4001, "Invalid or unauthorized credentials")).awaitFirstOrNull()
            return@mono
        }

        // open()이 반환하는 전송/수신 파이프라인은 소켓이 닫힐 때까지 살아 있어야 한다.
        // 여기서 await하지 않으면 handle의 Mono가 즉시 완료되어 Spring이 세션을 닫는다.
        open(session, requireNotNull(entity.id).toString()).awaitFirstOrNull()
    }.then()

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
                        // handler.handle은 이제 suspend 함수라 mono { } 로 감싸 리액티브
                        // 파이프라인에 다시 얹는다. concatMap이 순서를 보장하는 구조는 그대로다.
                        mono { handler.handle(instanceId, payloadText, session) }
                            .then()
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
                // sdkDisconnected는 이제 suspend라 doFinally(동기 Reactor 콜백)에서
                // mono { } 로 감싸 fire-and-forget으로 흘린다.
                mono { qaExecutionFailureService.sdkDisconnected(instanceId.toLong()) }
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

    private fun queryParam(session: WebSocketSession, name: String): String? =
        UriComponentsBuilder.fromUri(session.handshakeInfo.uri)
            .build()
            .queryParams
            .getFirst(name)
            ?.let { URLDecoder.decode(it, StandardCharsets.UTF_8) }
}
