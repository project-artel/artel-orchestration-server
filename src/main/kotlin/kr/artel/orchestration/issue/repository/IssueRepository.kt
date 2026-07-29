package kr.artel.orchestration.issue.repository

import kr.artel.orchestration.issue.entity.IssueEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

interface IssueRepository : CoroutineCrudRepository<IssueEntity, Long> {
    /** 재전송 프레임을 원래 행으로 되돌리는 멱등 조회. 라우터가 messageId(UUID)를 보장한다. */
    suspend fun findByQaTryIdAndMessageId(qaTryId: Long, messageId: String): IssueEntity?
}
