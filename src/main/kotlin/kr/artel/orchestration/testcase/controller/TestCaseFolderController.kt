package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseFolderListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.service.TestCaseFolderService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** test case folder REST(ARTEL-969). 상태 코드 규칙은 [TestCaseFolderService] 에 적었다. */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-folders")
class TestCaseFolderController(
    private val service: TestCaseFolderService
) {

    @GetMapping
    suspend fun listFolders(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderListResponse =
        service.list(projectId, appUserId)

    /** `@ResponseStatus` 로 적어 OpenAPI 문서에도 201 이 나오게 한다. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createFolder(
        @PathVariable projectId: Long,
        @RequestBody request: TestCaseFolderRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderResponse =
        service.create(projectId, appUserId, request)

    /** 이름과 부모를 함께 바꾼다. `parentId` 가 없으면 최상위로 옮긴다. */
    @PutMapping("/{folderId}")
    suspend fun updateFolder(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderResponse =
        service.update(projectId, appUserId, folderId, request)

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteFolder(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @CurrentUserId appUserId: Long
    ) {
        service.delete(projectId, appUserId, folderId)
    }
}
