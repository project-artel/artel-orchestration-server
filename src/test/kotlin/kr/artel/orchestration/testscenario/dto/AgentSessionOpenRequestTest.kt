package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentSessionOpenRequestTest {
    private val objectMapper = jacksonObjectMapper()

    @Test
    fun `명시한 model은 요청에 포함한다`() {
        val request = AgentSessionOpenRequest(
            userInput = "시나리오 생성",
            model = "openai/gpt-5.6-luna",
            locale = "ko",
            projectId = 1,
            runId = 2,
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(request))

        assertThat(json.get("model").asText()).isEqualTo("openai/gpt-5.6-luna")
    }

    @Test
    fun `model을 지정하지 않으면 요청에서 생략한다`() {
        val request = AgentSessionOpenRequest(
            userInput = "시나리오 생성",
            locale = "ko",
            projectId = 1,
            runId = 2,
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(request))

        assertThat(json.has("model")).isFalse()
    }
}
