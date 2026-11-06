package kr.artel.orchestration.developermacro.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.developermacro.agent.AgentMacroClient
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RestController

/**
 * macro DSL 문법 글(ARTEL-1088). agent-server `GET /internal/macros/grammar` 를 중계한다.
 *
 * 글의 원본은 agent-server 의 parser 옆에 있다(`reference.GRAMMAR`). 여기에 사본을 두면 parser 가 바뀔 때 어긋나므로
 * 매번 그쪽에 묻는다. 응답은 agent-server `MacroGrammarResponse` 의 `{grammar, rules}` JSON 이고, 본문과 content
 * type 을 그대로 옮기며 내용은 읽지 않는다.
 *
 * 프로젝트에 묶이지 않은 글이라 멤버 검사가 없다. 인증은 `/api` 아래 다른 경로와 같다.
 */
@Tag(name = "Developer Macro", description = "게임 개발자가 MCP 로 검사하고 등록하는 macro")
@RestController
class MacroGrammarController(
    private val agent: AgentMacroClient,
) {

    @Operation(
        summary = "macro 문법 글",
        description = "agent-server 의 `{grammar, rules}` JSON 을 본문과 content type 그대로 중계한다. `grammar` 는 " +
            "macro DSL 문법 설명(markdown), `rules` 는 developer macro 규칙 문장 목록이다.",
    )
    @GetMapping("/api/macros/grammar")
    suspend fun grammar(): ResponseEntity<String> {
        val document = agent.grammar()
        val contentType = document.contentType?.let(MediaType::parseMediaType)
            ?: MediaType(MediaType.TEXT_PLAIN, Charsets.UTF_8)
        return ResponseEntity.ok().contentType(contentType).body(document.body)
    }
}
