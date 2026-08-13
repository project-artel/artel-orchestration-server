package kr.artel.orchestration.knowledge.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.knowledge.dto.KnowledgeGraphViewResponse
import kr.artel.orchestration.knowledge.service.KnowledgeGraphViewService
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * 지식창고를 그래프로 읽는 사용자 화면용 조회.
 *
 * `/internal/knowledge`가 아니라 `/api` 아래다. 저쪽은 agent-server가 부르는 무인증 경로이고
 * 내부 포트에만 실린다(`project.md`의 신뢰 경계). 이 조회는 사람이 브라우저에서 보는 것이라
 * 인증이 필요하다.
 *
 * 프로젝트 하위 경로로 두는 것은 그래프의 스코프가 프로젝트이기 때문이다. `/api/knowledge-graph`에
 * projectId를 파라미터로 받는 형태도 되지만, 접근 판정이 프로젝트 참여 여부인 이상 경로가 그것을
 * 드러내는 편이 읽기 쉽다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/knowledge-graph")
class KnowledgeGraphViewController(
    private val service: KnowledgeGraphViewService,
    private val userResolver: SessionUserResolver
) {
    /**
     * @param nodeLimit 담을 노드 최대 개수. 넘치면 응답의 `truncated`가 서고, **간선도 함께
     *   잘린다** — 잘려 나간 노드에 걸린 간선은 응답에 없다.
     */
    @GetMapping
    suspend fun graph(
        @PathVariable projectId: Long,
        @RequestParam(defaultValue = "200") nodeLimit: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<KnowledgeGraphViewResponse> =
        ResponseEntity.ok(
            service.graph(
                projectId = projectId,
                userId = requireUser(jwt),
                nodeLimit = nodeLimit
            )
        )

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()
}
