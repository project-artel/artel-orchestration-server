package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import jakarta.validation.Valid
import kr.artel.orchestration.auth.service.SessionUser
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.project.dto.CreateProjectRequest
import kr.artel.orchestration.project.dto.DeleteProjectResponse
import kr.artel.orchestration.project.dto.ProjectDetailResponse
import kr.artel.orchestration.project.dto.ProjectPageResponse
import kr.artel.orchestration.project.dto.UpdateProjectRequest
import kr.artel.orchestration.project.service.ProjectService
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
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

@Tag(name = "Project", description = "프로젝트 생성·조회·수정·삭제")
@RestController
@RequestMapping("/api/projects")
class ProjectController(
    private val projectService: ProjectService,
    private val sessionUserResolver: SessionUserResolver
) {
    @Operation(summary = "프로젝트 목록", description = "참여 중인 프로젝트를 최근 수정순으로 조회한다.")
    @GetMapping
    fun list(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "0부터 시작하는 페이지 번호") @RequestParam(defaultValue = "0") page: Int,
        @Parameter(description = "페이지 크기. 최대 100") @RequestParam(defaultValue = "20") size: Int
    ): Mono<ProjectPageResponse> =
        session(jwt).flatMap { projectService.list(it.userId, page, size) }

    @Operation(summary = "프로젝트 생성", description = "만든 사람이 소유자로 등록된다.")
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    fun create(
        @AuthenticationPrincipal jwt: Jwt,
        @Valid @RequestBody request: CreateProjectRequest
    ): Mono<ProjectDetailResponse> =
        session(jwt).flatMap { projectService.create(it.userId, request) }

    @Operation(summary = "프로젝트 상세", description = "참여자가 아니면 404.")
    @GetMapping("/{projectId}")
    fun get(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): Mono<ProjectDetailResponse> =
        session(jwt)
            .flatMap { projectService.get(it.userId, projectId) }
            .switchIfEmpty(Mono.error(projectNotFound()))

    @Operation(summary = "프로젝트 수정", description = "보낸 필드만 반영한다. 설명을 지우려면 빈 문자열을 보낸다.")
    @PatchMapping("/{projectId}")
    fun update(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Valid @RequestBody request: UpdateProjectRequest
    ): Mono<ProjectDetailResponse> =
        session(jwt)
            .flatMap { projectService.update(it.userId, projectId, request) }
            .switchIfEmpty(Mono.error(projectNotFound()))

    @Operation(summary = "프로젝트 삭제", description = "소유자만 가능하다. 실제로 지우지 않고 삭제 표시만 한다.")
    @DeleteMapping("/{projectId}")
    fun delete(
        @AuthenticationPrincipal jwt: Jwt,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): Mono<DeleteProjectResponse> =
        session(jwt)
            .flatMap { projectService.delete(it.userId, projectId) }
            .switchIfEmpty(Mono.error(projectNotFound()))

    private fun session(jwt: Jwt): Mono<SessionUser> =
        Mono.justOrEmpty<SessionUser>(sessionUserResolver.resolve(jwt))
            // 서명은 유효하지만 가리키는 사용자가 없는 토큰은 유효한 세션이 아니다.
            .switchIfEmpty(Mono.error(ResponseStatusException(HttpStatus.UNAUTHORIZED)))

    /** 참여자가 아닌 프로젝트는 존재 여부조차 알리지 않는다. */
    private fun projectNotFound() =
        ResponseStatusException(HttpStatus.NOT_FOUND, "프로젝트를 찾을 수 없습니다.")
}
