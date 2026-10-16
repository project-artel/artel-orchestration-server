package kr.artel.orchestration.developermacro.agent

import com.fasterxml.jackson.databind.JsonNode

/**
 * agent-server `POST /internal/macros/check` 요청(ARTEL-1087).
 *
 * @property imports `from macros import` 로 가져올 수 있는 같은 프로젝트의 developer macro. 이름 → 원문이다.
 *   agent-server 가 필요한 것만 골라 재귀로 푼다. 남는 key 는 무시된다
 */
data class AgentMacroCheckRequest(
    val name: String,
    val source: String,
    val imports: Map<String, String>,
)

/**
 * agent-server `POST /internal/macros/check` 응답. 통과면 `{ok: true, definition, parameters, docstring}`, 거절이면
 * `{ok: false, rejection: {line, reason}}` 다.
 *
 * [definition] 은 이 서버가 읽지 않고 `developer_macro.definition_json` 에 그대로 담는 값이라 `JsonNode` 로 받는다.
 * [parameters] 는 원소가 이름 문자열이든 `{name, ...}` 객체든 받는다 — 계약 문서가 원소 모양을 정하지 않았고,
 * 이 서버가 쓰는 것은 이름과 순서뿐이다. 해석은 [AgentMacroClient] 가 경계에서 한 번 한다.
 */
data class AgentMacroCheckResponse(
    val ok: Boolean,
    val definition: JsonNode? = null,
    val parameters: JsonNode? = null,
    val docstring: String? = null,
    val rejection: AgentMacroRejection? = null,
)

/** 검사가 거절한 이유. [line] 은 원문의 1부터 세는 줄 번호이고, 줄을 특정할 수 없는 거절이면 null 이다. */
data class AgentMacroRejection(
    val line: Int? = null,
    val reason: String,
)

/** [AgentMacroClient.check] 의 결과. agent-server 응답을 경계에서 한 번 해석한 것이다. */
sealed interface MacroCheckOutcome {
    /** 통과. [definition] 은 저장용 원본 JSON, [parameterNames] 는 진입점 parameter 이름을 순서대로. */
    data class Passed(
        val definition: JsonNode,
        val parameterNames: List<String>,
        val docstring: String,
    ) : MacroCheckOutcome

    data class Rejected(val line: Int?, val reason: String) : MacroCheckOutcome
}

/**
 * `GET /internal/macros/grammar` 응답을 그대로 옮긴 것. 이 서버는 내용을 읽지 않고 중계만 하므로 본문과
 * content type 을 함께 들고 다닌다.
 */
data class MacroGrammarDocument(
    val body: String,
    val contentType: String?,
)
