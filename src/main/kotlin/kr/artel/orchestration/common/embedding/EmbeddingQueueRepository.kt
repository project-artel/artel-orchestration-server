package kr.artel.orchestration.common.embedding

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow

/**
 * 임베딩 큐/저장 접근(도메인 무관). `knowledge_embedding`에서 추출했다(ARTEL-215).
 *
 * **Spring Data 리포지토리가 아니라 [DatabaseClient]를 쓰는 이유는 둘이다.**
 * 1. `vector` 타입에 대응하는 R2DBC 코덱이 없다. 문자열로 보내고 SQL에서 `::vector`로 캐스팅한다.
 * 2. `FOR UPDATE SKIP LOCKED`와 `INSERT ... ON CONFLICT DO NOTHING`은 파생 쿼리로 표현할 수 없다.
 *
 * 테이블·컬럼 이름은 [spec]으로 SQL에 끼워 넣는다(코드 상수라 인젝션 위험 없음, [EmbeddingTableSpec] 참조).
 * 큐의 상태 모델(대기 행 / 완성 행)은 각 도메인 마이그레이션 주석의 두 상태를 따른다. 도메인별로
 * [spec]만 다른 인스턴스를 만들어 쓴다(knowledge / test_case ...).
 */
class EmbeddingQueueRepository(
    private val databaseClient: DatabaseClient,
    spec: EmbeddingTableSpec,
) {
    private val table = spec.embeddingTable
    private val ownerColumn = spec.ownerIdColumn
    private val ownerTable = spec.ownerTable

    // 소유 테이블 별칭은 o. 소프트삭제가 없는 도메인은 조건이 없으니 항상 참으로 둔다.
    private val aliveFilter = spec.aliveClause?.let { "o.$it" } ?: "TRUE"

    /**
     * 아직 이 (kind, model)로 아무 행도 없는 소유 행에 대기 행을 만든다.
     *
     * `ON CONFLICT DO NOTHING`은 부분 유니크 인덱스(`uq_*_embedding_pending`)에 기대어, 인스턴스
     * 두 대가 동시에 시딩해도 대기 행이 하나만 남게 한다.
     *
     * @return 새로 만들어진 대기 행 수
     */
    suspend fun seedPending(kind: String, model: String, limit: Int): Long =
        databaseClient.sql(
            """
            INSERT INTO $table ($ownerColumn, kind, model)
            SELECT o.id, :kind, :model
              FROM $ownerTable o
             WHERE $aliveFilter
               AND NOT EXISTS (
                   SELECT 1 FROM $table e
                    WHERE e.$ownerColumn = o.id AND e.kind = :kind AND e.model = :model
               )
             ORDER BY o.id
             LIMIT :limit
            ON CONFLICT DO NOTHING
            """.trimIndent()
        )
            .bind("kind", kind)
            .bind("model", model)
            .bind("limit", limit)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull() ?: 0L

    /**
     * 처리할 대기 행을 잡는다.
     *
     * `FOR UPDATE SKIP LOCKED`가 핵심이다. 인스턴스가 여러 개면 같은 행을 동시에 집어 중복 임베딩
     * 청구가 나온다. 잠긴 행은 건너뛰므로 두 워커가 서로를 기다리지도 않는다.
     *
     * **`attempts`를 실패 시점이 아니라 이 시점에 올린다.** 프로세스가 임베딩 도중 죽어도 시도가
     * 계산되어, 워커를 죽이는 항목이 매 tick 되살아나 큐를 점유하는 일이 없다. 잠금은 이 한 문장이
     * 끝나면 풀린다 — 뒤이은 Agent 호출(수십 초)은 락 밖에서 일어난다.
     */
    suspend fun claimPending(kind: String, model: String, maxAttempts: Int, limit: Int): List<ClaimedRow> =
        databaseClient.sql(
            """
            WITH claimed AS (
                SELECT e.id
                  FROM $table e
                 WHERE e.kind = :kind
                   AND e.model = :model
                   AND e.source_text IS NULL
                   AND e.attempts < :maxAttempts
                 ORDER BY e.id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE $table e
               SET attempts = e.attempts + 1,
                   updated_at = CURRENT_TIMESTAMP
              FROM claimed c
             WHERE e.id = c.id
            RETURNING e.id AS pending_id, e.$ownerColumn AS owner_id
            """.trimIndent()
        )
            .bind("kind", kind)
            .bind("model", model)
            .bind("maxAttempts", maxAttempts)
            .bind("limit", limit)
            .map { row: Readable ->
                ClaimedRow(
                    pendingId = row.get("pending_id", java.lang.Long::class.java)!!.toLong(),
                    ownerId = row.get("owner_id", java.lang.Long::class.java)!!.toLong()
                )
            }
            .flow()
            .toList()

    /**
     * 대기 행을 완성 행들로 바꾼다(대기 행 삭제 + 벡터 행 삽입).
     *
     * 두 문장이 한 트랜잭션에 있어야 한다. 중간에 끊기면 대기 행은 사라졌는데 벡터는 없는 상태가
     * 되고, 시딩은 "이미 행이 있다"고 보아 그 항목을 영영 다시 집지 않는다. 호출자가
     * `TransactionalOperator`로 감싼다.
     *
     * 대기 행이 이미 사라졌다면 그 사이에 소유 행이 수정·삭제되어 무효화된 것이다([discardFor]).
     * 지금 손에 든 벡터는 바뀌기 전 본문에서 나온 것이라 넣지 않고 버린다 — 무효화가 새로 만든
     * 대기 행(또는 다음 시딩)이 새 본문으로 다시 채운다.
     */
    suspend fun replacePendingWithVectors(
        pendingId: Long,
        ownerId: Long,
        kind: String,
        model: String,
        vectors: List<EmbeddedText>,
    ) {
        val deletedPendingRows = databaseClient.sql("DELETE FROM $table WHERE id = :id")
            .bind("id", pendingId)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull() ?: 0L

        if (deletedPendingRows == 0L) return

        for (vector in vectors) {
            databaseClient.sql(
                """
                INSERT INTO $table ($ownerColumn, kind, model, source_text, embedding)
                VALUES (:ownerId, :kind, :model, :sourceText, CAST(:embedding AS vector))
                """.trimIndent()
            )
                .bind("ownerId", ownerId)
                .bind("kind", kind)
                .bind("model", model)
                .bind("sourceText", vector.text)
                .bind("embedding", vector.toVectorLiteral())
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }
    }

    /** 실패 사유를 대기 행에 남긴다. `attempts`는 claim 때 이미 올랐다. */
    suspend fun recordFailure(pendingId: Long, error: String) {
        databaseClient.sql(
            """
            UPDATE $table
               SET last_error = :error, updated_at = CURRENT_TIMESTAMP
             WHERE id = :id
            """.trimIndent()
        )
            .bind("id", pendingId)
            .bind("error", error.take(MAX_ERROR_LENGTH))
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    /**
     * 한 소유 행의 임베딩 행을 전부 버린다 — 대기 행이든 완성 행이든.
     *
     * 소유 행의 본문이 바뀌면 옛 본문에서 나온 벡터는 틀린 것이 된다. 지우면 백필 시딩이 "이
     * (kind, model)로 행이 없다"고 보아 다시 집어 새 본문으로 채운다 — 되돌릴 상태를 따로 만들지
     * 않고 큐의 원점으로 돌린다. 소프트삭제 시에도 벡터까지 지워 검색이 조인 조건을 빠뜨려도
     * 삭제된 항목이 되살아나지 않게 한다.
     *
     * @return 지운 행 수
     */
    suspend fun discardFor(ownerId: Long): Long =
        databaseClient.sql("DELETE FROM $table WHERE $ownerColumn = :ownerId")
            .bind("ownerId", ownerId)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull() ?: 0L

    /** 대기 행을 버린다. 대상 소유 행이 사라졌거나 삭제된 경우에 쓴다. */
    suspend fun deletePending(pendingId: Long) {
        databaseClient.sql("DELETE FROM $table WHERE id = :id")
            .bind("id", pendingId)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }

    private companion object {
        /** last_error는 진단용이라 무한정 길 이유가 없다. 스택트레이스 전문이 들어오면 자른다. */
        const val MAX_ERROR_LENGTH = 1000
    }
}

/** claim된 대기 행 하나. */
data class ClaimedRow(
    val pendingId: Long,
    val ownerId: Long,
)
