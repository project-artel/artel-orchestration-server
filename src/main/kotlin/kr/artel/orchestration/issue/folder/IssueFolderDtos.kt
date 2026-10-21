package kr.artel.orchestration.issue.folder

/** folder 를 만들거나 고치는 요청. [parentId] 가 null 이면 최상위다. id 는 문자열이다. */
data class IssueFolderRequest(
    val name: String? = null,
    val parentId: String? = null,
)

/** folder 복제 요청. 모양은 케이스 folder 복제(`TestCaseFolderCloneRequest`)와 같다. */
data class IssueFolderCloneRequest(
    val name: String? = null,
    val parentId: String? = null,
    val toRoot: Boolean? = null,
)

/** folder 에 이슈를 담거나 빼는 요청. */
data class IssueFolderItemsRequest(val issueIds: List<String?>? = null)

/**
 * folder 한 줄.
 *
 * @property issueCount 이 folder 에 바로 담긴 이슈 수(하위 folder 제외).
 */
data class IssueFolderResponse(
    val id: String,
    val parentId: String?,
    val name: String,
    val issueCount: Int,
)

data class IssueFolderListResponse(val items: List<IssueFolderResponse>)
