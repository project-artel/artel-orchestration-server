package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesResponse
import kr.artel.orchestration.testcase.dto.TestCaseVersionListResponse
import kr.artel.orchestration.testcase.service.TestCaseVersionService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 케이스 라이브러리의 버전 보기(ARTEL-1021). 버전 목록과, 한 버전의 케이스 표(다른 버전과 비교 포함).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-case-versions")
class TestCaseVersionController(
    private val service: TestCaseVersionService
) {

    /** 버전 목록(최신 순)과 버전별 수. 맨 앞이 최신 버전(`latest`)이다. */
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
}
