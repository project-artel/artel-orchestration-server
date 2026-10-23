package kr.artel.orchestration.auth.sdk

import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.common.error.BadRequestException
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.util.UriComponentsBuilder

@RestController
class SdkLoginController(private val properties: AuthProperties) {
    // 로그인 전 브라우저가 방문한다. 목적지는 요청값이 아니라 서버 설정으로만 정한다.
    @GetMapping("/api/auth/sdk/login")
    fun login(
        @RequestParam port: Int,
        @RequestParam state: String,
        @RequestParam challenge: String
    ): ResponseEntity<Void> {
        if (port !in 1..65535 || !state.matches(Regex("[A-Za-z0-9_-]{43,128}")) ||
            !challenge.matches(Regex("[A-Za-z0-9_-]{43}"))) {
            throw BadRequestException("SDK 로그인 요청을 확인해 주세요.")
        }
        val location = UriComponentsBuilder.fromUriString(properties.frontendOrigin)
            .path("/sdk-login")
            .queryParam("port", port)
            .queryParam("state", state)
            .queryParam("challenge", challenge)
            .build().encode().toUri()
        return ResponseEntity.status(302).location(location)
            .header("Cache-Control", "no-store")
            .header("Referrer-Policy", "no-referrer").build()
    }
}
