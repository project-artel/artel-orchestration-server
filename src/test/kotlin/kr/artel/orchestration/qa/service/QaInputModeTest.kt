package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import com.fasterxml.jackson.module.kotlin.readValue
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.sdk.dto.ActionItemDto
import kr.artel.orchestration.sdk.dto.ActionResponseDto
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test

class QaInputModeTest {
    private val mapper = jacksonObjectMapper()

    @Test
    fun `두 시작 API는 생략한 입력 모드를 virtual로 읽는다`() {
        assertThat(CreateQaRunRequest("1", "2").toInputMode()).isEqualTo(QaInputMode.VIRTUAL)
        assertThat(CreateQaTryRequest("1", "2").toInputMode()).isEqualTo(QaInputMode.VIRTUAL)
        assertThat(inputModeOf("{}", mapper)).isEqualTo(QaInputMode.VIRTUAL)
    }

    @Test
    fun `REST 요청의 os를 두 시작 경로에서 동일하게 읽는다`() {
        val run = mapper.readValue<CreateQaRunRequest>("""{"testRunId":"1","gameInstanceId":"2","inputMode":"os"}""")
        val attempt = mapper.readValue<CreateQaTryRequest>("""{"testScenarioId":"1","gameInstanceId":"2","inputMode":"os"}""")
        assertThat(run.toInputMode()).isEqualTo(QaInputMode.OS)
        assertThat(attempt.toInputMode()).isEqualTo(QaInputMode.OS)
    }

    @Test
    fun `잘못된 입력 모드를 기본값으로 바꾸지 않는다`() {
        for (mode in listOf("OS", "", "native", "auto")) {
            assertThatThrownBy { CreateQaRunRequest("1", "2", inputMode = mode).toInputMode() }
                .isInstanceOf(BadRequestException::class.java)
            assertThatThrownBy { CreateQaTryRequest("1", "2", inputMode = mode).toInputMode() }
                .isInstanceOf(BadRequestException::class.java)
        }
        assertThatThrownBy { inputModeOf("""{"input_mode":false}""", mapper) }
            .isInstanceOf(BadRequestException::class.java)
    }

    @Test
    fun `Agent 스냅샷은 backend가 고정한 입력 모드를 덮어쓰지 못한다`() {
        val config = mergeCarriedGates(
            """{"input_mode":"os"}""", mapper.readTree("""{"input_mode":"virtual","model":"test"}"""), mapper,
        )
        assertThat(config.path("input_mode").asText()).isEqualTo("os")
        assertThat(config.path("model").asText()).isEqualTo("test")
        val legacy = mergeCarriedGates("{}", mapper.readTree("""{"input_mode":"os"}"""), mapper)
        assertThat(legacy.path("input_mode").asText()).isEqualTo("virtual")
    }

    @Test
    fun `SDK ACTION에는 고정된 입력 모드를 실으며 재전송 복사에서도 유지한다`() {
        val original = ActionResponseDto(id = 34, actions = listOf(ActionItemDto(
            id = 1, method = "key_down", params = listOf("A"),
        )), inputMode = "os")
        val copied = original.copy(id = 35)
        val payload = mapper.readTree(mapper.writeValueAsString(copied))
        assertThat(payload.path("inputMode").asText()).isEqualTo("os")
        assertThat(payload.path("actions").first().path("method").asText()).isEqualTo("key_down")
        assertThat(mapper.readTree(mapper.writeValueAsString(original.copy(inputMode = "virtual")))
            .path("inputMode").asText()).isEqualTo("virtual")
    }
}
