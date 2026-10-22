package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.testcase.dto.VerdictCarryOverRequest
import kr.artel.orchestration.testcase.dto.VerdictCarryOverResponse
import kr.artel.orchestration.testcase.dto.VerdictCarryOverSkipped
import kr.artel.orchestration.testcase.dto.VerdictCarryPreviewItem
import kr.artel.orchestration.testcase.dto.VerdictCarryPreviewResponse
import kr.artel.orchestration.testcase.service.VerdictCarryOverService
import kr.artel.orchestration.testcase.dto.ContentMapTestCaseGenerationResponse
import kr.artel.orchestration.testcase.service.ContentMapTestCaseService
import kr.artel.orchestration.testcase.dto.TestCaseGenerationCandidatesResponse
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * build 의 content map 에서 test case 를 생성한다(ARTEL-971). ARTEL-864 가 요청한 `/api` 아래 재생성
 * 경로이기도 하다. 상태 코드 규칙은 [ContentMapTestCaseService.generate] 에 적었다.
 *
 * `carryOver=false` 면 앞 버전의 사용자 생성 케이스를 이어 넣지 않는다. 생성할 때마다 사용자가 고르고, 주지 않으면
 * 이어 넣는다.
 *
 * 이미 TC 를 만든 build 는 409 다. `overwrite=true` 면 그 버전의 지도 TC 를 지금 지도로 덮어쓴다(ARTEL-1041).
 *
 * `verdicts` 를 주면 생성 뒤 앞 버전 판정을 이어받는다(ARTEL-1180): `NONE` 은 남은 판정까지 다시 검증, `SAME_CONTENT` 는
 * 내용이 같은 TC 를 모두 이어받음, `SELECTED` 는 `testCaseIds` 의 TC 만. 주지 않으면 지금 규칙 그대로다.
 */
@RestController
class ContentMapTestCaseController(
    private val service: ContentMapTestCaseService,
) {

    @PostMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases")
    suspend fun generateTestCases(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @CurrentUserId appUserId: Long,
        @RequestParam(defaultValue = "true") carryOver: Boolean,
        @RequestParam(defaultValue = "false") overwrite: Boolean,
        @RequestParam(required = false) verdicts: VerdictCarryOverService.Mode?,
        @RequestParam(required = false) testCaseIds: List<String>?,
    ): ContentMapTestCaseGenerationResponse {
        val selected = ids(testCaseIds)
        if (verdicts == VerdictCarryOverService.Mode.SELECTED && selected.isEmpty()) {
            throw BadRequestException("verdicts=SELECTED 는 testCaseIds 가 필요합니다.")
        }
        return service.generate(appUserId, projectId, gameBuildId, carryOver, overwrite, verdicts, selected.toSet())
    }

    /**
     * 생성 뒤 판정을 가져온다(ARTEL-1180). `fromGameBuildId` 버전(없으면 바로 앞 버전)에서, `testCaseIds`(없으면 이 버전의
     * 모든 TC)의 판정을 가져온다. 이 버전에서 직접 판정이 난 TC, 가져올 판정이 없는 TC, 내용이 바뀐 TC 는 가져오지 않고
     * `skipped` 에 까닭과 함께 싣는다.
     */
    @PostMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/test-cases/verdict-carry-over")
    suspend fun carryVerdicts(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @CurrentUserId appUserId: Long,
        @RequestBody request: VerdictCarryOverRequest,
    ): VerdictCarryOverResponse {
        val from = request.fromGameBuildId?.let { it.trim().toLongOrNull() ?: throw BadRequestException("invalid id: $it") }
        val outcome = service.carryVerdicts(appUserId, projectId, gameBuildId, request.testCaseIds?.let(::ids), from, request.resetAll)
        return VerdictCarryOverResponse(
            carried = outcome.carried.map { it.toString() },
            skipped = outcome.skipped.map { (testCaseId, reason) -> VerdictCarryOverSkipped(testCaseId.toString(), reason.name) },
            reset = outcome.reset.map { it.toString() },
        )
    }

    /**
     * `fromGameBuildId` 버전의 판정을 이 버전에 가져오면 어떻게 되는지 미리 본다(ARTEL-1181). 아무것도 바꾸지 않는다.
     * 판정 가져오기 창의 오른쪽 표다.
     */
    @GetMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/test-cases/verdict-carry-over/preview")
    suspend fun previewVerdicts(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @CurrentUserId appUserId: Long,
        @RequestParam fromGameBuildId: Long,
    ): VerdictCarryPreviewResponse =
        VerdictCarryPreviewResponse(
            service.previewVerdicts(appUserId, projectId, gameBuildId, fromGameBuildId).map { row ->
                VerdictCarryPreviewItem(
                    testCaseId = row.source.testCaseId.toString(),
                    scene = row.source.scene,
                    step = row.source.step,
                    expectedValue = row.source.expectedValue,
                    sourceStatus = row.source.verificationStatus,
                    targetStatus = row.targetStatus,
                    carryable = row.skip == null,
                    reason = row.skip?.name,
                )
            }
        )

    private fun ids(raw: List<String?>?): List<Long> =
        raw.orEmpty().filterNotNull().map { it.trim().toLongOrNull() ?: throw BadRequestException("invalid id: $it") }

    /**
     * 도는 지도 TC 생성을 끊는다(ARTEL-1187). 끊긴 생성의 요청은 409(`TEST_CASE_GENERATION_CANCELLED`)로 끝나고 아무것도
     * 바뀌지 않는다. 도는 것이 없으면 409(`TEST_CASE_GENERATION_NOT_RUNNING`).
     */
    @DeleteMapping("/api/projects/{projectId}/game-builds/{gameBuildId}/content-map/test-cases/generation")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun cancelTestCaseGeneration(
        @PathVariable projectId: Long,
        @PathVariable gameBuildId: Long,
        @CurrentUserId appUserId: Long,
    ) = service.cancel(appUserId, projectId, gameBuildId)

    /**
     * 케이스 라이브러리의 "지도에서 TC 생성" 모달이 고를 게임 빌드(ARTEL-1040). 빌드마다 지도 상태와 이미 만들었는지를
     * 싣는다. editor 행도 있다(ARTEL-1180).
     */
    @GetMapping("/api/projects/{projectId}/test-case-versions/generation-candidates")
    suspend fun listTestCaseGenerationCandidates(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long,
    ): TestCaseGenerationCandidatesResponse = service.candidates(appUserId, projectId)
}
