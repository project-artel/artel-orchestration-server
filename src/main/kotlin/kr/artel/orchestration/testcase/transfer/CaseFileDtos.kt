package kr.artel.orchestration.testcase.transfer

/**
 * TC 내보내기 요청(ARTEL-1327).
 *
 * @property format `xlsx` 또는 `csv`.
 * @property gameBuildId 버전. `unversioned` 면 "버전 없음", 비우면 프로젝트의 TC 전부다. 결과 칸은 이 버전의 판정이다.
 * @property testCaseIds 화면에 보이는 TC(필터 반영)만 내보낼 때 그 id. 비우면 그 버전의 TC 전부다. 순서는 이 목록을 따른다.
 */
data class CaseFileExportRequest(
    val format: String = "xlsx",
    val gameBuildId: String? = null,
    val testCaseIds: List<String>? = null,
)

/** 가져올 줄 하나의 처리. */
enum class CaseImportStatus { NEW, EDIT, SAME, REJECTED }

/**
 * 가져올 줄 하나(ARTEL-1327).
 *
 * @property line 파일의 줄 번호(머리글이 1).
 * @property caseNumber 고칠 TC 의 번호(`TC-012`). 새로 만들 줄이면 null.
 * @property changes 고칠 줄에서 바뀌는 칸의 이름(`화면` · `조작` · `이 버전에 넣음` …). 화면이 그대로 보인다.
 * @property reason 못 넣는 까닭. [CaseImportStatus.REJECTED] 일 때만.
 */
data class CaseImportRowView(
    val line: Int,
    val status: CaseImportStatus,
    val testCaseId: String? = null,
    val caseNumber: String? = null,
    val kind: String = "FUNCTION",
    val scene: String,
    val step: String,
    val expectedValue: String,
    val changes: List<String> = emptyList(),
    val reason: String? = null,
)

data class CaseImportCounts(val new: Int, val edit: Int, val same: Int, val rejected: Int)

data class CaseImportPreview(
    val format: String,
    val rows: List<CaseImportRowView>,
    val counts: CaseImportCounts,
)

/**
 * 가져오기 결과. 저장하는 동안 거절된 줄(다른 사람이 그 사이 지운 TC 등)은 [rejected] 에 이유와 함께 더해진다.
 */
data class CaseImportResult(
    val created: List<String>,
    val updated: List<String>,
    val unchanged: Int,
    val rejected: List<CaseImportRowView>,
)
