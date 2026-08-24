package kr.artel.orchestration.contentmap.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * 근거 문서 포인터 조회.
 *
 * [findByContentMapIdAndContentHash] 가 멱등의 축이다 — 같은 내용이 다시 오면 저장도 적재도
 * 건너뛴다. SDK 는 게임 실행마다 등록하므로 같은 빌드의 같은 문서가 반복해서 온다.
 */
interface ContentMapDocumentRepository : CoroutineCrudRepository<ContentMapDocumentEntity, Long> {

    suspend fun findByContentMapIdAndContentHash(
        contentMapId: Long,
        contentHash: String,
    ): ContentMapDocumentEntity?

    fun findByContentMapIdOrderByReceivedAtDesc(contentMapId: Long): Flow<ContentMapDocumentEntity>

    /**
     * 아직 적재되지 않은 문서를 [limit] 개까지 집는다. ARTEL-442 가 집어 가고 ARTEL-502 가 부른다.
     *
     * 오래된 것부터인 이유: 같은 지도에 문서가 여럿이면 시간순으로 적재해야 나중 것이 이긴다.
     *
     * **[ContentMapDocumentEntity.ingestAttempts] 를 집는 이 자리에서 올린다.** 실패 시점에
     * 올리면 적재기를 죽이는 문서는 시도가 한 번도 기록되지 않아 [maxAttempts] 에 닿지 못하고,
     * 매 tick 되살아나 큐의 앞자리를 차지한다. 이 UPDATE 는 적재 트랜잭션 밖에서 먼저
     * 커밋되므로 적재가 롤백돼도 횟수는 남는다 — 안에 두면 롤백이 장부까지 되돌린다.
     *
     * `FOR UPDATE SKIP LOCKED` 는 인스턴스가 둘일 때를 위한 것이다. 없으면 둘이 같은 문서를
     * 집어 같은 1.4 MB 를 두 번 파싱한다. 잠긴 행은 건너뛰므로 서로를 기다리지도 않는다.
     * `embedding_pending` 의 claim 이 같은 이유로 같은 모양이다.
     */
    @Query(
        """
        WITH claimed AS (
            SELECT d.id
              FROM content_map_document d
             WHERE d.ingested_at IS NULL
               AND d.ingest_attempts < :maxAttempts
             ORDER BY d.received_at ASC
             LIMIT :limit
             FOR UPDATE SKIP LOCKED
        )
        UPDATE content_map_document d
           SET ingest_attempts = d.ingest_attempts + 1
          FROM claimed c
         WHERE d.id = c.id
        RETURNING d.*
        """
    )
    fun claimPending(limit: Int, maxAttempts: Int): Flow<ContentMapDocumentEntity>

    /**
     * 적재 도장. **행 전체를 저장하지 않고 두 칸만 갱신한다.**
     *
     * `save(entity.copy(...))` 는 UPDATE 에 모든 칸을 실어, 읽어 온 엔티티에 없던 값(`received_at` 은
     * DB 기본값으로 채워진다)을 null 로 덮어쓴다. 도장은 도장만 찍어야 한다.
     */
    @Modifying
    @Query(
        """
        UPDATE content_map_document
           SET ingested_by = :ingestedBy, ingested_at = :ingestedAt
         WHERE id = :id
        """
    )
    suspend fun stampIngested(id: Long, ingestedBy: String, ingestedAt: Instant): Long

    /**
     * 실패 사유를 남긴다. 횟수는 [claimPending] 에서 이미 올랐다.
     *
     * [stampIngested] 와 같은 이유로 한 칸만 갱신한다 — `save(entity.copy(...))` 는 UPDATE 에
     * 모든 칸을 실어, 읽어 온 엔티티에 없던 값을 null 로 덮어쓴다.
     */
    @Modifying
    @Query(
        """
        UPDATE content_map_document
           SET last_error = :lastError
         WHERE id = :id
        """
    )
    suspend fun recordFailure(id: Long, lastError: String): Long
}
