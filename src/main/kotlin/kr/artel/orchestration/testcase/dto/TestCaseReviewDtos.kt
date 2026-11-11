package kr.artel.orchestration.testcase.dto

import java.time.Instant

/** 승인 대기 건에 사람이 내린 결정. */
enum class ReviewDecision { APPROVE, REJECT }

/**
 * @property expectedUpdatedAt 사람이 읽은 내용의 `updatedAt`. 그 사이 내용이 바뀌었으면 결정을 받지 않는다.
 */
data class ReviewDecisionRequest(
    val decision: ReviewDecision,
    val expectedUpdatedAt: Instant,
)

/**
 * 승인 대기 Test Case 하나. 사람이 팝업에서 읽고 결정하는 데 필요한 것을 싣는다.
 *
 * @property rejectable 거절(삭제)할 수 있는지. 승인된 것을 고친 Test Case 는 이전 내용이 남지 않고, 시나리오가 쓰는 Test Case 는
 * 참조가 끊어져 거절할 수 없다.
 */
data class CaseReviewItem(
    val id: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val kind: String,
    val updatedAt: Instant,
    val rejectable: Boolean,
)

/** 상단바 알림함의 승인 대기 한 줄. 승인하거나 거절할 때까지 남는다. */
data class ReviewNotice(
    val projectId: String,
    val projectName: String,
    val testCaseCount: Long,
    val scenarioCount: Long,
)

/** `GET /api/review-notices`. */
data class ReviewNoticeListResponse(val items: List<ReviewNotice>)
