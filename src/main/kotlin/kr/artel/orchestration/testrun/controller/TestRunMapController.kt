package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testrun.dto.TestRunMapResponse
import kr.artel.orchestration.testrun.dto.TestRunMapSaveRequest
import kr.artel.orchestration.testrun.service.TestRunMapService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 테스트 런 맵의 배치 · 주석(ARTEL-1244). TS 의 스텝 · TC 는 여기서 다루지 않는다(`PUT /api/test-scenario/{id}`).
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-runs/{runId}/map")
class TestRunMapController(private val service: TestRunMapService) {

    /** 맵이 아직 없으면 `version 0` 과 빈 배치 · 주석. */
    @GetMapping
    suspend fun getTestRunMap(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @CurrentUserId appUserId: Long,
    ): TestRunMapResponse = service.get(projectId, runId, appUserId)

    /** 맵을 통째로 바꾼다. 요청의 `version` 이 지금 것과 다르면 409 `run_map_stale`. */
    @PutMapping
    suspend fun saveTestRunMap(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunMapSaveRequest,
        @CurrentUserId appUserId: Long,
    ): TestRunMapResponse = service.save(projectId, runId, appUserId, request)
}
