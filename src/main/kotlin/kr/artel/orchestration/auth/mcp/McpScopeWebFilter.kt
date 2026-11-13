package kr.artel.orchestration.auth.mcp

import org.springframework.http.HttpMethod
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.server.ServerWebExchange
import org.springframework.web.server.WebFilter
import org.springframework.web.server.WebFilterChain
import org.springframework.web.util.pattern.PathPattern
import org.springframework.web.util.pattern.PathPatternParser
import reactor.core.publisher.Mono
import java.util.Optional

/** MCP access JWT 의 scope 값(계약 §2). */
enum class McpScope(val wire: String) {
    MACRO_READ("macro:read"),
    MACRO_WRITE("macro:write"),
    /** 글로 쓴 TC · 계약 · 용어집, TR 과 저작 대화 · 시나리오를 읽는다. */
    TESTCASE_READ("testcase:read"),
    /** TC 를 계약까지 넣거나 고친다. */
    TESTCASE_WRITE("testcase:write"),
    /** TR 을 만들고 시나리오를 저장한다. 저작 Agent 에게 맡기거나(`author_scenarios`), 쓴 것을 승인 대기로 올린다(`save_scenarios`). */
    SCENARIO_WRITE("scenario:write"),
}

/** 허용표 한 줄. [pattern] 은 `pathWithinApplication` 에 맞춘다. */
private class McpRoute(val method: HttpMethod, pattern: String, val scope: McpScope) {
    val pattern: PathPattern = PathPatternParser.defaultInstance.parse(pattern)
}

/**
 * MCP access JWT 가 열 수 있는 경로 전부(계약 §2). 이 밖은 403 이다.
 *
 * `macro:read` 는 MCP 의 `list_projects`·`list_builds`·`macro_grammar`·`list_macros`·`get_macro`·`list_cheats` 가
 * 부르는 것, `macro:write` 는 `publish_macros`·`delete_macro` 가 부르는 것이다. 검사(`:check`)는 아무것도 쓰지 않지만
 * `macro:write` 에 둔다 — 계약이 그렇게 정했다.
 *
 * `game-functions` 는 빌드가 보낸 cheat·behavior 목록이다(ARTEL-1147). 개발자 에이전트가 게임 코드에 쓴 cheat 가
 * 빌드에 들어갔는지 확인하는 데 쓴다. 읽기만 하므로 `macro:read` 다.
 *
 * `game-identities` 는 개발자 에이전트가 게임 state 의 값이 무엇을 가리키는지 선언하는 자원이다(V173). 목록은 `macro:read`,
 * 등록·삭제는 `macro:write` 다 — developer macro 와 같은 사람이 같은 연결로 쓴다.
 *
 * `game-facts`·`game-rules` 는 개발자 에이전트가 선언하는 fact 와 rule 이다(V177). 목록은 `macro:read`, 등록·삭제는
 * `macro:write` 다 — 같은 사람이 같은 연결로 macro·identity 와 함께 쓴다.
 *
 * 프로젝트 경로의 멤버 검사는 여기서 하지 않는다. 각 서비스가 `ProjectAccessService` 로 이미 한다.
 */
private val MCP_ROUTES = listOf(
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-cases/review-status-options", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-cases", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-cases/{caseId}", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/test-cases/{caseId}", McpScope.TESTCASE_WRITE),
    // 프로젝트별 TC 번호(Home 의 TC-007). MCP 는 TC 를 DB id 대신 이 번호로 보이고 받는다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-case-identifiers", McpScope.TESTCASE_READ),
    // TC 폴더 — 사용자가 원할 때 MCP 가 폴더를 만들고 TC 를 넣는다. 폴더를 지우거나 TC 를 빼는 것은 화면에서 한다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-case-folders", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-case-folders", McpScope.TESTCASE_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-case-folders/{folderId}/items", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-case-folders/{folderId}/items", McpScope.TESTCASE_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects", McpScope.MACRO_READ),
    McpRoute(HttpMethod.POST, "/api/projects", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-builds", McpScope.MACRO_READ),
    // 게임을 실행하기 전에 MCP 의 `init` 이 버전으로 빌드를 만든다(ARTEL-1447). macro·fact·rule 이 그 빌드에 붙는다.
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/game-builds", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-builds/{buildId}/game-functions", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/macros/grammar", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/developer-macros", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/developer-macros:check", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_WRITE),
    // 자연어 TC · 시나리오 저작. 저작 대화는 화면과 같은 경로를 쓴다 — 시나리오는 우리 저작 Agent 가 짜고 검사한다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/case-contracts", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/case-contracts/cases", McpScope.TESTCASE_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs/{runId}/scenarios", McpScope.TESTCASE_READ),
    // 시나리오 스텝(ARTEL-1310) — MCP 가 고칠 시나리오를 찾고, 저작 결과를 직접 읽는다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-scenario/{testScenarioId}", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/test-scenario/{testScenarioId}", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs/{runId}/chat/messages", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/chat/message", McpScope.SCENARIO_WRITE),
    // 시나리오를 짤 때 쓰는 게임 정보 — 콘솔의 저작 Agent 가 받는 것과 같다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs/{runId}/authoring-context", McpScope.TESTCASE_READ),
    // QA 실행. 개발자 에이전트가 모델 · 시나리오 · 실행 설정을 골라 QA 를 돌리고 결과를 읽는다. 시작 · 취소는 TR 의 시나리오를
    // 실행하는 일이라 `scenario:write`, 목록 · 상태 · 판정은 `testcase:read` 다. 멤버 검사와 "인스턴스 · 시나리오가 같은 프로젝트"
    // 검사는 [kr.artel.orchestration.qa.service.QaTryService] 가 한다.
    McpRoute(HttpMethod.GET, "/api/qa-models", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-instances", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/qa-runs", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/qa-runs", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.GET, "/api/qa-runs/{qaRunId}", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/qa-runs/{qaRunId}/verdicts", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/qa-runs/{qaRunId}/cancel", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.POST, "/api/qa-tries", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.GET, "/api/qa-tries/{qaTryId}", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.GET, "/api/qa-tries/{qaTryId}/verdicts", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/qa-tries/{qaTryId}/cancel", McpScope.SCENARIO_WRITE),
    // 저작 draft(ARTEL-1310) — MCP 를 쓰는 사람이 콘솔에서 draft 를 보고 저장 · 버린다. 화면과 같은 draft 다.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs/{runId}/chat/draft", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/chat/draft/apply", McpScope.SCENARIO_WRITE),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/chat/draft/discard", McpScope.SCENARIO_WRITE),
    // 에이전트가 쓴 시나리오의 저장(`save_scenarios`). 승인 대기로 들어가며 사람이 웹에서 승인한다.
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/scenarios/commit", McpScope.SCENARIO_WRITE),
    // 저작이 추천한 TC — 읽기는 testcase:read, 저장 · 버리기는 TC 를 만드는 일이라 testcase:write.
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-runs/{runId}/case-recommendations", McpScope.TESTCASE_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/case-recommendations/save", McpScope.TESTCASE_WRITE),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/test-runs/{runId}/case-recommendations/discard", McpScope.TESTCASE_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-identities", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-identities/history", McpScope.MACRO_READ),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/game-identities", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/game-identities", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-facts", McpScope.MACRO_READ),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/game-facts", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/game-facts", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/game-rules", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/game-rules", McpScope.MACRO_WRITE),
)

