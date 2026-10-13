package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * 다른 build 에 있는 **같은 케이스**의 짝(ARTEL-994). TS 를 다른 버전 TR 로 가져올 때, step 의 `case_id` 를
 * [sourceId] 에서 [targetId] 로 바꿔 끼운다. 짝은 `metadata.case_key` 로 찾는다.
 */
data class CaseTwin(
    val sourceId: Long,
    val targetId: Long,
)

/**
 * 한 케이스의 build 별 행(ARTEL-994). 같은 `case_key` 를 가진 행이 build 마다 하나씩 있고, 그 행의 상태가
 * 그 버전의 판정이다.
 */
data class CaseVerdictHistoryRow(
    val testCaseId: Long,
    val gameBuildId: Long,
    val version: String,
    val label: String?,
    val verificationStatus: String,
    val buildCreatedAt: Instant,
)

/** `GET /api/projects/{projectId}/test-cases/{testCaseId}/verdict-history` 의 한 줄. id 는 문자열이다. */
data class CaseVerdictHistoryItem(
    val testCaseId: String,
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val verificationStatus: String,
)

/** build 순서(오래된 것부터)의 판정. `case_key` 가 없는 케이스는 [items] 가 비어 있다. */
data class CaseVerdictHistoryResponse(
    val testCaseId: String,
    val items: List<CaseVerdictHistoryItem>,
)
