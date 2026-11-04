package kr.artel.orchestration.release.controller

import io.swagger.v3.oas.annotations.Operation
import io.swagger.v3.oas.annotations.Parameter
import io.swagger.v3.oas.annotations.tags.Tag
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.release.dto.BuildVerificationProgressResponse
import kr.artel.orchestration.release.dto.BuildVerificationSummariesResponse
import kr.artel.orchestration.release.dto.ReleaseCriteria
import kr.artel.orchestration.release.dto.ReleaseCriteriaResponse
import kr.artel.orchestration.release.service.BuildVerificationService
import kr.artel.orchestration.release.service.ReleaseCriteriaService
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** 대시보드의 이번 빌드 검증 진행과 출시 기준(ARTEL-1386). */
@Tag(name = "Release", description = "빌드 검증 진행과 출시 기준")
@RestController
@RequestMapping("/api/projects/{projectId}")
class ProjectReleaseController(
    private val buildVerificationService: BuildVerificationService,
    private val releaseCriteriaService: ReleaseCriteriaService
) {
    @Operation(
        summary = "빌드 검증 진행",
        description = "그 빌드의 TC 를 마지막 판정으로 센 상태(통과 · 실패 · 회귀 · 판정 못함 · 판정 전)와, 빌드가 올라온 날부터 " +
            "오늘까지(최대 90일) 날마다의 누적 상태 · QA 실행 수를 돌려준다. 다른 프로젝트의 빌드거나 참여자가 아니면 404 다."
    )
    @GetMapping("/game-builds/{gameBuildId}/verification-progress")
    suspend fun getBuildVerificationProgress(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "게임 빌드 id", required = true) @PathVariable gameBuildId: Long,
        @Parameter(description = "날을 나눌 시간대(IANA)") @RequestParam(defaultValue = "Asia/Seoul") timeZone: String
    ): BuildVerificationProgressResponse =
        buildVerificationService.progress(projectId, appUserId, gameBuildId, timeZone)

    @Operation(
        summary = "빌드별 검증 요약",
        description = "케이스 라이브러리에 보이는 빌드마다 TC 상태 수와 안 닫힌 BLOCKER · CRITICAL 이슈 수를, 최근에 올라온 것부터 돌려준다."
    )
    @GetMapping("/build-verification-summaries")
    suspend fun listBuildVerificationSummaries(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @Parameter(description = "몇 개(1~20)") @RequestParam(defaultValue = "5") limit: Int
    ): BuildVerificationSummariesResponse =
        buildVerificationService.summaries(projectId, appUserId, limit)

    @Operation(summary = "출시 기준", description = "프로젝트의 출시 기준 숫자. 정한 적이 없으면 기본값이다.")
    @GetMapping("/release-criteria")
    suspend fun getReleaseCriteria(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): ReleaseCriteriaResponse =
        releaseCriteriaService.get(projectId, appUserId)

    @Operation(summary = "출시 기준 바꾸기", description = "OWNER 만 바꾼다. 건수는 0 이상, 비율은 0~100 이다.")
    @PutMapping("/release-criteria")
    suspend fun updateReleaseCriteria(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long,
        @RequestBody criteria: ReleaseCriteria
    ): ReleaseCriteriaResponse =
        releaseCriteriaService.update(projectId, appUserId, criteria)

    @Operation(summary = "출시 기준을 기본값으로", description = "OWNER 만 되돌린다.")
    @DeleteMapping("/release-criteria")
    suspend fun resetReleaseCriteria(
        @CurrentUserId appUserId: Long,
        @Parameter(description = "프로젝트 id", required = true) @PathVariable projectId: Long
    ): ReleaseCriteriaResponse =
        releaseCriteriaService.reset(projectId, appUserId)
}
