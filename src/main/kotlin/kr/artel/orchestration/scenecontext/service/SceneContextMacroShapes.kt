package kr.artel.orchestration.scenecontext.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.contentmap.dto.MacroScreenRow
import kr.artel.orchestration.contentmap.macro.MacroWriteFrames
import kr.artel.orchestration.scenecontext.dto.SceneContextMacroParameter
import kr.artel.orchestration.scenecontext.dto.SceneContextMacroScreen

/**
 * `macro` 의 `parameters` · `screens` 를 scene context 가 싣는 모양으로 바꾼다.
 *
 * scene context 응답과 `MACRO_SEARCH_RESULT`(ARTEL-986)가 같은 모양을 싣기로 했으므로 변환을 한
 * 자리에 둔다. 따로 두면 `declared_type` 같은 tree key 를 읽는 코드가 두 벌이 된다.
 */
object SceneContextMacroShapes {

    private const val UNKNOWN_PARAMETER_TYPE = "unknown"

    /**
     * 진입점 `def` 의 parameter 를 **선언한 순서 그대로** 읽는다. 이름이 없는 항목은 건너뛰고, 타입을
     * 못 읽는 항목은 `unknown` 으로 싣는다 — 이름과 개수와 순서가 호출 모양이라 타입이 비었다고 빼면
     * agent 가 인자 개수를 틀린다.
     */
    fun parameters(objectMapper: ObjectMapper, declared: Json?): List<SceneContextMacroParameter> {
        val parameters = declared
            ?.let { runCatching { objectMapper.readTree(it.asString()) }.getOrNull() }
            ?.takeIf(JsonNode::isArray)
            ?: return emptyList()
        return parameters.mapNotNull { parameter ->
            val name = parameter.path(MacroWriteFrames.PARAMETER_NAME_FIELD)
                .takeIf(JsonNode::isTextual)?.asText() ?: return@mapNotNull null
            val type = parameter.path(MacroWriteFrames.DECLARED_TYPE_FIELD)
                .takeIf(JsonNode::isTextual)?.asText() ?: UNKNOWN_PARAMETER_TYPE
            SceneContextMacroParameter(name = name, type = type)
        }
    }

    /** `screen` id 는 문자열로 싣는다(64비트 정밀도). */
    fun screens(rows: List<MacroScreenRow>): List<SceneContextMacroScreen> =
        rows.map { SceneContextMacroScreen(screenId = it.screenId.toString(), sceneName = it.sceneName) }
}
