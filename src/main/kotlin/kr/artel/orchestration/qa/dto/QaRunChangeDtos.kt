package kr.artel.orchestration.qa.dto

import java.time.Instant

/** `GET /api/qa-runs/{qaRunId}/changes` 의 응답. */
data class QaRunChangeListResponse(
    val items: List<QaRunChangeResponse>,
)

/**
 * QA run 하나가 knowledge 항목 하나나 macro 하나에 한 변경. 그 run 의 첫 event 이전 상태와 마지막
 * event 이후 상태를 한 줄로 합친 것이다.
 *
 * @property kind `KNOWLEDGE` 또는 `MACRO`.
 * @property targetId knowledge id 나 macro id. 이 API 의 다른 id 처럼 문자열이다.
 * @property name knowledge 는 `summary`(비어 있으면 `tag`), macro 는 `name`.
 * @property change `CREATE` · `UPDATE` · `DELETE`. 합친 결과로 정한다.
 * @property before line diff 의 왼쪽. CREATE 이면 null.
 * @property after line diff 의 오른쪽. DELETE 이면 null.
 * @property qaTryId 그 run 의 마지막 event 를 남긴 `qa_try`.
 * @property changedAt 그 event 의 `created_at`.
 * @property decision `APPLIED`, 또는 그 event 를 가리키는 revert event 가 있으면 `REVERTED`.
 * @property notRevertibleReason `SUPERSEDED` · `ALREADY_REVERTED` · `BEFORE_UNKNOWN` · null.
 */
data class QaRunChangeResponse(
    val kind: String,
    val targetId: String,
    val name: String,
    val change: String,
    val before: String?,
    val after: String?,
    val qaTryId: String,
    val changedAt: Instant,
    val decision: String,
    val revertible: Boolean,
    val notRevertibleReason: String?,
)
