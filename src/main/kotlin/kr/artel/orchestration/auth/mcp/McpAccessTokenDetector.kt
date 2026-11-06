package kr.artel.orchestration.auth.mcp

import com.nimbusds.jwt.JWTParser
import kr.artel.orchestration.auth.cli.CLI_TOKEN_PREFIX
import kr.artel.orchestration.auth.cli.bearerTokenOrNull
import kr.artel.orchestration.auth.config.AuthProperties
import org.springframework.http.server.reactive.ServerHttpRequest
import org.springframework.stereotype.Component

/**
 * 요청의 bearer 가 MCP access JWT 인지 가른다(ARTEL-1088).
 *
 * 가르는 기준은 **서명 전 `iss`** 다. 세션 JWT 와 SDK JWT 는 `artel.auth.issuer` 를 들고, MCP access JWT 는
 * `artel.mcp.issuer` 를 든다. 서명을 아직 보지 않은 값으로 가르지만 그 값은 어느 검증기로 보낼지만 정한다 —
 * MCP 쪽 검증기는 MCP JWKS 의 키로만 서명을 받으므로, `iss` 를 MCP 로 위조해도 MCP 키 없이는 통과하지 못한다.
 *
 * "세션 검증기로 먼저 디코드해 보고 실패하면 MCP 로" 는 쓰지 않는다. `CliTokenBearer.cliTokenOrNull` 이 접두사로
 * 가르는 것과 같은 이유다 — 만료된 세션 JWT 까지 MCP JWKS 조회로 흘러가고, 401 의 원인이 어느 검증기였는지
 * 구분되지 않는다.
 *
 * 이 판단은 두 자리가 반대 방향으로 쓴다. MCP 필터는 값이 있을 때 받고, `SecurityConfig.cookieTokenConverter` 는
 * 값이 있을 때 거절한다. MCP 가 꺼져 있으면 늘 null 이라 기존 인증 경로가 그대로다.
 */
@Component
class McpAccessTokenDetector(
    properties: McpProperties,
    authProperties: AuthProperties,
) {
    private val mcpIssuer: String? = properties.issuer.takeIf { properties.accessTokenEnabled }

    init {
        require(mcpIssuer == null || mcpIssuer != authProperties.issuer) {
            "artel.mcp.issuer 는 artel.auth.issuer 와 달라야 합니다. 같으면 MCP access JWT 와 세션 JWT 를 가를 수 없습니다."
        }
    }

    /** 이 요청이 MCP access JWT 를 들고 왔으면 그 원문, 아니면 null. */
    fun tokenOrNull(request: ServerHttpRequest): String? {
        val expectedIssuer = mcpIssuer ?: return null
        val token = request.bearerTokenOrNull() ?: return null
        if (token.startsWith(CLI_TOKEN_PREFIX)) return null
        // JWT 가 아니거나 claims 를 읽을 수 없는 값은 MCP 것이 아니다. 기존 경로로 보내 거기서 401 이 난다.
        val tokenIssuer = runCatching { JWTParser.parse(token).jwtClaimsSet?.issuer }.getOrNull()
        return token.takeIf { tokenIssuer == expectedIssuer }
    }
}
