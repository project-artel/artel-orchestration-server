package kr.artel.orchestration.auth.mcp

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import jakarta.validation.constraints.NotBlank
import jakarta.validation.constraints.Pattern
import jakarta.validation.constraints.Size
import kr.artel.orchestration.auth.cli.CREDENTIAL_CLAIM
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.http.CacheControl
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RestController
import java.time.Duration

/**
 * `POST /api/mcp/handoffs` 요청.
 *
 * @property requestId MCP 가 `/authorize` 요청을 저장하며 만든 불투명한 값. 이 서버는 해석하지 않고 handoff JWT 에
 *   그대로 싣는다. 쿼리 문자열에 실려 다니는 값이라 URL 에 그대로 쓸 수 있는 글자만 받는다
 */
data class CreateMcpHandoffRequest(
    @field:NotBlank
    @field:Size(max = 128)
    @field:Pattern(regexp = "^[A-Za-z0-9._~-]+$", message = "영문자, 숫자, `.`, `_`, `~`, `-` 만 쓸 수 있습니다.")
    val requestId: String,
)

/** @property redirectUrl 브라우저가 이동할 MCP 주소. `{MCP}/oauth/handoff?token=<handoff JWT>` 다 */
data class McpHandoffResponse(
    val redirectUrl: String,
)

/**
 * MCP 연결 허용(ARTEL-1088, 계약 §1). home 의 동의 화면이 "허용" 을 누르면 사용자 cookie 로 부른다.
 *
 * 브라우저 세션만 받는다. CLI 토큰과 MCP access JWT 는 403 이다 — MCP access JWT 는 `McpScopeWebFilter` 의 허용표
 * 밖이라 여기 닿기 전에 막히고, CLI 토큰은 여기서 [CREDENTIAL_CLAIM] 을 보고 막는다. 파라미터가 `@CurrentUserId` 가
 * 아니라 `Jwt` 인 이유가 그것이다(`CliTokenController.create` 와 같다).
 */
@Tag(name = "MCP", description = "artel-macro-mcp 로그인 넘겨주기")
@RestController
class McpHandoffController(
    private val handoffService: McpHandoffService,
    private val signingKey: McpHandoffSigningKey,
    private val sessionUserResolver: SessionUserResolver,
) {

    @Operation(
        summary = "MCP 로그인 넘겨주기",
        description = "로그인한 사용자가 MCP 연결을 허용하면 2분짜리 handoff JWT 를 서명해 MCP 로 갈 주소를 돌려준다. " +
            "브라우저 세션만 받고, CLI 토큰과 MCP access JWT 는 403 이다.",
    )
    @PostMapping("/api/mcp/handoffs")
    // 이름이 `create` 면 다른 컨트롤러의 `create` 와 겹쳐 OpenAPI operationId 에 번호가 붙고 기존 번호가 밀린다.
    fun createMcpHandoff(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateMcpHandoffRequest,
    ): McpHandoffResponse {
        // 세션 JWT 에는 cred claim 이 없다. 있으면 CLI 토큰이나 MCP access JWT 다.
        if (jwt.hasClaim(CREDENTIAL_CLAIM)) throw McpHandoffRequiresSessionException()
        val userId = sessionUserResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
        return McpHandoffResponse(redirectUrl = handoffService.issue(userId, request.requestId))
    }

    /**
     * handoff 서명 키의 공개키. MCP 가 handoff JWT 를 검증할 때 읽는다. 인증이 없다 — 공개키다.
     *
     * 5분 캐시를 허용한다. 키를 바꾸면 `kid` 가 바뀌므로, 모르는 `kid` 를 만난 MCP 가 캐시를 무시하고 다시 읽으면 된다.
     */
    @Operation(summary = "handoff 서명 공개키(JWKS)", description = "handoff JWT 를 검증할 RSA 공개키. 설정이 없으면 빈 `keys` 다.")
    @GetMapping("/.well-known/artel-handoff-jwks.json")
    fun handoffJwks(): ResponseEntity<Map<String, Any>> =
        ResponseEntity.ok()
            .cacheControl(CacheControl.maxAge(Duration.ofMinutes(5)).cachePublic())
            .body(signingKey.publicJwkSet().toJSONObject(true))
}
