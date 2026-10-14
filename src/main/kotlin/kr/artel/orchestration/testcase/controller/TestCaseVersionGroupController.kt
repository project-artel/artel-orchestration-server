package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupResponse
import kr.artel.orchestration.testcase.service.TestCaseVersionLayoutService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/** 라이브러리 버전 목록의 묶음(ARTEL-1023). 목록은 버전 목록 응답(`groups`)에 함께 온다. */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-version-groups")
class TestCaseVersionGroupController(
    private val service: TestCaseVersionLayoutService,
) {

    /** 같은 이름이 있으면 409. */
    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createTestCaseVersionGroup(
        @PathVariable projectId: Long,
        @RequestBody request: TestCaseVersionGroupRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionGroupResponse = service.createGroup(projectId, appUserId, request)

    @PutMapping("/{versionGroupId}")
    suspend fun renameTestCaseVersionGroup(
        @PathVariable projectId: Long,
        @PathVariable versionGroupId: Long,
        @RequestBody request: TestCaseVersionGroupRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionGroupResponse = service.renameGroup(projectId, appUserId, versionGroupId, request)

    /** 안의 버전은 묶음 없음으로 돌아간다. 204. */
    @DeleteMapping("/{versionGroupId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteTestCaseVersionGroup(
        @PathVariable projectId: Long,
        @PathVariable versionGroupId: Long,
        @CurrentUserId appUserId: Long
    ) = service.deleteGroup(projectId, appUserId, versionGroupId)
}
