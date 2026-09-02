package kr.artel.orchestration.knowledge.repository

import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.EmbeddingQueue
import kr.artel.orchestration.common.embedding.EmbeddingQueueRepository
import kr.artel.orchestration.common.embedding.EmbeddingTableSpec
import kr.artel.orchestration.knowledge.entity.KnowledgeDocumentNodeSql
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
            //
            // **문서 node는 애초에 백필 대상에서 뺀다(ARTEL-748).** 문서 node는 게임에 대한 사실이
            // 아니라 그 문서에서 나온 항목들을 묶는 구조적 표지라, search_knowledge(KNOWLEDGE_SEARCH)
            // 결과에 섞이면 잡음이다. 매 검색 질의에 필터를 추가하는 대신(검색은 QA 스텝마다 발화하는
            // 잦은 경로다) 여기서 임베딩 자체를 만들지 않는다 — `KnowledgeVectorSearchRepository`의
            // `searchNearest`는 `knowledge_embedding`을 INNER JOIN하므로, 벡터가 없으면 그 조인이
            // 자동으로 이 행을 거른다. 백필 tick은 검색보다 훨씬 드물게 돌아서 이 조건을 매 tick
            // 스캔에 더하는 비용이 싸고, 애초에 만들지 않으므로 Agent 임베딩 호출 비용도 아낀다.
            // 술어는 [KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE] 하나에서 온다 — 그 KDoc이 이유를
            // 적어 뒀다.
            aliveClause = "deleted_at IS NULL AND NOT (${KnowledgeDocumentNodeSql.IS_DOCUMENT_NODE})",
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
