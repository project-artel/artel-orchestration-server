package kr.artel.orchestration.stream.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.stream.config.StreamProperties
import kr.artel.orchestration.stream.dto.SignalEnvelope
import kr.artel.orchestration.stream.dto.StreamMessageType
import kr.artel.orchestration.stream.dto.StreamReadyMessage
import org.slf4j.LoggerFactory
import org.springframework.security.core.Authentication
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.CloseStatus
import org.springframework.web.reactive.socket.WebSocketHandler
import org.springframework.web.reactive.socket.WebSocketSession
import org.springframework.web.util.UriComponentsBuilder
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.reactor.mono
import reactor.core.publisher.Mono
import java.util.UUID
import java.util.concurrent.TimeoutException

private const val INSTANCE_ID_PARAM = "instanceId"

/**
 * 브라우저가 게임 화면을 보기 위해 붙는 웹소켓.
 *
 * 이 경로는 `SecurityConfig`의 permitAll 목록에 없다. `anyExchange().authenticated()`가 이미
 * 덮으므로 핸드셰이크는 다른 브라우저 호출과 같은 `artel_access_token` 쿠키로 인증된다.
 * 토큰을 쿼리스트링으로 받지 않는 이유는 그렇게 하면 자격증명이 서버 접근 로그에 남기
 * 때문이다.
 *
 * 미디어는 여기를 지나지 않는다. 이 핸들러가 하는 일은 SDP와 ICE를 게임 쪽 소켓으로 옮기는
 * 것뿐이다.
 */
