package kr.artel.orchestration.testcase.repository

import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.EmbeddingQueue
import kr.artel.orchestration.common.embedding.EmbeddingQueueRepository
import kr.artel.orchestration.common.embedding.EmbeddingTableSpec
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * test_case 임베딩 큐/저장 접근(ARTEL-216).
 *
 * 실제 SQL은 도메인 무관한 공용 [EmbeddingQueueRepository]가 담당하고, 여기서는 test_case 테이블
 * 좌표만 지정해 위임한다. **상속이 아니라 위임**인 이유: `@Repository` 예외변환 CGLIB 프록시가
 * 상속된 final 메서드를 못 가로채 NPE가 나기 때문(자세한 근거는 knowledge 쪽과 동일).
 *
 * test_case는 소프트삭제가 없어 alive 조건이 없고, 삭제 정리는 FK ON DELETE CASCADE가 맡는다.
 */
@Repository
class TestCaseEmbeddingRepository(databaseClient: DatabaseClient) : EmbeddingQueue {

    private val queue = EmbeddingQueueRepository(
        databaseClient,
        EmbeddingTableSpec(
            embeddingTable = "test_case_embedding",
            ownerIdColumn = "test_case_id",
            ownerTable = "test_case",
            aliveClause = null,
        )
    )

    override suspend fun seedPending(kind: String, model: String, limit: Int): Long =
        queue.seedPending(kind, model, limit)

    override suspend fun claimPending(kind: String, model: String, maxAttempts: Int, limit: Int): List<ClaimedRow> =
        queue.claimPending(kind, model, maxAttempts, limit)

    override suspend fun replacePendingWithVectors(
        pendingId: Long,
        ownerId: Long,
        kind: String,
        model: String,
        vectors: List<EmbeddedText>,
    ) = queue.replacePendingWithVectors(pendingId, ownerId, kind, model, vectors)

    override suspend fun recordFailure(pendingId: Long, error: String) =
        queue.recordFailure(pendingId, error)

    override suspend fun discardFor(ownerId: Long): Long =
        queue.discardFor(ownerId)

    override suspend fun deletePending(pendingId: Long) =
        queue.deletePending(pendingId)
}
