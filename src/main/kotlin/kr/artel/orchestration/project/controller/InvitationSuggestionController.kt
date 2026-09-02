package kr.artel.orchestration.project.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.dto.InvitationSuggestionResponse
import kr.artel.orchestration.project.service.ProjectInvitationService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 초대 대상 후보 REST(코루틴). 인가와 검색 규칙은 [ProjectInvitationService]가 한다.
 *
 * `/api/projects/{projectId}/invitations` 아래가 아니라 형제 경로인 것은 이 자원이 초대가 아니기
 * 때문이다. 초대 목록의 한 줄은 실제로 나간 초대이고 여기 한 줄은 아직 아무 일도 일어나지 않은
 * 후보다. 같은 경로 아래에 두면 `GET .../invitations` 가 두 가지 다른 것을 내게 된다.
 */
@Tag(name = "Project Invitation", description = "프로젝트 초대 발송·조회·취소")
@RestController
@RequestMapping("/api/projects/{projectId}/invitation-suggestions")
class InvitationSuggestionController(
    private val projectInvitationService: ProjectInvitationService
) {
    @Operation(
        summary = "초대 대상 후보",
        description = "소유자만 볼 수 있고, 아니면 404. 다듬은 검색어가 두 글자 미만이면 빈 배열이다. " +
            "`#` 이 있으면 마지막 `#` 에서 갈라 nickname 과 userTag 를 함께 정확히 맞추고, 없으면 " +
            "nickname·login·displayName 을 대소문자 무시 접두사로 찾는다. 이메일은 통째로 적었을 " +
            "때만 맞춘다. 응답에는 이메일이 실리지 않고 최대 10건이다."
    )
    @GetMapping
    suspend fun suggest(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "찾을 글자. nickname#userTag 나 이메일 전체도 된다")
        @RequestParam(defaultValue = "") query: String
    ): List<InvitationSuggestionResponse> =
        projectInvitationService.suggest(projectId, appUserId, query)
}
