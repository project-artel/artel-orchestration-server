package kr.artel.orchestration.testcase.dto

/**
 * folder 생성과 수정 요청(ARTEL-969). PUT 도 두 칸을 모두 받는다 — [parentId] 가 null 이거나 없거나 빈
 * 문자열이면 최상위로 옮긴다.
 */
data class TestCaseFolderRequest(
    val name: String? = null,
    val parentId: String? = null,
)

/** folder 한 줄. id 는 모두 문자열이고, content map folder 가 아니면 [contentMapId] 와 [gameBuildId] 가 null 이다. */
data class TestCaseFolderResponse(
    val id: String,
    val parentId: String?,
    val name: String,
    val contentMapId: String?,
    val gameBuildId: String?,
)

data class TestCaseFolderListResponse(val items: List<TestCaseFolderResponse>)

/** test case 를 folder 로 옮기는 요청. [folderId] 가 null 이거나 빈 문자열이면 folder 없음으로 옮긴다. */
data class TestCaseMoveRequest(
    val caseIds: List<String?>? = null,
    val folderId: String? = null,
)

/** 실제로 옮긴 행 수. 다른 프로젝트나 없는 id 는 세지 않는다. */
data class TestCaseMoveResponse(val moved: Int)

/** folder 목록 조회 projection. `content_map` 을 LEFT JOIN 해 `game_build_id` 를 함께 읽는다. */
data class TestCaseFolderRow(
    val id: Long,
    val parentId: Long?,
    val name: String,
    val contentMapId: Long?,
    val gameBuildId: Long?,
) {
    fun toResponse() = TestCaseFolderResponse(
        id = id.toString(),
        parentId = parentId?.toString(),
        name = name,
        contentMapId = contentMapId?.toString(),
        gameBuildId = gameBuildId?.toString(),
    )
}
