package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * 한 케이스의 build 별 몫(ARTEL-1018). `test_case_version` 한 행이고, 그 상태가 그 버전의 판정이다.
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
