package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.artel.orchestration.gameidentity.dto.SdkGameIdentityCatalog
import kr.artel.orchestration.gameidentity.service.GameIdentityService
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession

@Component
class GameIdentityCatalogMessageHandler(
    private val objectMapper: ObjectMapper,
    private val identities: GameIdentityService,
) : SdkMessageHandler {
    override val messageType = "GAME_IDENTITY_CATALOG"
    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        identities.recordSdkCatalog(instanceId.toLong(), objectMapper.readValue<SdkGameIdentityCatalog>(payloadText))
    }
}