@Component
class ViewerWebSocketHandler(
    private val objectMapper: ObjectMapper,
    private val properties: StreamProperties,
    private val instanceRepository: GameInstanceRepository,
    private val sessionUserResolver: SessionUserResolver,
    private val sessionManager: SessionManager,
    private val viewers: ViewerSessionRegistry,
    private val relay: StreamSignalRelay
) : WebSocketHandler {

    private val logger = LoggerFactory.getLogger(ViewerWebSocketHandler::class.java)

    // 인터페이스가 Mono<Void>를 요구하므로 시그니처는 유지하고, 내부는 mono { } 코루틴
    // 빌더로 감싼다. userIdFrom과 findAccessibleByIdForMember가 이제 suspend라 코루틴
    // 컨텍스트가 필요하다. 못 찾음(null)은 명시적으로 분기해 거절한다. switchIfEmpty를 쓰지
    // 않는 이유는 open()이 반환하는 Mono<Void>가 항상 비어 있어 거절 경로가 다시 도는
    // 문제를 피하기 위해서다.
    override fun handle(session: WebSocketSession): Mono<Void> = mono {
        val instanceId = instanceIdFrom(session)
        if (instanceId == null) {
            session.close(CloseStatus(4003, "Missing or malformed instance id")).awaitFirstOrNull()
            return@mono
        }

        val userId = userIdFrom(session)
        val accessible =
            if (userId == null) null
            else instanceRepository.findAccessibleByIdForMember(instanceId, userId)

        if (accessible == null) {
            logger.warn("뷰어 연결 거부: 접근할 수 없는 인스턴스 - instanceId: $instanceId")
            session.close(CloseStatus(4403, "Not a member of the owning project")).awaitFirstOrNull()
            return@mono
        }

        // open()이 반환하는 전송/수신 파이프라인은 소켓이 닫힐 때까지 살아 있어야 한다.
        // 여기서 await하지 않으면 handle의 Mono가 즉시 완료되어 Spring이 세션을 닫는다.
        open(session, instanceId.toString()).awaitFirstOrNull()
    }.then()

    private fun open(session: WebSocketSession, instanceId: String): Mono<Void> {
        if (!sessionManager.hasSession(instanceId)) {
            logger.info("뷰어 연결 거부: 게임이 연결되어 있지 않습니다 - instanceId: $instanceId")
            return session.close(CloseStatus(4404, "Game is not connected"))
        }

        val streamId = UUID.randomUUID().toString()
        val (viewer, displaced) = viewers.admit(instanceId, streamId, session)

        if (displaced != null) {
            logger.info("뷰어 교체 - instanceId: $instanceId, 밀려난 streamId: ${displaced.streamId}")
            displaced.session
                .close(CloseStatus(4009, "Taken over by a newer viewer"))
                .subscribe()
        }

        logger.info("뷰어 연결 성공 - instanceId: $instanceId, streamId: $streamId")

        // 임대는 수신 흐름의 타임아웃으로 지킨다. 이 시간 동안 아무 메시지도 없다는 것은 상대가
        // 사라졌다는 뜻이다. 그 폭을 무엇에 맞춰 잡았는지는 StreamProperties.lease가 설명한다.
        // 별도의 청소 스케줄러를 두지 않는 이유는, 죽은 연결을 알아채는 데 필요한 신호가 이미
        // 여기 있기 때문이다.
        val receive = session.receive()
            .timeout(properties.lease)
            // handleViewerMessage는 이제 suspend라 mono { } 로 감싸 리액티브 파이프라인에
            // 다시 얹는다. flatMap이 프레임 처리 순서를 그대로 유지한다.
            .flatMap { message -> mono { handleViewerMessage(viewer, message.payloadAsText) }.then() }
            .then()
            .onErrorResume(TimeoutException::class.java) {
                logger.info("뷰어 임대 만료 - streamId: $streamId")
                Mono.empty()
            }
            .doOnError { error ->
                logger.error("뷰어 웹소켓 에러 [streamId: $streamId]: ${error.message}", error)
            }
            // 정리는 수신이 끝나는 시점에 건다. 전송 쪽이 끝나기를 기다리는 자리에 두면,
            // 전송은 세션이 지워지며 큐가 닫혀야 끝나므로 서로를 기다리다 멈춘다.
            .doFinally {
                // 자기가 현재 뷰어일 때만 스트림을 멈춘다. 밀려난 세션이 뒤늦게 정리되면서
                // 중단을 보내면 방금 시작한 새 스트림이 꺼진다. stopStream은 suspend라
                // doFinally(동기)에서 mono { } 로 감싸 구독한다.
                if (viewers.release(viewer)) {
                    mono { relay.stopStream(instanceId, streamId) }
                        .onErrorResume { Mono.empty() }
                        .subscribe()
                }
                logger.info("뷰어 연결 종료 - instanceId: $instanceId, streamId: $streamId")
            }

        val prologue = mono {
            viewers.send(viewer, StreamReadyMessage(streamId, properties.iceServers))
            relay.startStream(instanceId, streamId)
        }.then()

        return session.send(viewer.outbound().map(session::textMessage))
            .and(receive)
            .and(prologue)
    }

    /**
     * 브라우저가 보낸 메시지를 처리한다.
     *
     * 갱신은 도착한 것만으로 임대가 연장된다(수신 타임아웃이 다시 시작된다). SDK에도 넘기는
     * 이유는 SDK가 자기 타이머를 따로 돌리기 때문이다. 서버가 죽어도 게임이 스스로 멈추게
     * 하려면 그 타이머가 서버 바깥에 있어야 한다.
     */
    private suspend fun handleViewerMessage(
        viewer: ViewerSessionRegistry.ViewerSession,
        payloadText: String
    ) {
        val envelope = try {
            objectMapper.readValue(payloadText, SignalEnvelope::class.java)
        } catch (exception: Exception) {
            logger.warn("뷰어 메시지 파싱 실패 [streamId: ${viewer.streamId}]: ${exception.message}")
            return
        }

        try {
            when (envelope.type) {
                StreamMessageType.RENEW ->
                    relay.renewStream(viewer.instanceId, viewer.streamId)

                StreamMessageType.STOP ->
                    viewer.session.close(CloseStatus.NORMAL).awaitFirstOrNull()

                StreamMessageType.WEBRTC_ANSWER, StreamMessageType.WEBRTC_ICE ->
                    relay.toSdk(viewer, envelope.streamId, payloadText)

                else ->
                    logger.warn("정의되지 않은 뷰어 메시지 [streamId: ${viewer.streamId}]: ${envelope.type}")
            }
        } catch (error: kotlinx.coroutines.CancellationException) {
            // 뷰어 연결 취소는 삼키지 않고 전파해야 수신 루프가 정상 종료된다.
            throw error
        } catch (error: Exception) {
            // 게임이 방금 끊겼다면 중계는 실패한다. 그것 때문에 뷰어 연결까지 끊을 필요는
            // 없다. 스트림 상태는 SDK가 사라진 것으로 곧 드러난다.
            logger.warn("뷰어 메시지 처리 실패 [streamId: ${viewer.streamId}]: ${error.message}")
        }
    }

    /**
     * 토큰이 없거나 사용자 식별자 형식이 아니면 null이다. 호출자가 거절로 옮긴다.
     */
    private suspend fun userIdFrom(session: WebSocketSession): Long? {
        val principal = session.handshakeInfo.principal.awaitFirstOrNull() ?: return null
        val jwt = (principal as? Authentication)?.principal as? Jwt
        return jwt?.let(sessionUserResolver::resolve)?.userId
    }

    private fun instanceIdFrom(session: WebSocketSession): Long? =
        UriComponentsBuilder.fromUri(session.handshakeInfo.uri)
            .build()
            .queryParams
            .getFirst(INSTANCE_ID_PARAM)
            ?.toLongOrNull()
}
