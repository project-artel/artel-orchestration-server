package kr.artel.orchestration.game.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.SessionUser
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
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * 게임 인스턴스는 SDK 설치본 하나를 가리킨다.
 *
 * 생성 응답에 담긴 instanceKey를 Unity 온보딩 창에 붙여넣으면 그 실행본이 이 인스턴스로
 * 등록된다. 키는 목록에서도 계속 다시 읽을 수 있다.
 */
@Tag(name = "Game Instance", description = "프로젝트에 연결된 SDK 설치본 관리")
@RestController
@RequestMapping("/api/projects/{projectId}/game-instances")
class GameInstanceController(
    private val instanceService: GameInstanceService,
    private val sessionUserResolver: SessionUserResolver
) {
    @Operation(
        summary = "게임 인스턴스 생성",
        description = "영구 자격증명인 instanceKey를 함께 발급한다. 지금은 UNITY만 받는다."
    )
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: CreateGameInstanceRequest
    ): Mono<GameInstanceResponse> =
        session(jwt)
            .flatMap { instanceService.create(it.userId, projectId, request) }
            .switchIfEmpty(Mono.error(projectNotFound()))

    @Operation(summary = "게임 인스턴스 목록", description = "최근에 만든 것이 앞에 온다.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): Mono<GameInstanceListResponse> =
        session(jwt)
            .flatMap { instanceService.list(it.userId, projectId) }
            .switchIfEmpty(Mono.error(projectNotFound()))

    @Operation(
        summary = "게임 인스턴스 이름 수정",
        description = "이름만 바꿀 수 있다. instanceKey를 바꾸면 설치된 SDK가 전부 연결을 잃는다."
    )
    @PatchMapping("/{instanceId}")
    fun rename(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long,
        @Valid @RequestBody request: UpdateGameInstanceRequest
    ): Mono<GameInstanceResponse> =
        session(jwt)
            .flatMap { instanceService.rename(it.userId, projectId, instanceId, request) }
            .switchIfEmpty(Mono.error(instanceNotFound()))

    @Operation(
        summary = "게임 인스턴스 삭제",
        description = "삭제한 순간부터 그 instanceKey로는 등록도 연결도 되지 않는다."
    )
    @DeleteMapping("/{instanceId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 인스턴스 id", required = true) @PathVariable instanceId: Long
    ): Mono<Void> =
        session(jwt)
            .flatMap { instanceService.delete(it.userId, projectId, instanceId) }
            .switchIfEmpty(Mono.error(instanceNotFound()))
            .then()

    private fun session(jwt: Jwt): Mono<SessionUser> =
        Mono.justOrEmpty<SessionUser>(sessionUserResolver.resolve(jwt))
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))

    /** 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다. */
    private fun projectNotFound() =
        ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.")

    private fun instanceNotFound() =
        ResponseStatusException(HttpStatus.NOT_FOUND, "게임 인스턴스를 찾을 수 없습니다.")
}
