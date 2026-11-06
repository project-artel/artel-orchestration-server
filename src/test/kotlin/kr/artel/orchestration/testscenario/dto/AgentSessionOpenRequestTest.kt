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

    /**
     * **추론 예산은 agent 계약의 이름으로 나간다.**
     *
     * `max_tokens` 다 — Anthropic 은 `effort` 를 안 받고 예산으로만 켠다. 이름이 어긋나면
     * agent 가 조용히 안 켜므로, 판이 도는 것만으로는 안 걸린다.
     */
    @Test
    fun `추론 예산은 max_tokens 로 나간다`() {
        val request = AgentSessionOpenRequest(
            userInput = "시나리오 생성",
            locale = "ko",
            projectId = 1,
            runId = 2,
            reasoning = AgentReasoning(maxTokens = 4_096),
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(request))

        assertThat(json.get("reasoning").get("max_tokens").asInt()).isEqualTo(4_096)
    }

    /** 안 켜면 칸 자체가 안 나간다 — agent 의 기본값이 "안 켠다"이므로 그것과 짝이다. */
    @Test
    fun `추론을 안 켜면 요청에서 생략한다`() {
        val request = AgentSessionOpenRequest(
            userInput = "시나리오 생성",
            locale = "ko",
            projectId = 1,
            runId = 2,
        )

        val json = objectMapper.readTree(objectMapper.writeValueAsString(request))

        assertThat(json.has("reasoning")).isFalse()
    }
}
