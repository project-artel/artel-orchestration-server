package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.QaStatsLabelsResponse
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
     * @param testRunId 이 test run 의 런만 센다. 벤치마크 런이 난이도별로 test run 으로 쪼개져 있어
     *   (프로브 · L1 상세 · L2 중간 · L3 추상), 이 값 없이는 넷이 한 덩어리로 접힌다. 생략하면
     *   지금까지처럼 전부이고 단독 실행 런도 함께 나온다. 볼 수 없는 프로젝트의 id 를 줘도 404 가
     *   아니라 빈 집계다 — 갈라 답하면 그 test run 의 존재 여부가 새어 나간다.
     * @param label 이 실험 묶음의 런만 센다. 같은 설정으로 다음 달에 다시 돌리면 `run_config` 는
     *   같은데 다른 실험이고, 그 둘을 가르는 것이 이 값이다. [testRunId] 와 **독립이라 함께 걸 수
     *   있다** — "1차 실험의 9013 런" 이 실제 질문이다. 생략하면 어느 실험에도 안 묶인 런까지 전부다.
     */
    @GetMapping
    suspend fun stats(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) testRunId: String?,
        @RequestParam(required = false) label: String?,
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
                cellLimit = cellLimit,
                testRunId = testRunId?.let(::parseId),
                // 빈 문자열은 "안 준 것" 으로 읽는다. 쿼리 문자열은 `label=` 로 빈 값을 실을 수 있고,
                // 그것을 이름으로 받으면 아무 실험에도 안 맞아 화면이 이유 없이 0건이 된다.
                label = label?.takeIf { it.isNotBlank() }
            )
        )

    /**
     * 이미 쓰인 실험 묶음 이름의 목록. 화면의 `label` 자리가 자유 입력이 아니라 **고르는 자리**여야
     * 하기 때문에 있다 — 자유 문자열의 실질 위험은 `content map 1차` 와 `content map 1차 실험` 이
     * 두 칸으로 갈리는 것이고, 고르게 만들면 tag 체계 없이 그것이 막힌다.
     *
     * `/api/qa-stats` 아래에 두는 이유는 이 목록이 그 집계의 필터 값이기 때문이다. 가시성도 그 집계와
     * 같은 술어라, 여기 선 이름은 고르는 순간 그 사용자에게 실제로 행이 있는 이름이다.
     *
     * @param projectId 생략하면 볼 수 있는 전 프로젝트의 목록이다. 집계와 같은 규칙이라, 프로젝트를
     *   안 고르고 합산해 보는 화면에서도 실험으로 묶어 볼 수 있다.
     */
    @GetMapping("/labels")
    suspend fun labels(
        @RequestParam(required = false) projectId: String?,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaStatsLabelsResponse> =
        ResponseEntity.ok(service.labels(projectId?.let(::parseId), appUserId))

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
