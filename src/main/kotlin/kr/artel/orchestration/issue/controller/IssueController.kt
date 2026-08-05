package kr.artel.orchestration.issue.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.issue.service.IssueService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 이슈 상태 전이(ARTEL-245).
 *
 * 목록은 프로젝트(`/api/projects/{id}/issues`)와 실행(`/api/qa-tries/{id}/issues`) 아래에 있지만
 * 전이는 이슈 자신의 경로에 둔다. 이슈는 둘 다에 속하므로, 어느 한쪽을 URL에 박으면 같은 전이를
 * 화면마다 다른 주소로 부르게 된다.
 *
 * 둘 다 멱등이다 — 이미 그 상태면 아무것도 바꾸지 않고 204를 준다.
 */
@RestController
@RequestMapping("/api/issues")
class IssueController(
    private val service: IssueService,
    private val userResolver: SessionUserResolver
) {
    /** 해결로 표시. 없거나 접근 불가면 404. */
    @PostMapping("/{issueId}/resolve")
    suspend fun resolve(
        @PathVariable issueId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.resolve(issueId, requireUser(jwt))
        return ResponseEntity.noContent().build()
    }

    /** 해결 표시 취소. 없거나 접근 불가면 404. */
    @PostMapping("/{issueId}/reopen")
    suspend fun reopen(
        @PathVariable issueId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.reopen(issueId, requireUser(jwt))
        return ResponseEntity.noContent().build()
    }

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
}
