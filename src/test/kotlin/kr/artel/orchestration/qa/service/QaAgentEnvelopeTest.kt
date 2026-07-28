package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant
import java.util.UUID

class QaAgentEnvelopeTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `shared envelope preserves correlation and display message`() {
        val envelope = QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "ACTION_RESULT",
            qaTryId = "42",
            correlationId = "5ed50471-f651-4f01-98a8-b4cf4fe94580",
            timestamp = Instant.parse("2026-07-24T00:00:00Z"),
            payload = objectMapper.readTree("""{"message":"Action completed","results":[]}""")
        )

        val json = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(envelope)

        assertThat(json["type"].asText()).isEqualTo("ACTION_RESULT")
        assertThat(json["qaTryId"].asText()).isEqualTo("42")
        assertThat(json["correlationId"].asText()).isEqualTo(envelope.correlationId)
        assertThat(json["payload"]["message"].asText()).isEqualTo("Action completed")
    }

    /**
     * 캡처 결과는 액션 결과의 `returnValue`를 타고 에이전트에 닿는다. 중계 경로가 payload를
     * `JsonNode`로 읽어 그대로 넘기므로 지금은 통과하지만, 누군가 이 자리를 타입이 고정된
     * DTO로 바꾸면 필드가 조용히 사라진다. 그때 증상은 "에이전트가 이미지를 못 본다"뿐이라
     * 원인을 찾기 어렵다.
     */
    @Test
    fun `preserves an action result returnValue the envelope has no field for`() {
        val payload = objectMapper.readTree(
            """
            {
              "message": "Action completed",
              "results": [
                {
                  "id": 1,
                  "success": true,
                  "returnValue": {
                    "captureId": "5ed50471",
                    "url": "https://storage.test/qa-captures/42/5ed50471.jpg?sig=x",
                    "mimeType": "image/jpeg",
                    "width": 1024,
                    "height": 576,
                    "targetId": 7,
                    "clipped": false
                  }
                }
              ]
            }
            """.trimIndent()
        )

        val envelope = QaAgentEnvelope(
            messageId = UUID.randomUUID().toString(),
            type = "ACTION_RESULT",
            qaTryId = "42",
            correlationId = "5ed50471-f651-4f01-98a8-b4cf4fe94580",
            timestamp = Instant.parse("2026-07-28T00:00:00Z"),
            payload = payload
        )

        val json = objectMapper.valueToTree<com.fasterxml.jackson.databind.JsonNode>(envelope)

        val returned = json["payload"]["results"][0]["returnValue"]
        assertThat(returned["url"].asText()).endsWith(".jpg?sig=x")
        assertThat(returned["targetId"].asInt()).isEqualTo(7)
        assertThat(returned["clipped"].asBoolean()).isFalse()
    }
}
