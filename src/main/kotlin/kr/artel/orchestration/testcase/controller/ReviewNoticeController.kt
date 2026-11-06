package kr.artel.orchestration.testcase.controller

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.dto.ReviewNotice
import kr.artel.orchestration.testcase.dto.ReviewNoticeListResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 상단바 알림함의 승인 대기. 내가 승인을 요청한 Test Case 와 시나리오를 프로젝트를 가리지 않고 센다.
 * 프로젝트에 들어가기 전에도 대기 건을 알 수 있게 한다.
 *
 * 사람의 웹 세션만 쓰는 경로라 MCP 허용표(`McpScopeWebFilter`)에 넣지 않는다.
 */
@RestController
@RequestMapping("/api/review-notices")
class ReviewNoticeController(
    private val projects: ProjectRepository,
) {
    @GetMapping
    suspend fun listReviewNotices(@CurrentUserId appUserId: Long): ReviewNoticeListResponse =
        ReviewNoticeListResponse(
            projects.findReviewNotices(appUserId)
                .map { ReviewNotice(it.projectId.toString(), it.projectName, it.testCaseCount, it.scenarioCount) }
                .toList(),
        )
}
