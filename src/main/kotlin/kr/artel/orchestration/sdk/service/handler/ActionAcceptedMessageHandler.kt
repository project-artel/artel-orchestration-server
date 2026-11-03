package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.sdk.dto.ActionAcceptedDto
import kr.artel.orchestration.sdk.service.SdkActionDelivery
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

@Component
class ActionAcceptedMessageHandler(
    private val mapper: ObjectMapper,
    private val delivery: SdkActionDelivery,
) : SdkMessageHandler {
    override val messageType = "ACTION_ACCEPTED"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        delivery.accepted(instanceId, mapper.readValue(payloadText, ActionAcceptedDto::class.java), session)
    }
}
