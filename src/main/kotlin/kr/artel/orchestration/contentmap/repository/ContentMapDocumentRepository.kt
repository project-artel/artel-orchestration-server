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
