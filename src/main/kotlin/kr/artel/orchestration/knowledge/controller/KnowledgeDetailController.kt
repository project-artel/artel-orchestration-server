package kr.artel.orchestration.knowledge.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.knowledge.dto.KnowledgeDetailResponse
import kr.artel.orchestration.knowledge.service.KnowledgeGraphViewService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * knowledge 항목 하나를 본문까지 읽는 사용자 화면용 단건 조회(ARTEL-753).
 *
 * [KnowledgeGraphViewController]와 경로 루트가 다르다(`/knowledge` vs `/knowledge-graph`) — 그
 * 컨트롤러의 `@RequestMapping`을 바꾸지 않고는 이 경로를 얹을 수 없어 별도 클래스로 둔다. 서비스는
 * 같은 [KnowledgeGraphViewService]를 공유한다 — 그래프 목록과 단건 조회가 같은 접근 판정
 * ([kr.artel.orchestration.project.service.ProjectAccessService])과 같은 운영 스코프 술어를 쓴다.
 *
 * `/internal/knowledge`가 아니라 `/api` 아래인 이유는 [KnowledgeGraphViewController]와 같다 — 사람이
 * 브라우저에서 보는 조회라 인증이 필요하다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/knowledge")
class KnowledgeDetailController(
    private val service: KnowledgeGraphViewService,
    private val userResolver: SessionUserResolver
) {
    @GetMapping("/{knowledgeId}")
    suspend fun detail(
        @PathVariable projectId: Long,
        @PathVariable knowledgeId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<KnowledgeDetailResponse> =
        ResponseEntity.ok(
            service.detail(
                projectId = projectId,
                userId = requireUser(jwt),
                knowledgeId = knowledgeId
            )
        )

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
}
