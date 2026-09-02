package kr.artel.orchestration.knowledge.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.knowledge.dto.KnowledgeStatsResponse
import kr.artel.orchestration.knowledge.service.KnowledgeStatsService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * 지식창고 결과의 실행 설정 축 집계.
 *
 * `/internal/knowledge` 아래가 아니라 `/api` 아래에 둔다. 저쪽은 agent-server가 부르는 무인증
 * 서버-투-서버 경로이고 내부 포트에만 실리는데, 이 집계는 운영자가 브라우저에서 보는 것이라
 * 인증이 필요하다(`project.md`의 신뢰 경계 규칙).
 *
 * `KnowledgeController`에 붙이지 않고 자기 루트를 쓰는 것은 `QaStatsController`가 `/api/qa-tries`
 * 를 피한 것과 같은 이유다 — 항목 조회 경로와 자리를 다투면 나중에 경로 매칭 우선순위에 기대는
 * 코드가 된다.
 */
@RestController
@RequestMapping("/api/knowledge-stats")
class KnowledgeStatsController(
    private val service: KnowledgeStatsService,
    private val userResolver: SessionUserResolver
) {
    /**
     * @param projectId 생략하면 볼 수 있는 전 프로젝트를 합산한다. `DEVELOPER` 등급에게는 그것이
     *   배포 전체이고, 그 밖에는 참여 중인 프로젝트다.
     * @param from,to ISO-8601 instant(`2026-08-01T00:00:00Z`). 기준은 **버전이 만들어진 시각**이지
     *   런의 시작 시각이 아니다. 생략하면 최근 90일.
     * @param cellLimit 돌려줄 조합 최대 개수. 넘치면 응답의 `truncated`가 선다.
     */
    @GetMapping
    suspend fun stats(
        @RequestParam(required = false) projectId: String?,
        @RequestParam(required = false) from: String?,
        @RequestParam(required = false) to: String?,
        @RequestParam(defaultValue = "200") cellLimit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<KnowledgeStatsResponse> =
        ResponseEntity.ok(
            service.stats(
                projectId = projectId?.let(::parseId),
                userId = requireUser(jwt),
                from = parseInstant(from, "from"),
                to = parseInstant(to, "to"),
                cellLimit = cellLimit
            )
        )

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw BadRequestException("ID must be a non-negative 64-bit decimal string")

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
