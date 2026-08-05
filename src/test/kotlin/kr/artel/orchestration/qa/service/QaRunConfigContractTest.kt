package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.dto.QaReasoningRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 실행 설정이 Agent 계약대로 나가고, Agent 가 확정한 값이 온전히 돌아오는지.
 *
 * 여기서 깨지면 증상이 조용하다. 필드 이름이 어긋나면 Agent 는 그 축을 못 본 채
 * 자기 기본값으로 돌고, 응답 키가 어긋나면 런은 정상으로 보이는데 설정 칸만
 * 비어 쌓인다. 둘 다 나중에 집계를 열어보기 전까지 아무도 모른다.
 */
class QaRunConfigContractTest {
    private val objectMapper = jacksonObjectMapper().findAndRegisterModules()

    private fun openRequest(
        model: String? = null,
        language: String? = null,
        promptVersion: String? = null,
        arch: String? = null
    ) = QaSessionOpenRequest(
        model = model,
        reasoning = null,
        language = language,
        promptVersion = promptVersion,
        arch = arch?.let { objectMapper.readTree(it) },
        context = QaSessionOpenContext(
            qaTryId = "7",
            gameInstanceId = "1",
            testScenarioId = "1",
            scenario = objectMapper.createObjectNode()
        )
    )

    @Test
    fun `sends the run axes under the names the agent reads`() {
        val json = objectMapper.writeValueAsString(
            openRequest(
                model = "anthropic/claude-sonnet-5",
                language = "ko",
                promptVersion = "v3",
                arch = """{"vision":"off","tool_calls_per_step":20}"""
            )
        )

        assertThat(json).contains(
            "\"model\":\"anthropic/claude-sonnet-5\"",
            "\"language\":\"ko\"",
            "\"prompt_version\":\"v3\"",
            "\"vision\":\"off\""
        )
        assertThat(json).doesNotContain("promptVersion")
    }

    @Test
    fun `omits an axis nobody chose instead of nulling it`() {
        val json = objectMapper.writeValueAsString(openRequest())

        // An explicit null would be Orchestration overriding the Agent's default
        // with nothing; absence lets the Agent apply its own.
        assertThat(json).doesNotContain("prompt_version", "language", "arch", "\"model\"")
    }

    @Test
    fun `keeps the resolved config the agent answers with`() {
        val response = objectMapper.readValue<QaSessionOpenResponse>(
            """
            {
              "session_id": "abc",
              "run_config": {
                "model": "anthropic/claude-sonnet-5",
                "prompt_version": "v3",
                "agent_arch": "v2-tool-loop",
                "agent_fingerprint": "a3f1c9d2e8b0",
                "reasoning": {"effort": "high", "max_tokens": null},
                "reasoning_supported": true
              }
            }
            """.trimIndent()
        )

        assertThat(response.sessionId).isEqualTo("abc")
        assertThat(response.runConfig?.path("agent_fingerprint")?.asText()).isEqualTo("a3f1c9d2e8b0")
        assertThat(response.runConfig?.path("reasoning")?.path("effort")?.asText()).isEqualTo("high")
    }

    @Test
    fun `accepts an agent that does not report the config`() {
        // 구버전 Agent. 설정을 모르는 런은 집계에서 빠질 뿐, 시작 자체가 실패해서는 안 된다.
        val response = objectMapper.readValue<QaSessionOpenResponse>("""{"session_id": "abc"}""")

        assertThat(response.runConfig).isNull()
    }

    @Test
    fun `accepts fields the agent added after this was written`() {
        val response = objectMapper.readValue<QaSessionOpenResponse>(
            """{"session_id": "abc", "something_new": 1}"""
        )

        assertThat(response.sessionId).isEqualTo("abc")
    }

    @Test
    fun `maps the create request onto the run settings`() {
        val settings = CreateQaTryRequest(
            testScenarioId = "1",
            gameInstanceId = "2",
            model = "openai/gpt-4o",
            language = "en",
            promptVersion = "v2",
            reasoning = QaReasoningRequest(maxTokens = 2048),
            arch = objectMapper.readTree("""{"fold_stale_scenes":false}""")
        ).toRunSettings(objectMapper)

        assertThat(settings.model).isEqualTo("openai/gpt-4o")
        assertThat(settings.language).isEqualTo("en")
        assertThat(settings.promptVersion).isEqualTo("v2")
        assertThat(settings.reasoning?.path("max_tokens")?.asInt()).isEqualTo(2048)
        assertThat(settings.arch?.path("fold_stale_scenes")?.asBoolean()).isFalse()
    }

    @Test
    fun `a request that chooses nothing carries nothing`() {
        val settings = CreateQaTryRequest(testScenarioId = "1", gameInstanceId = "2")
            .toRunSettings(objectMapper)

        assertThat(settings).isEqualTo(QaRunSettings())
    }
}
