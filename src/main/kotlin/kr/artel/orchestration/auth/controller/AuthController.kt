package kr.artel.orchestration.auth.controller

import kr.artel.orchestration.auth.dto.AuthUserResponse
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/auth")
class AuthController {
    @GetMapping("/me")
    fun me(@AuthenticationPrincipal jwt: Jwt): AuthUserResponse = AuthUserResponse(
        id = jwt.subject,
        provider = jwt.getClaimAsString("provider"),
        login = jwt.getClaimAsString("login"),
        displayName = jwt.getClaimAsString("name"),
        avatarUrl = jwt.getClaimAsString("avatar_url")
    )
}
