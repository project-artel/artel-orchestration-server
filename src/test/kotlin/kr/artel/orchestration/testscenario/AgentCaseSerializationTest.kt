package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.artel.orchestration.testscenario.dto.AgentCase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

class AgentCaseSerializationTest {
    @Test
    fun `omits unspecified functions and preserves the test_step wire name`() {
        val payload = AgentCase(1, "scene", null, "click", "opened", checks = null, cannotCheck = emptyList())
        val json = jacksonObjectMapper().valueToTree<com.fasterxml.jackson.databind.JsonNode>(payload)

        assertThat(json.has("functions")).isFalse()
        assertThat(json["test_step"].asText()).isEqualTo("click")
        assertThat(json.has("testStep")).isFalse()
        assertThat(json.has("precondition")).isTrue()
    }
}
