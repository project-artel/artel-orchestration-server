package kr.artel.orchestration.referencecontext.repository

import kr.artel.orchestration.referencecontext.entity.ReferenceContextEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * reference_context R2DBC 리포지토리.
 *
 * 조회는 프로젝트 스코프로 하며, Agent가 타입별로 뽑으므로 `findByProjectIdAndType`가 주 조회다.
 * 문서 재추출 시 그 문서의 행을 통째 교체하기 위해 `deleteByProjectIdAndSourceDocumentId`를 둔다.
 */
interface ReferenceContextRepository : ReactiveCrudRepository<ReferenceContextEntity, Long> {

    /** 프로젝트의 특정 타입 항목(문서 전반)을 조회한다. */
    fun findByProjectIdAndType(projectId: Long, type: String): Flux<ReferenceContextEntity>

    /** 프로젝트의 모든 reference_context 항목을 조회한다. */
    fun findByProjectId(projectId: Long): Flux<ReferenceContextEntity>

    /** 한 문서에서 추출된 모든 타입 행을 제거한다(재추출 교체용). */
    fun deleteByProjectIdAndSourceDocumentId(projectId: Long, sourceDocumentId: Long): Mono<Void>
}
