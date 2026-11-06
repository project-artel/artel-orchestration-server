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

/** 승인 대기 Test Case 하나. 사람이 팝업에서 읽고 결정하는 데 필요한 것을 싣는다. */
data class CaseReviewItem(
    val id: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val kind: String,
    val updatedAt: Instant,
)
