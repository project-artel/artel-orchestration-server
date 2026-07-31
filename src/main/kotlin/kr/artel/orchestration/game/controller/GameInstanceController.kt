package kr.artel.orchestration.game.controller

import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.game.dto.CreateGameInstanceRequest
import kr.artel.orchestration.game.dto.GameInstanceListResponse
import kr.artel.orchestration.game.dto.GameInstanceResponse
import kr.artel.orchestration.game.dto.UpdateGameInstanceRequest
import kr.artel.orchestration.game.service.GameInstanceService
import org.springframework.http.HttpStatus
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * 게임 인스턴스는 SDK 설치본 하나를 가리킨다.
 *
 * 생성 응답에 담긴 instanceKey를 Unity 온보딩 창에 붙여넣으면 그 실행본이 이 인스턴스로
 * 등록된다. 키는 목록에서도 계속 다시 읽을 수 있다.
 *
 * 컨트롤러는 얇게: JWT→userId 추출 + 상태코드 매핑(서비스가 null이면 404), 비즈니스는 [GameInstanceService].
 */
@Tag(name = "Game Instance", description = "프로젝트에 연결된 SDK 설치본 관리")
@RestController
@RequestMapping("/api/projects/{projectId}/game-instances")
class GameInstanceController(
    private val instanceService: GameInstanceService,
    private val sessionUserResolver: SessionUserResolver
) {
    // 생성 경로는 없다. 인스턴스는 SDK가 로그인 후 처음 등록할 때 생긴다.
    @Operation(summary = "게임 인스턴스 목록", description = "최근에 만든 것이 앞에 온다.")
    @GetMapping
    suspend fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): GameInstanceListResponse =
        instanceService.list(requireUser(jwt), projectId) ?: throw projectNotFound()

    @Operation(
        summary = "게임 인스턴스 이름 수정",
        description = "이름만 바꿀 수 있다. 플랫폼은 SDK가 이미 그 위에서 돌고 있어 바꾸면 실제와 어긋난다."
    )
    @PatchMapping("/{instanceId}")
    suspend fun rename(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long,
        @Valid @RequestBody request: UpdateGameInstanceRequest
    ): GameInstanceResponse =
        instanceService.rename(requireUser(jwt), projectId, instanceId, request) ?: throw instanceNotFound()

    @Operation(
        summary = "게임 인스턴스 삭제",
        description = "삭제한 순간부터 그 instanceKey로는 등록도 연결도 되지 않는다."
    )
    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long
    ) {
        if (!instanceService.delete(requireUser(jwt), projectId, instanceId)) throw instanceNotFound()
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()

    /** 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다. */
    private fun projectNotFound() =
        NotFoundException("프로젝트를 찾을 수 없습니다.")

    private fun instanceNotFound() =
        NotFoundException("게임 인스턴스를 찾을 수 없습니다.")
}
