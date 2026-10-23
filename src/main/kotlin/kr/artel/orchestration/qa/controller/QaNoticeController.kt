package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.qa.dto.QaNoticeListResponse
import kr.artel.orchestration.qa.service.QaLaunchReadService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 상단바 알림함(ARTEL-1254). 내가 시작한 QA 실행이 끝난 것을 프로젝트를 가리지 않고 보인다. 읽음은 사람마다
 * 서버에 남아 다른 기기에서도 같다.
 */
@RestController
@RequestMapping("/api/qa-notices")
class QaNoticeController(
    private val service: QaLaunchReadService,
) {
    /** 최신순 [limit] 개와 읽지 않은 수. */
    @GetMapping
    suspend fun listQaNotices(
        @RequestParam(defaultValue = "10") limit: Int,
        @CurrentUserId appUserId: Long,
    ): QaNoticeListResponse =
        service.listNotices(appUserId, limit)

    /** 알림 하나를 읽음으로 두고 알림함을 다시 돌려준다. 내가 시작한 실행이 아니면 404 다. */
    @PostMapping("/{qaRunId}/read")
    suspend fun readQaNotice(
        @PathVariable qaRunId: Long,
        @CurrentUserId appUserId: Long,
    ): QaNoticeListResponse =
        service.readNotice(appUserId, qaRunId)

    /** 내 알림 전부를 읽음으로 두고 알림함을 다시 돌려준다. */
    @PostMapping("/read-all")
    suspend fun readAllQaNotices(
        @CurrentUserId appUserId: Long,
    ): QaNoticeListResponse =
        service.readAllNotices(appUserId)
}
