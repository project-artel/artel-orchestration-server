package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.CreateQaTryRequest

internal const val INPUT_MODE_FIELD = "input_mode"

enum class QaInputMode(val wire: String) {
    VIRTUAL("virtual"),
    OS("os");

    companion object {
        fun parse(value: String?): QaInputMode = when (value) {
            null, "virtual" -> VIRTUAL
            "os" -> OS
            else -> throw BadRequestException("inputMode must be one of virtual, os")
        }
    }
}

fun CreateQaTryRequest.toInputMode() = QaInputMode.parse(inputMode)
fun CreateQaRunRequest.toInputMode() = QaInputMode.parse(inputMode)

/** 예전 run의 누락만 virtual로 읽는다. 손상된 설정을 가상 입력으로 바꾸지 않는다. */
internal fun inputModeOf(runConfig: String, objectMapper: ObjectMapper): QaInputMode =
    inputModeOf(objectMapper.readTree(runConfig))

internal fun inputModeOf(runConfig: JsonNode): QaInputMode {
    val value = runConfig.get(INPUT_MODE_FIELD) ?: return QaInputMode.VIRTUAL
    if (!value.isTextual) throw BadRequestException("Stored input_mode must be virtual or os")
    return QaInputMode.parse(value.asText())
}
