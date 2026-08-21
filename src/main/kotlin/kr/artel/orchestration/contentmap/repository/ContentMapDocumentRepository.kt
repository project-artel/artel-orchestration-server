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
     * 아직 적재되지 않은 문서. ARTEL-442 가 집어 간다.
     *
     * 오래된 것부터인 이유: 같은 지도에 문서가 여럿이면 시간순으로 적재해야 나중 것이 이긴다.
     */
    @Query(
        """
        SELECT * FROM content_map_document
        WHERE ingested_at IS NULL
        ORDER BY received_at ASC
        LIMIT :limit
        """
    )
    fun findPending(limit: Int): Flow<ContentMapDocumentEntity>

    /**
     * 이 빌드의 대기 문서. 사람이 화면에서 버튼을 누르면 이쪽이 돈다.
     *
     * 전역 [findPending] 을 그대로 쓰지 않는 이유: 남의 프로젝트 문서가 그 요청 시간에 섞여 들어가고,
     * 실패도 그 사람의 응답에 섞인다.
     *
     * **한 번도 시도하지 않은 문서가 먼저다.** 결정적으로 깨지는 문서는 도장이 안 찍혀 영원히 대기로
     * 남는데, 받은 순서로만 고르면 그것이 큐 머리를 잡고 앉아 새로 올린 문서에 차례가 오지 않는다.
     * 실패한 것들끼리는 오래 전에 실패한 것부터 다시 돌려 본다.
     *
     * `content_map_document` 에는 `game_build_id` 가 없어 `content_map` 을 조인한다. 기존 부분 인덱스는
     * `(received_at) WHERE ingested_at IS NULL` 이라 빌드 필터를 덮지 않는다 — 지금 행 수에서는 문제가
     * 아니라 인덱스를 새로 만들지 않는다.
     */
    @Query(
        """
        SELECT d.* FROM content_map_document d
        JOIN content_map m ON m.id = d.content_map_id
        WHERE m.game_build_id = :gameBuildId AND d.ingested_at IS NULL
        ORDER BY d.ingest_failed_at ASC NULLS FIRST, d.received_at ASC
        LIMIT :limit
        """
    )
    fun findPendingByGameBuild(gameBuildId: Long, limit: Int): Flow<ContentMapDocumentEntity>

    /**
     * 적재 실패를 문서에 적는다.
     *
     * **적재 트랜잭션 밖에서 불러야 한다.** 안에서 쓰면 실패와 함께 되돌아가 아무것도 안 남고,
     * 문서는 "아무 일도 없었던 것"과 똑같은 모양이 된다.
     *
     * 도장과 같은 이유로 두 칸만 갱신한다 — 행 전체를 저장하면 읽어 온 엔티티에 없던 값이 null 로
     * 덮인다.
     */
    @Modifying
    @Query(
        """
        UPDATE content_map_document
           SET ingest_failed_at = :failedAt, ingest_error = :error
         WHERE id = :id
        """
    )
    suspend fun recordIngestFailure(id: Long, failedAt: Instant, error: String): Long

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
}
