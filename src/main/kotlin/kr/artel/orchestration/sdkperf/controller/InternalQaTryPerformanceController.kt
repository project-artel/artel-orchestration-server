package kr.artel.orchestration.sdkperf.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.sdkperf.dto.QaTryPerformanceResponse
import kr.artel.orchestration.sdkperf.service.QaTryPerformanceService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * QA agent 가 런 도중 게임의 성능 표본을 읽는 경로(ARTEL-1223).
 *
 * `/internal` 아래라 내부 포트(기본 8081)에만 있고 인증이 없다. 사람이 보는 런 상세 조회
 * (`/api/qa-runs/{runId}/performance`)는 집계 테이블을 읽지만, 이쪽은 agent 가 "방금 그 조작 뒤에 frame 이 튀었나" 를
 * 묻는 용도라 최근 표본을 그대로 준다.
 */
@Tag(name = "QA try performance (internal)", description = "QA agent 가 런 도중 읽는 SDK 성능 표본")
@RestController
@RequestMapping("/internal/qa-tries/{qaTryId}/performance")
class InternalQaTryPerformanceController(private val service: QaTryPerformanceService) {

    @Operation(
        summary = "QA try 의 최근 성능 표본 조회",
        description = "SDK 가 1초마다 보낸 표본을 시각 순으로 준다. `sinceFrame` 은 그 frame 이상, `lastSeconds` 는 마지막 " +
            "그 초만큼으로 좁힌다. 둘 다 없으면 마지막 60초다. 한 번에 600개까지 준다."
    )
    @GetMapping
    suspend fun performance(
        @PathVariable qaTryId: Long,
        @RequestParam(required = false) sinceFrame: Long?,
        @RequestParam(required = false) lastSeconds: Long?
    ): QaTryPerformanceResponse = service.performance(qaTryId, sinceFrame, lastSeconds)
}
