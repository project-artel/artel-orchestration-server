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
    /** 이 판정을 낸 STATUS 행과 그 실행(ARTEL-1134). 근거 기록이 이어지기 전의 판정은 비어 있다. */
    val verdictQaLogId: Long? = null,
    val verdictQaTryId: Long? = null,
    val verdictQaRunId: Long? = null,
    val isEditor: Boolean = false,
    val verdictCarriedFromBuildId: Long? = null,
)

/**
 * `GET /api/projects/{projectId}/test-cases/{testCaseId}/verdict-history` 의 한 줄. id 는 문자열이다.
 *
 * @property isEditor 이 버전이 editor 행인가(ARTEL-1180). 같은 version 의 build 행과 따로 있다.
 * @property verdictCarriedFromBuildId 이 판정을 앞 버전에서 이어받았으면 그 build. 이 버전에서 본 판정이면 null.
 */
data class CaseVerdictHistoryItem(
    val testCaseId: String,
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val verificationStatus: String,
    /** 이 판정의 근거로 가는 길(ARTEL-1134): 실행 · 시나리오 실행 · STATUS 행. 근거가 이어지기 전 판정은 null. */
    val qaRunId: String? = null,
    val qaTryId: String? = null,
    val verdictLogId: String? = null,
    val isEditor: Boolean = false,
    val verdictCarriedFromBuildId: String? = null,
)

/** build 순서(오래된 것부터)의 판정. `case_key` 가 없는 케이스는 [items] 가 비어 있다. */
data class CaseVerdictHistoryResponse(
    val testCaseId: String,
    val items: List<CaseVerdictHistoryItem>,
)
