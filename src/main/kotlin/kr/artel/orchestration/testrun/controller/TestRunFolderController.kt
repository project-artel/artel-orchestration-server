package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testrun.dto.TestRunFolderCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunFolderListResponse
import kr.artel.orchestration.testrun.dto.TestRunFolderRenameRequest
import kr.artel.orchestration.testrun.dto.TestRunFolderResponse
import kr.artel.orchestration.testrun.service.TestRunFolderService
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

/** 테스트 런 폴더(ARTEL-1049). 폴더는 한 빌드 버전 안에만 있다. 런을 폴더에 넣는 것은 `PUT /test-runs/{runId}/game-build`. */
@RestController
@RequestMapping("/api/projects/{projectId}/test-run-folders")
class TestRunFolderController(private val service: TestRunFolderService) {

    @GetMapping
    suspend fun listTestRunFolders(@PathVariable projectId: Long, @CurrentUserId appUserId: Long): TestRunFolderListResponse =
        service.list(projectId, appUserId)

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createTestRunFolder(
        @PathVariable projectId: Long,
        @RequestBody request: TestRunFolderCreateRequest,
        @CurrentUserId appUserId: Long,
    ): TestRunFolderResponse = service.create(projectId, appUserId, request)

    @PutMapping("/{testRunFolderId}")
    suspend fun renameTestRunFolder(
        @PathVariable projectId: Long,
        @PathVariable testRunFolderId: Long,
        @RequestBody request: TestRunFolderRenameRequest,
        @CurrentUserId appUserId: Long,
    ): TestRunFolderResponse = service.rename(projectId, appUserId, testRunFolderId, request)

    @DeleteMapping("/{testRunFolderId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteTestRunFolder(
        @PathVariable projectId: Long,
        @PathVariable testRunFolderId: Long,
        @CurrentUserId appUserId: Long,
    ) = service.delete(projectId, appUserId, testRunFolderId)
}
