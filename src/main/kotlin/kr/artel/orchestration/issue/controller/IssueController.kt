package kr.artel.orchestration.issue.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.issue.dto.IssueResponse
import kr.artel.orchestration.issue.service.IssueService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * QA 이슈 조회 컨트롤러(엔드유저/FE 대상, JWT + 프로젝트 멤버 인가).
 *
 * 이슈의 "저장"은 API가 아니라 QA WebSocket 인바운드 경로([QaAgentInboundRouter])가 Agent
 * 프레임을 받아 [IssueService]로 넘기는 내부 파이프라인이 담당한다. 컨트롤러는 조회만 노출하며
 * 로직·인가는 서비스에 위임한다(요청 매핑과 HTTP 상태만).
 */
@RestController
@RequestMapping("/api/issues")
class IssueController(
    private val service: IssueService,
    private val userResolver: SessionUserResolver
) {
    /** 한 QA 실행이 남긴 이슈를 최신순으로 조회한다. 접근 불가한 실행이면 404. */
    @GetMapping
    fun listIssuesForQaTry(
        @RequestParam qaTryId: String,
        @RequestParam(defaultValue = "50") size: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<List<IssueResponse>>> {
        if (size !in 1..200) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 200")
        }
        return service.listByQaTry(parseId(qaTryId), requireUser(jwt), size)
            .map { ResponseEntity.ok(it) }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
    }

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ID must be a signed 64-bit decimal string")
}
