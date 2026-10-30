package kr.artel.orchestration.auth.mcp

import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.security.core.context.ReactiveSecurityContextHolder
import org.springframework.security.oauth2.jwt.Jwt

/**
 * 지금 요청이 MCP access JWT 로 들어왔나. 사람이 화면에서 한 일과 개발자의 Agent 가 MCP 로 한 일을 가를 때 쓴다.
 *
 * 요청을 처리하는 코루틴 안에서만 맞다. 따로 띄운 코루틴에는 보안 맥락이 없어 false 다.
 */
suspend fun isMcpRequest(): Boolean =
    (ReactiveSecurityContextHolder.getContext().awaitSingleOrNull()?.authentication?.principal as? Jwt)?.isMcpAccess() == true
