package kr.artel.orchestration.knowledge.repository

import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.EmbeddingQueue
import kr.artel.orchestration.common.embedding.EmbeddingQueueRepository
import kr.artel.orchestration.common.embedding.EmbeddingTableSpec
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/**
 * knowledge 임베딩 큐/저장 접근.
 *
 * 실제 SQL은 도메인 무관한 공용 [EmbeddingQueueRepository]가 담당하고, 여기서는 knowledge 테이블
 * 좌표만 지정해 위임한다(ARTEL-215). **상속이 아니라 위임**인 이유: `@Repository` 예외 변환 CGLIB
 * 프록시가 상속된 final 메서드를 가로채지 못해, 상속하면 프록시 인스턴스의 null 필드로 SQL이 돌아
 * NPE가 난다. 큐의 상태 모델은 마이그레이션 V18 주석의 두 상태(대기 행 / 완성 행)를 따른다.
 */
@Repository
class KnowledgeEmbeddingRepository(databaseClient: DatabaseClient) : EmbeddingQueue {

    private val queue = EmbeddingQueueRepository(
        databaseClient,
        EmbeddingTableSpec(
            embeddingTable = "knowledge_embedding",
            ownerIdColumn = "knowledge_id",
            ownerTable = "knowledge",
            // knowledge는 소프트삭제가 있어, 삭제된 항목에는 임베딩 비용을 쓰지 않는다.
            aliveClause = "deleted_at IS NULL",
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
