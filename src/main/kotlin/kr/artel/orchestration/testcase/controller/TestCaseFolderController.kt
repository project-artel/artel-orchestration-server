package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseFolderCloneRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemHiddenRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemMoveRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemOrderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemsChangeResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderItemsRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderListResponse
import kr.artel.orchestration.testcase.dto.TestCaseFolderRequest
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.service.TestCaseFolderItemService
import kr.artel.orchestration.testcase.service.TestCaseFolderService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * test case folder REST(ARTEL-969)와 folder 항목(ARTEL-1023). 상태 코드 규칙은 [TestCaseFolderService] 와
 * [TestCaseFolderItemService] 에 적었다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-folders")
class TestCaseFolderController(
    private val service: TestCaseFolderService,
    private val itemService: TestCaseFolderItemService,
) {

    /** `gameBuildId` 를 주면 folder 마다 그 버전에서 보이는 항목 수(`visibleCount`)를 함께 센다. */
    @GetMapping
    suspend fun listFolders(
        @PathVariable projectId: Long,
        @RequestParam(required = false) gameBuildId: Long?,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderListResponse =
        service.list(projectId, appUserId, gameBuildId)

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

    /** 하위 folder 와 항목(순서 · 숨김 포함)째 복제한다. */
    @PostMapping("/{folderId}/clones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun cloneFolder(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderCloneRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderResponse =
        service.clone(projectId, appUserId, folderId, request)

    /** 항목을 `gameBuildId` 버전으로 푼 목록. 버전을 주지 않으면 가장 최근 build 다. */
    @GetMapping("/{folderId}/items")
    suspend fun listFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestParam(required = false) gameBuildId: Long?,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemListResponse =
        itemService.list(projectId, appUserId, folderId, gameBuildId)

    @PostMapping("/{folderId}/items")
    suspend fun addFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderItemsRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemsChangeResponse =
        itemService.add(projectId, appUserId, folderId, request)

    /** 본문에 id 목록을 받으므로 DELETE 대신 POST 다(`/test-cases/bulk-delete` 와 같은 이유). */
    @PostMapping("/{folderId}/items/removals")
    suspend fun removeFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderItemsRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemsChangeResponse =
        itemService.remove(projectId, appUserId, folderId, request)

    @PostMapping("/{folderId}/items/moves")
    suspend fun moveFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderItemMoveRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemsChangeResponse =
        itemService.move(projectId, appUserId, folderId, request)

    @PutMapping("/{folderId}/items/order")
    suspend fun reorderFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderItemOrderRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemsChangeResponse =
        itemService.reorder(projectId, appUserId, folderId, request)

    @PutMapping("/{folderId}/items/hidden")
    suspend fun setFolderItemsHidden(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @RequestBody request: TestCaseFolderItemHiddenRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderItemsChangeResponse =
        itemService.setHidden(projectId, appUserId, folderId, request)
}
