package kr.artel.orchestration.auth.controller

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.auth.dto.AuthUserResponse
import kr.artel.orchestration.auth.dto.LinkedIdentityResponse
import kr.artel.orchestration.auth.dto.UpdateLocaleRequest
import kr.artel.orchestration.auth.dto.UpdateProfileRequest
import kr.artel.orchestration.auth.config.AuthCookies
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.RefreshTokenService
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.auth.service.UserProfile
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ServerWebExchange

/** 홈 UI가 번역을 제공하는 언어. 새 번역이 추가될 때 함께 넓힌다. */
private val SUPPORTED_LOCALES = setOf("en", "ko")

/** BattleTag 형식. `#` 앞은 1~24자, 뒤는 숫자 1~8자다. `#`을 이름 쪽에 허용하면 구분이 모호해진다. */
private val BATTLE_TAG_PATTERN = Regex("^[^#]{1,24}#\\d{1,8}$")

private const val MAX_NICKNAME_LENGTH = 64

@RestController
@RequestMapping("/api/auth")
class AuthController(
    private val oauthUserService: OAuthUserService,
    private val sessionUserResolver: SessionUserResolver,
    private val refreshTokenService: RefreshTokenService,
    private val jwtService: JwtService,
    private val authCookies: AuthCookies,
    private val properties: AuthProperties
) {
    /**
     * refresh 쿠키로 access 쿠키를 다시 발급한다. 세션이 없는 공개 경로다. access 토큰이 만료된
     * 뒤에 부르는 것이 목적이라 인증을 걸면 애초에 쓸 수 없다.
     *
     * refresh 토큰은 회전시키지 않는다. 상태가 없어 폐기 목록을 둘 수 없으니, 회전시키면 세션이
     * 무한히 늘어나 새어나간 토큰이 영원히 살아남는다. 지금은 refresh 토큰의 수명이 세션 상한이다.
     *
     * 프로필은 매번 DB에서 읽는다. 삭제된 사용자나 지워진 신원의 토큰으로는 재발급되지 않는다.
     */
    @PostMapping("/refresh")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun refresh(exchange: ServerWebExchange) {
        val token = exchange.request.cookies.getFirst(properties.refreshCookieName)?.value
        if (token.isNullOrBlank()) throw UnauthorizedException()

        val userId = refreshTokenService.verify(token, properties.audience)
            ?: throw UnauthorizedException()
        val profile = oauthUserService.findProfile(userId) ?: throw UnauthorizedException()
        // 표시용 클레임은 가장 최근에 로그인한 제공자 신원에서 가져온다(OAuth 성공 시점과 같은 값).
        val identity = profile.identities.firstOrNull() ?: throw UnauthorizedException()

        val access = jwtService.issue(
            AuthenticatedUser(
                userId = profile.userId,
                provider = identity.provider,
                login = identity.login,
                displayName = identity.displayName,
                avatarUrl = identity.avatarUrl
            )
        )
        exchange.response.addCookie(authCookies.access(access))
    }

    @GetMapping("/me")
    // principal이 Jwt가 아니면 인자 resolver가 예외 대신 null을 넘긴다. 타입을 non-null로 두면
    // 인증 실패가 401이 아니라 NPE(500)로 새어 나온다.
    suspend fun me(@AuthenticationPrincipal jwt: Jwt?): AuthUserResponse {
        val session = jwt?.let(sessionUserResolver::resolve)
            ?: throw UnauthorizedException()
        // 서명은 유효하지만 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
        val profile = oauthUserService.findProfile(session.userId)
            ?: throw UnauthorizedException()
        return profile.toResponse()
    }

    @PutMapping("/me/locale")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun updateLocale(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateLocaleRequest
    ) {
        if (request.locale !in SUPPORTED_LOCALES) {
            throw BadRequestException("unsupported locale")
        }
        val session = sessionUserResolver.resolve(jwt)
            ?: throw UnauthorizedException()
        // me()와 같은 이유: 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
        oauthUserService.updateLocale(session.userId, request.locale)
            ?: throw UnauthorizedException()
    }

    /**
     * nickname과 battleTag를 통째로 덮어쓴다. 각 필드에 null을 보내면(또는 아예 안 보내면) 그
     * 값을 지운다 — updateLocale과 달리 부분 수정이 아니라 프로필 전체를 다시 쓰는 PUT이다.
     */
    @PutMapping("/me/profile")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun updateProfile(
        @AuthenticationPrincipal jwt: Jwt,
        @RequestBody request: UpdateProfileRequest
    ) {
        val nickname = normalizeNickname(request.nickname)
        val battleTag = normalizeBattleTag(request.battleTag)
        val session = sessionUserResolver.resolve(jwt)
            ?: throw UnauthorizedException()
        // me()와 같은 이유: 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
        oauthUserService.updateProfile(session.userId, nickname, battleTag)
            ?: throw UnauthorizedException()
    }

    /** 앞뒤 공백을 지운다. 공백만 있거나 비어 있으면 지우려는 의도로 보고 null을 돌려준다. */
    private fun normalizeNickname(nickname: String?): String? {
        val trimmed = nickname?.trim()
        if (trimmed.isNullOrEmpty()) return null
        if (trimmed.length > MAX_NICKNAME_LENGTH) {
            throw BadRequestException("닉네임은 $MAX_NICKNAME_LENGTH 자를 넘을 수 없습니다.", code = "invalid_nickname")
        }
        return trimmed
    }

    /** null은 지우는 것으로 통과시키고, null이 아닌데 형식이 안 맞으면 거절한다. */
    private fun normalizeBattleTag(battleTag: String?): String? {
        if (battleTag == null) return null
        if (!BATTLE_TAG_PATTERN.matches(battleTag)) {
            throw BadRequestException(
                "BattleTag 형식이 올바르지 않습니다. 예) Name#1234",
                code = "invalid_battle_tag"
            )
        }
        return battleTag
    }

    private fun UserProfile.toResponse() = AuthUserResponse(
        id = userId,
        displayName = displayName,
        email = email,
        locale = locale,
        nickname = nickname,
        battleTag = battleTag,
        identities = identities.map {
            LinkedIdentityResponse(
                provider = it.provider,
                login = it.login,
                displayName = it.displayName,
                avatarUrl = it.avatarUrl
            )
        }
    )
}
