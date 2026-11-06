package kr.artel.orchestration.game.controller

import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.dto.SdkRegistrationRequest
import kr.artel.orchestration.game.dto.SdkRegistrationResponse
import kr.artel.orchestration.game.service.SdkRegistrationService
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * SDK가 게임 실행마다 부르는 등록 지점. 로그인해서 받은 SDK 토큰으로 통과한다.
 */
@Tag(name = "SDK Registration", description = "SDK 인스턴스 등록 및 게임 버전 보고")
@RestController
@RequestMapping("/api/sdk/registrations")
class SdkRegistrationController(
    private val registrationService: SdkRegistrationService,
    private val sessionUserResolver: SessionUserResolver
) {
    /**
     * 프로젝트를 찾지 못하거나 참여자가 아니면 404다. 둘을 구분해서 알려주면 프로젝트 id를
     * 훑어 남의 프로젝트가 존재한다는 사실을 알아낼 수 있다.
     *
     * principal을 non-null로 두면 인증 실패가 401이 아니라 NPE(500)로 새어 나간다.
     */
    @Operation(
        summary = "SDK 인스턴스 등록",
        description = "sdkUuid로 인스턴스를 찾거나 만들고, 보고된 게임 버전으로 게임 빌드를 찾거나 만든다."
    )
    @PostMapping
    suspend fun register(
        @AuthenticationPrincipal jwt: Jwt?,
        @Valid @RequestBody request: SdkRegistrationRequest
    ): SdkRegistrationResponse {
        val session = jwt?.let(sessionUserResolver::resolve) ?: throw UnauthorizedException()
        return registrationService.register(request, session.userId)
            ?: throw NotFoundException("등록할 수 있는 프로젝트를 찾을 수 없습니다.")
    }
}
