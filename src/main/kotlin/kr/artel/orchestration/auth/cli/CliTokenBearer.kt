package kr.artel.orchestration.auth.cli

import org.springframework.http.HttpHeaders
import org.springframework.http.server.reactive.ServerHttpRequest

/**
 * CLI 토큰 원문의 접두사. 자격증명의 종류를 정하는 것이 이 여섯 글자다.
 *
 * `AuthProperties.cookieName` 이 `artel_access_token` 이라 접두사가 겹쳐 보이지만 충돌은 없다.
 * 쿠키는 이름으로 찾고, CLI 토큰은 `Authorization` 헤더 **값**의 접두사로 찾는다.
 */
const val CLI_TOKEN_PREFIX = "artel_"

/** principal 에 실려 자격증명 종류를 가르는 claim. 세션 JWT 에는 이 claim 이 없다. */
const val CREDENTIAL_CLAIM = "cred"

/** [CREDENTIAL_CLAIM] 이 이 값이면 그 요청의 자격증명은 CLI 토큰이다. */
const val CREDENTIAL_CLI = "cli"

/** [CREDENTIAL_CLAIM] 이 이 값이면 그 요청의 자격증명은 `artel-macro-mcp` 가 발급한 MCP access JWT 다(ARTEL-1088). */
const val CREDENTIAL_MCP = "mcp"

private const val BEARER_PREFIX = "Bearer "

/**
 * 이 요청이 CLI 토큰을 들고 왔으면 그 원문, 아니면 null.
 *
 * 접두사로 가르고 "JWT 로 디코드해 보고 실패하면 CLI 토큰으로 본다"는 방식은 쓰지 않는다. 그러면
 * 만료된 세션 JWT 도 디코드에 실패하므로 그 토큰까지 DB 조회로 흘러가 매 요청에 질의가 하나씩
 * 붙고, 401 의 원인이 "서명이 틀렸다"인지 "테이블에 없다"인지도 구분되지 않는다.
 *
 * 판단이 한 곳에만 있어야 하므로 이 함수를 두 converter 가 반대 방향으로 쓴다 — CLI 토큰 필터는
 * 값이 있을 때 받고, `SecurityConfig.cookieTokenConverter` 는 값이 있을 때 거절한다.
 */
fun ServerHttpRequest.cliTokenOrNull(): String? =
    bearerTokenOrNull()?.takeIf { it.startsWith(CLI_TOKEN_PREFIX) }

/**
 * `Authorization: Bearer` 의 값. 자격증명 종류는 가리지 않는다 — CLI 토큰과 MCP access JWT
 * (`auth.mcp.McpAccessTokenDetector`)가 각자 이 값을 보고 자기 것인지 판단한다.
 */
fun ServerHttpRequest.bearerTokenOrNull(): String? {
    val authorization = headers.getFirst(HttpHeaders.AUTHORIZATION) ?: return null
    if (!authorization.regionMatches(0, BEARER_PREFIX, 0, BEARER_PREFIX.length, ignoreCase = true)) {
        return null
    }
    return authorization.substring(BEARER_PREFIX.length).trim().ifEmpty { null }
}
