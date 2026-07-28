package kr.artel.orchestration.knowledge.controller

import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * knowledge 조회 컨트롤러. 저장은 API가 아니라 내부 파이프라인(docs 추출 / QA WS)이 담당하므로
 * 조회만 노출한다. 내부 경로(api/orchestration 하위, permitAll — 엔드유저 JWT 아님)이며,
 * Agent/FE가 프로젝트 스코프로 source/tag를 걸어 조회한다. 컨트롤러는 얇게: 필터 파싱·검증만 하고
 * 조회 로직은 [KnowledgeService]에 위임한다. (Phase 2에서 하이브리드 검색이 여기 붙는다.)
 */
@RestController
@RequestMapping("/api/knowledge")
class KnowledgeController(
    private val knowledgeService: KnowledgeService
) {

    /** 프로젝트의 knowledge를 최신순으로 조회한다. source/tag는 선택 필터(잘못된 값이면 400). */
    @GetMapping
    fun getKnowledgeByProjectAndFilters(
        @RequestParam projectId: Long,
        @RequestParam(required = false) source: String?,
        @RequestParam(required = false) tag: String?
    ): Mono<ResponseEntity<KnowledgeListResponse>> {
        val parsedSource = source?.let { KnowledgeSource.fromWire(it) }
        val parsedTag = tag?.let { KnowledgeTag.fromWire(it) }
        if ((source != null && parsedSource == null) || (tag != null && parsedTag == null)) {
            return Mono.just(ResponseEntity.badRequest().build())
        }
        return knowledgeService.findForProject(projectId, parsedSource, parsedTag)
            .map { ResponseEntity.ok(it) }
    }
}
