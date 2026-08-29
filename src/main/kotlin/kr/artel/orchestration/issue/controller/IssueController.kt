package kr.artel.orchestration.issue.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.tracker.dto.IssueTrackerEnvelope
import kr.artel.orchestration.tracker.service.IssueTrackerSyncService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
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
    private val trackerSync: IssueTrackerSyncService
) {
    /** 해결로 표시. 없거나 접근 불가면 404. */
    @PostMapping("/{issueId}/resolve")
    suspend fun resolve(
        @PathVariable issueId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.resolve(issueId, appUserId)
        return ResponseEntity.noContent().build()
    }

    /** 해결 표시 취소. 없거나 접근 불가면 404. */
    @PostMapping("/{issueId}/reopen")
    suspend fun reopen(
        @PathVariable issueId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.reopen(issueId, appUserId)
        return ResponseEntity.noContent().build()
    }

    /**
     * 사람이 누르는 외부 tracker 내보내기와 재시도(ARTEL-671).
     *
     * 202 인 이유는 "받아서 처리했다"가 아니라 **저쪽의 상태를 실어 보낸다**는 뜻이기 때문이다.
     * 이미 나간 결함이면 그 상태가 그대로 오고(멱등), 실패했으면 `syncState="FAILED"` 와 `syncError`
     * 가 온다. 연결이 없으면 400 이다 — 조용한 202 는 화면이 "눌렀는데 아무 일도 없다"로 읽는다.
     */
    @PostMapping("/{issueId}/tracker-sync")
    suspend fun trackerSync(
        @PathVariable issueId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<IssueTrackerEnvelope> =
        ResponseEntity.status(HttpStatus.ACCEPTED)
            .body(IssueTrackerEnvelope(trackerSync.syncManually(issueId, appUserId)))
}
