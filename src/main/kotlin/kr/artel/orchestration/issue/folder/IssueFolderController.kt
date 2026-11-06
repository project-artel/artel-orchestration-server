package kr.artel.orchestration.issue.folder

import kr.artel.orchestration.auth.web.CurrentUserId
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

/** 이슈 folder(ARTEL-1114). 경로 모양은 케이스 folder(`/test-case-folders`)와 같다. */
@RestController
@RequestMapping("/api/projects/{projectId}/issue-folders")
class IssueFolderController(private val service: IssueFolderService) {
    @GetMapping
    suspend fun listIssueFolders(@PathVariable projectId: Long, @CurrentUserId userId: Long) =
        service.list(projectId, userId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createIssueFolder(
        @PathVariable projectId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueFolderRequest,
    ) = service.create(projectId, userId, request)

    @PutMapping("/{folderId}")
    suspend fun updateIssueFolder(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueFolderRequest,
    ) = service.update(projectId, userId, folderId, request)

    @DeleteMapping("/{folderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteIssueFolder(@PathVariable projectId: Long, @PathVariable folderId: Long, @CurrentUserId userId: Long) =
        service.delete(projectId, userId, folderId)

    @PostMapping("/{folderId}/clones")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun cloneIssueFolder(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueFolderCloneRequest,
    ) = service.clone(projectId, userId, folderId, request)

    @PostMapping("/{folderId}/items")
    suspend fun addIssueFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueFolderItemsRequest,
    ) = service.addItems(projectId, userId, folderId, request)

    @PostMapping("/{folderId}/items/removals")
    suspend fun removeIssueFolderItems(
        @PathVariable projectId: Long,
        @PathVariable folderId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueFolderItemsRequest,
    ) = service.removeItems(projectId, userId, folderId, request)
}
