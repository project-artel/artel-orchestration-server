package kr.artel.orchestration.sdk.service.handler

import kr.artel.orchestration.contentmap.observe.ScreenObservationService
import kr.artel.orchestration.qa.service.QaSdkBridgeService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

/**
 * PULSE 판독을 **변환 없이** QA 브리지로 넘기는 핸들러 전략.
 *
 * 등록되지 않은 타입은 [kr.artel.orchestration.sdk.service.SdkWebSocketHandler] 가 프레임째
 * 버린다. 이 핸들러가 없으면 SDK 가 보낸 판독은 agent 에 닿지 않는다.
 *
 * **접지 않는다.** `GAME_STATE` 는 [kr.artel.orchestration.sdk.service.GameStateTransformer] 가
 * 조작 후보와 관찰값으로 정제하는데, 판독에 같은 일을 하면 판독을 도입한 이유가 사라진다.
 * `statics`(GameObject 에 걸리지 않은 값) · `changed`(무엇이 움직였는지) · `deactive` ·
 * `whole`/`reading` · `unwatchable`(못 읽은 것을 못 읽었다고 말하는 것)은 접는 순간 없어지고,
 * 그 중 마지막은 읽는 쪽이 "여기까지만 안다"를 알 수 있어야 한다는 규율 자체다.
 *
 * 전량 판독과 델타 판독을 가리지 않는다. 어느 쪽인지는 문서의 `whole` 이 말하고, 그 판단은
 * 받는 쪽 몫이다.
 */
@Component
class PulseMessageHandler(
    private val qaBridge: QaSdkBridgeService,
    private val screenObservation: ScreenObservationService,
) : SdkMessageHandler {

    private val logger = LoggerFactory.getLogger(PulseMessageHandler::class.java)

    override val messageType: String = "PULSE"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        // 본문은 찍지 않는다. 전량 판독이 실측 약 18 KB 이고 초당 한 번 오므로, 다른 핸들러처럼
        // 원문을 로그에 실으면 판독만으로 로그가 채워진다. 크기는 남겨 둔다 — 적재량을 나중에
        // 되짚을 수 있는 값이 그것뿐이다.
        logger.info("판독 수신 [instanceId: $instanceId]: ${payloadText.length}자")

        val gameInstanceId = instanceId.toLong()
        qaBridge.routePulse(gameInstanceId, payloadText)

        // 중계 **뒤에** 부른다. 앞에 두면 화면 적재가 느릴 때 판독이 agent 에 늦게 닿는다.
        //
        // 위의 "접지 않는다"는 중계 payload 에 대한 규율이고, 이쪽은 payload 를 건드리지 않는
        // 두 번째 소비자다 — 원문은 이미 나갔다. 실패는 [ScreenObservationService] 가 삼킨다.
        screenObservation.observe(gameInstanceId, payloadText)
    }
}
