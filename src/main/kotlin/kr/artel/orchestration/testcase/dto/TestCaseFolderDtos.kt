package kr.artel.orchestration.testcase.dto

/**
 * folder 생성과 수정 요청(ARTEL-969). PUT 도 두 칸을 모두 받는다 — [parentId] 가 null 이거나 없거나 빈
 * 문자열이면 최상위로 옮긴다.
 */
data class TestCaseFolderRequest(
    val name: String? = null,
    val parentId: String? = null,
)

/**
 * folder 한 줄. id 는 모두 문자열이다.
 *
 * @property caseCount 이 folder 에 바로 담긴 케이스 수(하위 folder 제외, 숨긴 것 포함).
 * @property visibleCount 목록을 [gameBuildId 로][kr.artel.orchestration.testcase.controller.TestCaseFolderController.listFolders]
 *   물었을 때 그 버전에서 보이는 항목 수. 버전을 묻지 않았으면 null 이다.
 */
data class TestCaseFolderResponse(
    val id: String,
    val parentId: String?,
    val name: String,
    val caseCount: Int,
    val visibleCount: Int?,
)

data class TestCaseFolderListResponse(val items: List<TestCaseFolderResponse>)

/**
 * test case 를 folder 로 옮기는 요청. [folderId] 가 null 이거나 빈 문자열이면 folder 없음으로 옮긴다.
 *
 * 케이스가 folder 하나에만 들던 때의 요청이다(ARTEL-969). 이제 케이스는 여러 folder 에 들 수 있어, 이 요청은
 * 케이스를 **모든 folder 에서 빼고** [folderId] 에 넣는다. folder 끼리 옮기기는 folder 항목 API 를 쓴다(ARTEL-1023).
 */
data class TestCaseMoveRequest(
    val caseIds: List<String?>? = null,
    val folderId: String? = null,
)

/** 실제로 옮긴 행 수. 다른 프로젝트나 없는 id 는 세지 않는다. */
data class TestCaseMoveResponse(val moved: Int)

/** folder 목록 조회 projection. 항목 수를 함께 센다. */
data class TestCaseFolderRow(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val caseCount: Long,
    val visibleCount: Long?,
) {
    fun toResponse() = TestCaseFolderResponse(
        id = id.toString(),
        parentId = parentId?.toString(),
        name = name,
        caseCount = caseCount.toInt(),
        visibleCount = visibleCount?.toInt(),
    )
}

/** folder 에 케이스를 넣거나 빼는 요청(ARTEL-1023). */
data class TestCaseFolderItemsRequest(
    val caseIds: List<String?>? = null,
)

/**
 * folder 의 항목을 다른 folder 로 옮기는 요청(ARTEL-1023). 옮긴 항목은 [targetFolderId] 의 끝에 붙는다.
 * 대상에 이미 있는 케이스는 대상 쪽 항목을 남기고 이쪽 항목만 지운다.
 */
data class TestCaseFolderItemMoveRequest(
    val caseIds: List<String?>? = null,
    val targetFolderId: String? = null,
)

/** folder 안 순서를 통째로 다시 정하는 요청. [caseIds] 는 그 folder 의 항목 전부여야 한다. */
data class TestCaseFolderItemOrderRequest(
    val caseIds: List<String?>? = null,
)

/** 버전별 숨김 요청(ARTEL-1023). [hidden] 이 true 면 [gameBuildId] 버전에서 숨기고, false 면 다시 보인다. */
data class TestCaseFolderItemHiddenRequest(
    val caseIds: List<String?>? = null,
    val gameBuildId: String? = null,
    val hidden: Boolean? = null,
)

/** 항목 쓰기의 결과. [changed] 는 실제로 바뀐 항목 수이고, 이미 그 상태였거나 다른 프로젝트 케이스는 세지 않는다. */
data class TestCaseFolderItemsChangeResponse(val changed: Int)

/**
 * folder 복제 요청(ARTEL-1023). 하위 folder 와 항목(숨김 포함)을 함께 복제한다.
 *
 * @property parentId 복제본을 둘 부모. null 이거나 없으면 원본과 같은 부모다. 최상위로 두려면 [toRoot] 를 쓴다.
 * @property name 복제본 이름. 없으면 `"<원본> 사본"` 이고, 겹치면 ` (2)`, ` (3)` … 을 붙인다.
 */
data class TestCaseFolderCloneRequest(
    val parentId: String? = null,
    val toRoot: Boolean? = null,
    val name: String? = null,
)

/**
 * folder 항목 한 줄을 [gameBuildId 버전][TestCaseFolderItemListResponse.gameBuildId] 으로 푼 것(ARTEL-1023).
 *
 * @property inVersion 그 버전에 있는 케이스인가. 지도 케이스는 버전 몫이 있으면, 사람이 쓴 케이스는 늘 있다.
 *   없으면 내용은 가장 최근 버전의 것이고 [verificationStatus] 는 null 이다.
 * @property hidden 이 folder 에서 그 버전에 숨겼나.
 * @property origin `MAP` 또는 `MANUAL`.
 */
data class TestCaseFolderItemResponse(
    val testCaseId: String,
    val position: Int,
    val hidden: Boolean,
    val inVersion: Boolean,
    val origin: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val verificationStatus: String?,
    val createdAt: java.time.Instant,
)

/** @property gameBuildId 항목을 푼 버전. 프로젝트에 build 가 없으면 null 이고, 항목은 케이스의 현재 내용이다. */
data class TestCaseFolderItemListResponse(
    val folderId: String,
    val gameBuildId: String?,
    val items: List<TestCaseFolderItemResponse>,
)

/** [kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository.findRows] 의 한 줄. */
data class TestCaseFolderItemRow(
    val testCaseId: Long,
    val position: Int,
    val hidden: Boolean,
)

/** 케이스가 처음 담긴 folder. 케이스 응답의 `folderId` 를 채운다. */
data class CaseFirstFolder(
    val testCaseId: Long,
    val folderId: Long,
)
