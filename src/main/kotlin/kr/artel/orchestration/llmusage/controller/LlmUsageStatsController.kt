package kr.artel.orchestration.llmusage.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmusage.dto.LlmUsageStatsResponse
import kr.artel.orchestration.llmusage.dto.QaRunUsageResponse
import kr.artel.orchestration.llmusage.service.LlmUsageStatsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * LLM 지출 조회(ARTEL-233 후속).
 *
 * 수집 엔드포인트([LlmUsageController])와 같은 테이블을 읽지만 경계가 다르다 — 저쪽은 agent 전용
 * 무인증 `/internal`이고, 이쪽은 로그인 사용자의 `/api`다. 한 컨트롤러에 두면 그 차이가 메서드
 * 애너테이션에만 남아, `/internal`을 통째로 permitAll로 여는 `SecurityConfig` 아래에서 조회가
 * 조용히 무인증이 된다.
 *
 * 응답은 항상 사용자가 참여한 프로젝트로 좁혀진다. 관리자 role이 없으므로 "배포 전체 지출"을
 * 내주는 경로는 여기 없다.
 */
@RestController
@RequestMapping("/api/llm-usage")
class LlmUsageStatsController(
    private val service: LlmUsageStatsService
) {
    /**
     * 기간 지출을 service·model·project·일자 네 축으로 접어 돌려준다.
     *
     * @param projectId 생략하면 참여 중인 전 프로젝트 합산.
     * @param from,to ISO-8601 instant(`2026-08-01T00:00:00Z`). 생략하면 최근 30일. 기준 컬럼은
     *   `called_at`이다.
     * @param zone 일별 버킷을 자를 IANA 시간대(`Asia/Seoul`). 생략하면 UTC — 이 값에 따라 월 경계의
     *   하루가 어느 달에 붙는지가 바뀐다.
     */
    @GetMapping("/stats")
    suspend fun stats(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(required = false) zone: String?,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<LlmUsageStatsResponse> =
        ResponseEntity.ok(
            service.stats(
                userId = appUserId,
                projectId = projectId?.let { parseId(it) },
                from = parseInstant(from, "from"),
                to = parseInstant(to, "to"),
                zone = zone
            )
        )

    /**
     * QA 런 한 건씩의 토큰과 비용, 최신순.
     *
     * 기간 기준이 [stats]와 다르다 — 여기는 `qa_try.started_at`이다. 런에 귀속시키는 목록이라 그
     * 런이 시작된 구간에 들어간다.
     */
    @GetMapping("/qa-runs")
    suspend fun qaRuns(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<List<QaRunUsageResponse>> =
        ResponseEntity.ok(
            service.qaRuns(
                userId = appUserId,
                projectId = projectId?.let { parseId(it) },
                from = parseInstant(from, "from"),
                to = parseInstant(to, "to"),
                size = size
            )
        )

    /** 런 하나의 지출. 실행 화면이 그 런을 열어 둔 채로 부른다. */
    @GetMapping("/qa-runs/{qaTryId}")
    suspend fun qaRun(
        @PathVariable qaTryId: String,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaRunUsageResponse> =
        service.qaRun(appUserId, parseId(qaTryId))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw BadRequestException("ID must be a signed 64-bit decimal string")

    /**
     * 프레임워크 변환에 맡기지 않고 직접 읽는다. 맡기면 오타 하나가 이 컨트롤러의 문맥이 없는
     * 400으로 나가고, 어느 파라미터가 문제인지 응답에 남지 않는다(QaStatsController와 같은 이유).
     */
    private fun parseInstant(value: String?, field: String): Instant? =
        value?.takeIf { it.isNotBlank() }?.let {
            try {
                Instant.parse(it)
            } catch (_: DateTimeParseException) {
                throw BadRequestException("$field must be an ISO-8601 instant")
            }
        }
}
