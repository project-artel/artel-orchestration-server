package kr.artel.orchestration.sdk.controller

import kr.artel.orchestration.common.error.UnauthorizedException
import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** SDK 오버레이가 한 화면에 담는 만큼. 이보다 많으면 대시보드에서 정리하는 편이 빠르다. */
private const val PROJECT_LIMIT = 100

/**
 * SDK가 등록할 프로젝트를 고르기 위한 목록.
 *
 * `/api/projects`를 그대로 쓰지 않는 이유는 audience다. SDK 토큰은 수명이 30일이라
 * 브라우저 API 전체를 열어주면 새어나간 토큰 하나가 한 달짜리 대시보드 세션이 된다.
 * SDK가 실제로 필요한 것은 이름과 id 두 개뿐이라 전용 경로로 그만큼만 준다.
 */
@Tag(name = "SDK project", description = "SDK가 등록 대상으로 고를 프로젝트 목록")
@RestController
@RequestMapping("/api/sdk/projects")
class SdkProjectController(
    private val projectRepository: ProjectRepository,
    private val sessionUserResolver: SessionUserResolver
) {
    @Operation(summary = "SDK 프로젝트 목록", description = "참여 중인 프로젝트를 최근 수정순으로 조회한다.")
    @GetMapping
    suspend fun list(@AuthenticationPrincipal jwt: Jwt?): SdkProjectListResponse {
        val session = jwt?.let(sessionUserResolver::resolve)
            ?: throw UnauthorizedException()
        val projects = projectRepository
            .findPageForMember(session.userId, PROJECT_LIMIT, 0)
            .map { SdkProjectResponse(requireNotNull(it.id).toString(), it.name) }
            .toList()
        return SdkProjectListResponse(projects)
    }
}

data class SdkProjectResponse(val id: String, val name: String)

data class SdkProjectListResponse(val projects: List<SdkProjectResponse>)
