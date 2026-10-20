package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseFolderResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesChangeResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionCopyRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionGroupAssignRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionHiddenRequest
import kr.artel.orchestration.testcase.dto.TestCaseVersionListResponse
import kr.artel.orchestration.testcase.service.ManualCaseVersionService
import kr.artel.orchestration.testcase.service.TestCaseVersionLayoutService
import kr.artel.orchestration.testcase.service.TestCaseVersionService
import org.springframework.http.HttpStatus
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
 * 케이스 라이브러리의 버전 보기(ARTEL-1021). 버전 목록과, 한 버전의 케이스 표(다른 버전과 비교 포함).
 * 버전을 묶음에 넣고 숨기고 folder 로 복제하는 정리도 여기다(ARTEL-1023).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-versions")
class TestCaseVersionController(
    private val service: TestCaseVersionService,
    private val layoutService: TestCaseVersionLayoutService,
    private val manualCaseService: ManualCaseVersionService,
) {

    /** 버전 목록(최신 순)과 버전별 수, 버전 묶음, "버전 없음" 수. 맨 앞이 최신 버전(`latest`)이다. */
    @GetMapping
    suspend fun listTestCaseVersions(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionListResponse = service.versions(projectId, appUserId)

    /**
     * [gameBuildId] 버전의 케이스 표. `compareTo` 를 주면 그 버전의 판정과 존재 여부(`BOTH` · `ONLY_THIS` ·
     * `ONLY_OTHER`)를 같은 줄에 싣는다.
     */
    @GetMapping("/{gameBuildId}/cases")
    suspend fun listTestCasesOfVersion(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestParam(required = false) compareTo: Long?,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionCasesResponse = service.cases(projectId, gameBuildId, compareTo, appUserId)

    /** "버전 없음": 어느 버전에도 몫이 없는 케이스. */
    @GetMapping("/unversioned/cases")
    suspend fun listUnversionedTestCases(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionCasesResponse = service.unversioned(projectId, appUserId)

    /** 사용자 생성 케이스를 이 버전에 넣는다(ARTEL-1031). 지도 케이스와 이미 든 케이스는 건너뛴다. */
    @PostMapping("/{gameBuildId}/cases")
    suspend fun addTestCasesToVersion(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestBody request: TestCaseVersionCasesRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionCasesChangeResponse = manualCaseService.addToVersion(projectId, appUserId, gameBuildId, request)

    /** 사용자 생성 케이스를 이 버전에서 뺀다. 본문에 id 목록을 받으므로 POST 다. */
    @PostMapping("/{gameBuildId}/cases/removals")
    suspend fun removeTestCasesFromVersion(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestBody request: TestCaseVersionCasesRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseVersionCasesChangeResponse = manualCaseService.removeFromVersion(projectId, appUserId, gameBuildId, request)

    /** 버전을 묶음에 넣거나 뺀다. 204. */
    @PutMapping("/{gameBuildId}/group")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun assignTestCaseVersionGroup(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestBody request: TestCaseVersionGroupAssignRequest,
        @CurrentUserId appUserId: Long
    ) = layoutService.assignGroup(projectId, appUserId, gameBuildId, request)

    /** 버전을 목록에서 숨기거나 다시 보인다. 204. */
    @PutMapping("/{gameBuildId}/hidden")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun setTestCaseVersionHidden(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestBody request: TestCaseVersionHiddenRequest,
        @CurrentUserId appUserId: Long
    ) = layoutService.setHidden(projectId, appUserId, gameBuildId, request)

    /** 버전의 케이스를 담은 folder 를 새로 만든다. 201. */
    @PostMapping("/{gameBuildId}/folder-copies")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun copyTestCaseVersionToFolder(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @RequestBody request: TestCaseVersionCopyRequest,
        @CurrentUserId appUserId: Long
    ): TestCaseFolderResponse = layoutService.copyToFolder(projectId, appUserId, gameBuildId, request)
}
