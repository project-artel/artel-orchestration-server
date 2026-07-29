package kr.artel.orchestration.game.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.game.dto.SdkRegistrationRequest
import kr.artel.orchestration.game.dto.SdkRegistrationResponse
import kr.artel.orchestration.game.service.SdkRegistrationService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException

/**
 * SDK가 게임 실행마다 부르는 등록 지점. 엔드유저 JWT가 아니라 instanceKey로 통과한다.
 */
@Tag(name = "SDK Registration", description = "SDK 인스턴스 등록 및 게임 버전 보고")
@RestController
@RequestMapping("/api/sdk/registrations")
class SdkRegistrationController(
    private val registrationService: SdkRegistrationService
) {
    /**
     * 키를 찾지 못하면 401이 아니라 404다. 호출자는 로그인할 사용자가 아니라서 인증을
     * 다시 시도할 realm이 없고, 404는 "그런 키는 없다"와 "그 인스턴스는 지워졌다"를
     * 같은 응답으로 묶어준다.
     */
    @Operation(
        summary = "SDK 인스턴스 등록",
        description = "instanceKey로 인스턴스를 확인하고, 보고된 게임 버전으로 게임 빌드를 찾거나 만든다."
    )
    @PostMapping
    suspend fun register(
        @Valid @RequestBody request: SdkRegistrationRequest
    ): SdkRegistrationResponse =
        registrationService.register(request)
            ?: throw ResponseStatusException(
                HttpStatus.NOT_FOUND,
                "등록된 게임 인스턴스를 찾을 수 없습니다."
            )
}
