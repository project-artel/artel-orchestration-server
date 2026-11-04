package kr.artel.orchestration.qa.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.qa.dto.QaVerdictTrendBucket
import kr.artel.orchestration.qa.dto.QaVerdictTrendResponse
import kr.artel.orchestration.qa.service.QaVerdictTrendService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant

/** 대시보드의 QA 판정 추이(ARTEL-1386). */
@Tag(name = "QA Stats", description = "QA 판정 집계")
@RestController
@RequestMapping("/api/projects/{projectId}/qa-verdict-trend")
class ProjectQaVerdictTrendController(
    private val service: QaVerdictTrendService
) {
    @Operation(
        summary = "QA 판정 추이",
        description = "기간 안의 Test Case 판정을 날 · 주 · 실행마다 묶어 통과 · 실패 · 회귀 · 미판정 수와 가장 많이 실패한 " +
            "Test Case 를 돌려준다. 기간은 최대 366일이고, `from` 이 `to` 보다 앞서야 한다. 참여자가 아니면 404 다."
    )
    @GetMapping
    suspend fun getQaVerdictTrend(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "기간 시작(포함)", required = true) @RequestParam from: Instant,
        @Parameter(description = "기간 끝(제외)", required = true) @RequestParam to: Instant,
        @Parameter(description = "묶는 단위") @RequestParam(defaultValue = "DAY") bucket: QaVerdictTrendBucket,
        @Parameter(description = "날 · 주를 나눌 시간대(IANA)") @RequestParam(defaultValue = "Asia/Seoul") timeZone: String,
        @Parameter(description = "이 게임 빌드의 판정만") @RequestParam(required = false) gameBuildId: Long?
    ): QaVerdictTrendResponse =
        service.trend(projectId, appUserId, from, to, bucket, timeZone, gameBuildId)
}