/** MCP access JWT 로 허용표 밖의 경로를 불렀다. */
private const val ROUTE_FORBIDDEN_CODE = "mcp_route_forbidden"
private const val ROUTE_FORBIDDEN_MESSAGE = "MCP 연결로는 이 경로를 부를 수 없습니다."

/** 허용표 안의 경로지만 token 에 그 경로의 scope 가 없다. 이름은 RFC 6750 의 `insufficient_scope` 를 따른다. */
private const val SCOPE_MISSING_CODE = "insufficient_scope"

/**
 * MCP access JWT 로 들어온 요청을 scope → 경로 허용표로 거른다(ARTEL-1088). 다른 자격증명의 요청은 건드리지 않는다.
 *
 * MCP access JWT 는 위에 명시한 API 와 scope 조합으로만 호출할 수 있다.
 * 그래서 허용표에 없는 경로는 scope 와 상관없이 403 이다 — `/api/auth` 아래 경로로 CLI 토큰을 찍어내거나 `/api/mcp/handoffs`
 * 로 다른 handoff 를 만드는 길이 여기서 막힌다.
 *
 * 컨트롤러마다 확인을 넣지 않고 필터 하나로 두는 이유는 `AccountStateWebFilter` 와 같다. 새 엔드포인트가 생겨도
 * 허용표에 더하지 않으면 MCP 로는 열리지 않는다.
 *
 * **`@Bean` 으로 내지 않는다.** `AccountStateWebFilter` 와 같은 이유다. 응답을 직접 쓰는 이유도 같다 —
 * `WebFilter` 에서 던진 예외는 `ApiExceptionHandler` 에 닿지 못한다. 본문 모양은 `ApiErrorResponse` 와 같다.
 */
class McpScopeWebFilter : WebFilter {

    override fun filter(exchange: ServerWebExchange, chain: WebFilterChain): Mono<Void> =
        ReactiveSecurityContextHolder.getContext()
            .flatMap { context ->
                Mono.justOrEmpty((context.authentication?.principal as? Jwt)?.takeIf { it.isMcpAccess() })
            }
            .map { principal -> Optional.ofNullable(refusalFor(principal, exchange)) }
            .defaultIfEmpty(Optional.empty())
            .flatMap { refusal ->
                if (refusal.isPresent) writeForbidden(exchange, refusal.get()) else chain.filter(exchange)
            }

    private fun refusalFor(principal: Jwt, exchange: ServerWebExchange): Refusal? {
        val request = exchange.request
        val path = request.path.pathWithinApplication()
        val route = MCP_ROUTES.firstOrNull { it.method == request.method && it.pattern.matches(path) }
            ?: return Refusal(ROUTE_FORBIDDEN_CODE, ROUTE_FORBIDDEN_MESSAGE)
        if (route.scope.wire !in principal.mcpScopes()) {
            return Refusal(SCOPE_MISSING_CODE, "이 요청에는 `${route.scope.wire}` scope 가 필요합니다.")
        }
        return null
    }

    private fun writeForbidden(exchange: ServerWebExchange, refusal: Refusal): Mono<Void> {
        val response = exchange.response
        response.statusCode = HttpStatus.FORBIDDEN
        response.headers.contentType = MediaType.APPLICATION_JSON
        // code 와 message 는 우리가 정한 상수라 JSON escape 가 필요한 글자가 없다.
        val body = """{"code":"${refusal.code}","message":"${refusal.message}","fields":{}}"""
        return response.writeWith(Mono.just(response.bufferFactory().wrap(body.toByteArray(Charsets.UTF_8))))
    }

    private data class Refusal(val code: String, val message: String)
}
