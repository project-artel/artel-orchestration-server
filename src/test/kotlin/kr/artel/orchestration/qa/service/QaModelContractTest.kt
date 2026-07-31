package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.artel.orchestration.qa.dto.QaModelResponse
import kr.artel.orchestration.qa.dto.QaReasoningRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class QaModelContractTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    @Test
    fun `maps agent snake case catalog to home camel case contract`() {
        val model = objectMapper.readValue<QaModelResponse>(
            """
            {
              "id": "google/gemini-2.5-flash",
              "label": "Gemini 2.5 Flash",
              "provider": "Google",
              "supports_strict_json": true,
              "supports_vision": true,
              "input_modalities": ["text", "image"],
              "multimodal": true,
              "reasoning": {
                "kind": "max_tokens",
                "efforts": null,
                "min_tokens": 0,
                "max_tokens": 24576,
                "step": 128
              }
            }
            """.trimIndent()
        )

        val response = objectMapper.writeValueAsString(model)
        assertThat(response).contains("\"supportsVision\":true", "\"inputModalities\"")
        assertThat(response).doesNotContain("supports_vision", "input_modalities")
    }

    @Test
    fun `maps home maxTokens to agent max_tokens`() {
        val payload = QaReasoningRequest(maxTokens = 2048).toAgentPayload(objectMapper)

        assertThat(payload.path("max_tokens").asInt()).isEqualTo(2048)
        assertThat(payload.has("maxTokens")).isFalse()
    }
}
