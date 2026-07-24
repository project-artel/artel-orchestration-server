package kr.artel.orchestration.referencecontext.controller

import kr.artel.orchestration.referencecontext.dto.ReferenceContextListResponse
import kr.artel.orchestration.referencecontext.dto.StoreReferenceContextRequest
import kr.artel.orchestration.referencecontext.service.ReferenceContextService
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import reactor.core.publisher.Mono

/**
 * reference_context 내부 통신 컨트롤러(Agent/추출 파이프라인용, permitAll).
 *
 * 컨트롤러는 얇게 유지한다: 요청 매핑과 HTTP 상태 매핑만 하고, 분해/교체/조회 로직은
 * [ReferenceContextService]에 위임한다. 엔드유저 JWT가 아닌 내부 경로(api/orchestration 하위)다.
 */
@RestController
@RequestMapping("/api/orchestration/reference-context")
class ReferenceContextController(
    private val referenceContextService: ReferenceContextService
) {

    /**
     * 문서에서 추출한 game_context를 타입별로 저장한다(같은 문서 재추출 시 그 문서분 교체).
     * 멱등 교체이므로 PUT.
     */
    @PutMapping
    fun replaceExtractedReferenceContext(
        @RequestBody request: StoreReferenceContextRequest
    ): Mono<ResponseEntity<Void>> =
        referenceContextService.replaceDocumentReferenceContext(
            request.projectId,
            request.sourceDocumentId,
            request.gameContext
        ).thenReturn(ResponseEntity.noContent().build())

    /** 프로젝트의 특정 타입 reference_context를 조회한다(문서 전반). Agent가 타입별로 참고. */
    @GetMapping
    fun getReferenceContextByProjectAndType(
        @RequestParam projectId: Long,
        @RequestParam type: String
    ): Mono<ReferenceContextListResponse> =
        referenceContextService.findReferenceContextByType(projectId, type)
}
