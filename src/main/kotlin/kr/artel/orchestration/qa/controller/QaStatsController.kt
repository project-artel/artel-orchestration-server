package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.QaStatsResponse
import kr.artel.orchestration.qa.service.QaStatsService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * QA 런의 실행 설정 집계.
 *
 * `/api/qa-tries/stats`가 아니라 자기 루트를 쓴다 — 저쪽에 두면 `/api/qa-tries/{qaTryId}`와 같은
 * 자리를 다투고, 나중에 경로 매칭 우선순위에 기대는 코드가 된다.
 */
@RestController
@RequestMapping("/api/qa-stats")
class QaStatsController(
    private val service: QaStatsService
) {
    /**
     * @param projectId 생략하면 볼 수 있는 전 프로젝트를 합산한다. `DEVELOPER` 등급에게는 그것이
     *   배포 전체이고, 그 밖에는 참여 중인 프로젝트다.
     * @param from,to ISO-8601 instant(`2026-08-01T00:00:00Z`). 생략하면 최근 30일.
     * @param cellLimit 돌려줄 조합 최대 개수. 넘치면 응답의 `truncated`가 선다.
     */
    @GetMapping
    suspend fun stats(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "200") cellLimit: Int,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaStatsResponse> =
        ResponseEntity.ok(
            service.stats(
                projectId = projectId?.let(::parseId),
                userId = appUserId,
                from = parseInstant(from, "from"),
                to = parseInstant(to, "to"),
                cellLimit = cellLimit
            )
        )

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw BadRequestException("ID must be a signed 64-bit decimal string")

    /**
     * 프레임워크 변환에 맡기지 않고 직접 읽는다. 맡기면 오타 하나가 이 컨트롤러의 문맥이 없는
     * 400으로 나가고, 어느 파라미터가 문제인지 응답에 남지 않는다.
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
