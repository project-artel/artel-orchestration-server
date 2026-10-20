package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testrun.dto.ScenarioLibraryResponse
import kr.artel.orchestration.testrun.service.ScenarioLibraryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

/**
 * TS 불러오기 모달의 트리(ARTEL-994). 게임 버전 → TR → TS. 가져오기 자체는
 * `POST /api/projects/{projectId}/test-runs/{runId}/scenarios/imports` 다.
 */
@RestController
class ScenarioLibraryController(
    private val libraryService: ScenarioLibraryService
) {

    @GetMapping("/api/projects/{projectId}/test-scenario-library")
    suspend fun library(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): ScenarioLibraryResponse = libraryService.library(projectId, appUserId)
}
