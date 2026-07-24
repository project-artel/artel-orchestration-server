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
}
