package kr.artel.orchestration.sdk.service.handler

import com.fasterxml.jackson.databind.ObjectMapper
import org.springframework.stereotype.Component
import org.springframework.web.reactive.socket.WebSocketSession
import java.io.ByteArrayOutputStream
import java.util.Base64

@Component
class PulseChunkMessageHandler(
    private val objectMapper: ObjectMapper,
    private val pulseHandler: PulseMessageHandler,
) : SdkMessageHandler {
    override val messageType = "PULSE_CHUNK"

    override suspend fun handle(instanceId: String, payloadText: String, session: WebSocketSession) {
        val chunk = objectMapper.readTree(payloadText)
        val id = chunk.path("id").longValue()
        val index = chunk.path("index").asInt(-1)
        val count = chunk.path("count").asInt(-1)
        require(chunk.path("id").isIntegralNumber && count >= 2 && index in 0 until count)
        val bytes = Base64.getDecoder().decode(chunk.path("data").asText())
        require(bytes.isNotEmpty() && bytes.size <= 175 * 1024)

        // 연결마다 하나만 조립한다. 새 판독이나 연결 종료 시 미완성 데이터는 폐기된다.
        val attributes = session.attributes
        val assembly = if (index == 0) {
            Assembly(id, count).also { attributes[messageType] = it }
        } else {
            attributes[messageType] as? Assembly ?: return
        }
        if (assembly.id != id || assembly.count != count || assembly.nextIndex != index) {
            attributes.remove(messageType)
            return
        }

        assembly.data.write(bytes)
        assembly.nextIndex++
        if (assembly.nextIndex != count) return

        attributes.remove(messageType)
        val document = assembly.data.toString(Charsets.UTF_8.name())
        val pulse = objectMapper.readTree(document)
        require(pulse.path("type").asText() == "PULSE" && pulse.path("id").longValue() == id)
        pulseHandler.handle(instanceId, document, session)
    }

    private class Assembly(val id: Long, val count: Int) {
        val data = ByteArrayOutputStream()
        var nextIndex = 0
    }
}
