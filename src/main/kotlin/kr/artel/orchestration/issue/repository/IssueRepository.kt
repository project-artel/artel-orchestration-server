package kr.artel.orchestration.issue.repository

import kr.artel.orchestration.issue.entity.IssueEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

interface IssueRepository : ReactiveCrudRepository<IssueEntity, Long> {
    /** 재전송 프레임을 원래 행으로 되돌리는 멱등 조회. 라우터가 messageId(UUID)를 보장한다. */
    fun findByQaTryIdAndMessageId(qaTryId: Long, messageId: String): Mono<IssueEntity>

    /** 한 실행이 남긴 이슈, 최신순. size로 상한을 둔다(qa_log 목록과 동일한 방식). */
    @Query(
        """
        SELECT * FROM issue
        WHERE qa_try_id = :qaTryId
        ORDER BY id DESC
        LIMIT :size
        """
    )
    fun findByQaTryId(qaTryId: Long, size: Int): Flux<IssueEntity>
}
