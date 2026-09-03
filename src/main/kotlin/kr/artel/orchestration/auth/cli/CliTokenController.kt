package kr.artel.orchestration.auth.cli

import jakarta.validation.Valid
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.UnauthorizedException
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * CLI 토큰의 발급·목록·폐기.
 *
 * `AuthController` 에 얹지 않는다. 그 클래스는 이미 의존 일곱을 들고 있고, CLI 토큰은 자기
 * 서비스와 자기 DTO 를 가진 별개 리소스다. 같은 `/api/auth` 아래 컨트롤러가 둘이어도 경로가
 * 겹치지 않는다.
 *
 * 세 경로 모두 `/api/auth` 아래라 `SecurityConfig` 의 `.authenticated()` 가 이미 덮는다. 파라미터가
 * `@CurrentUserId` 가 아니라 `Jwt` 인 이유는 [create] 가 자격증명 **종류**를 봐야 하기 때문이다.
 */
@RestController
@RequestMapping("/api/auth/cli-tokens")
class CliTokenController(
    private val cliTokenService: CliTokenService,
    private val sessionUserResolver: SessionUserResolver
) {
    /**
     * 새 토큰을 발급한다. 원문은 이 201 응답에만 실린다.
     *
     * CLI 토큰으로는 부를 수 없다(403). CLI 토큰이 CLI 토큰을 찍어낼 수 있으면 폐기가 의미를
     * 잃는다 — 새어나간 토큰 하나로 새 토큰을 만들어 두면 원본을 폐기해도 접근이 남는다.
     */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateCliTokenRequest
    ): CreatedCliTokenResponse {
        if (jwt.getClaimAsString(CREDENTIAL_CLAIM) == CREDENTIAL_CLI) {
            throw CliTokenCannotIssueException()
        }
        val issued = cliTokenService.issue(userIdOf(jwt), request.name, request.expiresInDays)
        return CreatedCliTokenResponse(
            id = requireNotNull(issued.row.id).toString(),
            name = issued.row.name,
            token = issued.token,
            createdAt = issued.row.createdAt,
            expiresAt = issued.row.expiresAt
        )
    }

    /**
     * 자기 토큰 전부. 폐기된 것도 낸다.
     *
     * CLI 토큰으로도 열어 둔다. 노트북 토큰이 샌 것을 알아챈 사람이 손에 쥔 것이 CLI 뿐일 수 있고,
     * 목록은 원문을 내지 않으므로 잃을 것이 없다.
     */
    @GetMapping
    suspend fun list(@AuthenticationPrincipal jwt: Jwt): List<CliTokenResponse> =
        cliTokenService.list(userIdOf(jwt)).map { it.toResponse() }.toList()

    /**
     * 토큰을 폐기한다. 다음 요청부터 그 토큰은 401 이다.
     *
     * 목록과 같은 이유로 CLI 토큰으로도 열어 둔다 — 폐기를 막으면 CLI 밖에 없는 사람이 새어나간
     * 토큰을 회수할 길이 없다.
     *
     * 없는 id, 남의 토큰, 이미 폐기한 토큰은 모두 404 다. idempotent 하지 않다.
     */
    @DeleteMapping("/{tokenId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun revoke(@AuthenticationPrincipal jwt: Jwt, @PathVariable tokenId: Long) {
        cliTokenService.revoke(userIdOf(jwt), tokenId)
    }

    private fun userIdOf(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
}

/** CLI 토큰으로 새 CLI 토큰을 발급하려 할 때. 세션으로 로그인해야 한다. */
class CliTokenCannotIssueException :
    ForbiddenException(
        "CLI 토큰으로는 새 토큰을 발급할 수 없습니다. 로그인한 브라우저에서 발급하세요.",
        code = "cli_token_cannot_issue"
    )
