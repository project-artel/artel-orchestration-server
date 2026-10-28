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
 * `test-cases` 는 macro MCP 가 QA 에 필요한 케이스를 읽고 `observe` 같은 칸을 고치는 데 쓴다(ARTEL-1280). 목록·단건 조회는
 * `macro:read`, 수정(`PUT`)은 `macro:write` 다. 생성·삭제는 열지 않는다 — MCP 가 케이스를 만들거나 지울 일이 없다.
 *
 * 프로젝트 경로의 멤버 검사는 여기서 하지 않는다. 각 서비스가 `ProjectAccessService` 로 이미 한다.
 */
private val MCP_ROUTES = listOf(
    McpRoute(HttpMethod.GET, "/api/projects", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-builds", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/game-builds/{buildId}/game-functions", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/macros/grammar", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/developer-macros", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_READ),
    McpRoute(HttpMethod.POST, "/api/projects/{projectId}/developer-macros:check", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.DELETE, "/api/projects/{projectId}/developer-macros/{name}", McpScope.MACRO_WRITE),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-cases", McpScope.MACRO_READ),
    McpRoute(HttpMethod.GET, "/api/projects/{projectId}/test-cases/{caseId}", McpScope.MACRO_READ),
    McpRoute(HttpMethod.PUT, "/api/projects/{projectId}/test-cases/{caseId}", McpScope.MACRO_WRITE),
)

/** MCP access JWT 로 허용표 밖의 경로를 불렀다. */
private const val ROUTE_FORBIDDEN_CODE = "mcp_route_forbidden"
private const val ROUTE_FORBIDDEN_MESSAGE = "MCP 연결로는 이 경로를 부를 수 없습니다."

/** 허용표 안의 경로지만 token 에 그 경로의 scope 가 없다. 이름은 RFC 6750 의 `insufficient_scope` 를 따른다. */
private const val SCOPE_MISSING_CODE = "insufficient_scope"

/**
 * MCP access JWT 로 들어온 요청을 scope → 경로 허용표로 거른다(ARTEL-1088). 다른 자격증명의 요청은 건드리지 않는다.
 *
 * access JWT 가 새어도 developer macro API 와 거기 필요한 읽기 몇 개 밖으로는 못 나가게 하는 것이 목적이다.
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
