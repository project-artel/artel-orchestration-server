package kr.artel.orchestration.referencecontext.controller

import kr.artel.orchestration.referencecontext.dto.ReferenceContextListResponse
import kr.artel.orchestration.referencecontext.service.ReferenceContextService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * reference_context 조회 컨트롤러(Agent 참고용, permitAll).
 *
 * 저장은 API가 아니라 업로드 확정 시 [ReferenceContextExtractionService]가 도는 내부 pull
 * 파이프라인(문서 → /extract → 적재)이 담당한다. 컨트롤러는 조회만 노출하며 로직은
 * [ReferenceContextService]에 위임한다. 엔드유저 JWT가 아닌 내부 경로(api/orchestration 하위)다.
 */
@RestController
@RequestMapping("/api/orchestration/reference-context")
class ReferenceContextController(
    private val referenceContextService: ReferenceContextService
) {

    /** 프로젝트의 특정 타입 reference_context를 조회한다(문서 전반). Agent가 타입별로 참고. */
    @GetMapping
    fun getReferenceContextByProjectAndType(
        @RequestParam projectId: Long,
        @RequestParam type: String
    ): Mono<ReferenceContextListResponse> =
        referenceContextService.findReferenceContextByType(projectId, type)
}
