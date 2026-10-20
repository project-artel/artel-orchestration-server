package kr.artel.orchestration.testrun.dto

/** 테스트 런 폴더(ARTEL-1049). [gameBuildId] 가 null 이면 "버전 없음" 안의 폴더다. */
data class TestRunFolderResponse(
    val id: String,
    val gameBuildId: String?,
    val parentId: String?,
    val name: String,
)

data class TestRunFolderListResponse(val items: List<TestRunFolderResponse>)

/**
 * @property parentId 주면 그 폴더 안에 만든다. 그때 빌드 버전은 부모의 것이다.
 * @property gameBuildId 부모 없이 만들 때의 빌드 버전. null 이면 "버전 없음" 안이다.
 */
data class TestRunFolderCreateRequest(
    val name: String? = null,
    val gameBuildId: String? = null,
    val parentId: String? = null,
)

data class TestRunFolderRenameRequest(val name: String? = null)
