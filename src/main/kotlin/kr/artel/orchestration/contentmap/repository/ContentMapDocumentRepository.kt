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
     * 이 빌드의 아직 앉지 않은 문서. 화면의 스캔 버튼이 돌려받는 결과가 이것을 집어 간다.
     *
     * 전역 [findPending] 을 그대로 쓸 수 없다. 한 사람의 스캔 결과에 남의 프로젝트 문서가 섞이면
     * 그 사람의 요청 시간에 남의 적재가 실리고, 남의 실패가 그 응답에 나타난다.
     *
     * `content_map` 을 조인하는 이유는 문서가 빌드를 직접 들고 있지 않기 때문이다 —
     * `content_map_document` → `content_map` → `game_build` 순으로 묶인다.
     *
     * **한 번도 시도하지 않은 문서를 먼저 본다**(`ingest_failed_at ASC NULLS FIRST`). 받은 순서로만
     * 고르면 깨진 문서가 큐 머리를 잡고, 상한만큼 쌓이는 순간 새로 올라온 문서에는 차례가 영영
     * 오지 않는다. 그 다음이 오래된 순인 것은 같은 지도에 문서가 여럿이면 시간순으로 앉아야
     * 나중 것이 이기기 때문이다.
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
     * 적재가 왜 깨졌는지 적는다. [stampIngested] 와 마찬가지로 두 칸만 갱신한다.
     *
     * **호출자는 이것을 적재 트랜잭션 밖에서 불러야 한다.** 적재는 실패하면 트랜잭션째 되돌아가므로,
     * 안에서 쓴 기록은 함께 지워지고 문서는 `ingested_at IS NULL` 인 채로 남아 **아무 일도 없었던
     * 것과 똑같은 모양**이 된다. 그러면 사람이 화면에서 "왜 안 됐나"를 물을 자리가 없다.
     *
     * `ingested_at` 은 건드리지 않는다. 실패해도 문서는 대기 상태 그대로이고, 다음 시도가 다시
     * 집어 갈 수 있어야 한다 — 이 두 칸은 "왜 못 앉았나"를 적을 뿐 큐에서 빼지 않는다.
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
}
