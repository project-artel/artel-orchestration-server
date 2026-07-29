package kr.artel.orchestration.knowledge.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/**
 * `knowledge_embedding` 큐/저장 접근.
 *
 * **Spring Data 리포지토리가 아니라 [DatabaseClient]를 쓰는 이유는 둘이다.**
 * 1. `vector` 타입에 대응하는 R2DBC 코덱이 없다. 문자열로 보내고 SQL에서 `::vector`로 캐스팅한다.
 * 2. `FOR UPDATE SKIP LOCKED`와 `INSERT ... ON CONFLICT DO NOTHING`은 파생 쿼리로 표현할 수 없다.
 *
 * 큐의 상태 모델은 마이그레이션 V18 주석에 적힌 두 상태(대기 행 / 완성 행)를 따른다.
 */
@Repository
class KnowledgeEmbeddingRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * 아직 이 (kind, model)로 아무 행도 없는 knowledge에 대기 행을 만든다.
     *
     * 삭제된 항목은 제외한다 — 소프트삭제된 knowledge에 임베딩 비용을 쓸 이유가 없다.
     * `ON CONFLICT DO NOTHING`은 부분 유니크 인덱스(`uq_knowledge_embedding_pending`)에 기대어,
     * 인스턴스 두 대가 동시에 시딩해도 대기 행이 하나만 남게 한다.
     *
     * @return 새로 만들어진 대기 행 수
     */
    suspend fun seedPending(kind: String, model: String, limit: Int): Long =
        databaseClient.sql(
            """
            INSERT INTO knowledge_embedding (knowledge_id, kind, model)
            SELECT k.id, :kind, :model
              FROM knowledge k
             WHERE k.deleted_at IS NULL
               AND NOT EXISTS (
                   SELECT 1 FROM knowledge_embedding e
                    WHERE e.knowledge_id = k.id AND e.kind = :kind AND e.model = :model
               )
             ORDER BY k.id
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
     * `FOR UPDATE SKIP LOCKED`가 핵심이다. 인스턴스가 여러 개로 뜨면 같은 행을 동시에 집어
     * 중복 임베딩 청구서가 나온다. 잠긴 행은 건너뛰므로 두 워커가 서로를 기다리지도 않는다.
     *
     * **`attempts`를 실패 시점이 아니라 이 시점에 올린다.** 프로세스가 임베딩 도중 죽어도 시도가
     * 계산되므로, 워커를 죽이는 항목이 매 tick 되살아나 큐를 점유하는 일이 없다.
     *
     * 잠금은 이 한 문장이 끝나면 풀린다. 뒤이은 Agent 호출(수십 초)은 락 밖에서 일어난다.
     */
    suspend fun claimPending(kind: String, model: String, maxAttempts: Int, limit: Int): List<ClaimedRow> =
        databaseClient.sql(
            """
            WITH claimed AS (
                SELECT e.id
                  FROM knowledge_embedding e
                 WHERE e.kind = :kind
                   AND e.model = :model
                   AND e.source_text IS NULL
                   AND e.attempts < :maxAttempts
                 ORDER BY e.id
                 LIMIT :limit
                 FOR UPDATE SKIP LOCKED
            )
            UPDATE knowledge_embedding e
               SET attempts = e.attempts + 1,
                   updated_at = CURRENT_TIMESTAMP
              FROM claimed c
             WHERE e.id = c.id
            RETURNING e.id, e.knowledge_id
            """.trimIndent()
        )
            .bind("kind", kind)
            .bind("model", model)
            .bind("maxAttempts", maxAttempts)
            .bind("limit", limit)
            .map { row: Readable ->
                ClaimedRow(
                    pendingId = row.get("id", java.lang.Long::class.java)!!.toLong(),
                    knowledgeId = row.get("knowledge_id", java.lang.Long::class.java)!!.toLong()
                )
            }
            .flow()
            .toList()

    /**
     * 대기 행을 완성 행들로 바꾼다(대기 행 삭제 + 벡터 행 삽입).
     *
     * 두 문장이 한 트랜잭션에 있어야 한다. 중간에 끊기면 대기 행은 사라졌는데 벡터는 없는 상태가
     * 되고, 시딩은 "이미 행이 있다"고 보아 그 항목을 영영 다시 집지 않는다.
     * 호출자가 `TransactionalOperator`로 감싼다.
     */
    suspend fun replacePendingWithVectors(pendingId: Long, knowledgeId: Long, kind: String, model: String, vectors: List<EmbeddedText>) {
        databaseClient.sql("DELETE FROM knowledge_embedding WHERE id = :id")
            .bind("id", pendingId)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()

        for (vector in vectors) {
            databaseClient.sql(
                """
                INSERT INTO knowledge_embedding (knowledge_id, kind, model, source_text, embedding)
                VALUES (:knowledgeId, :kind, :model, :sourceText, CAST(:embedding AS vector))
                """.trimIndent()
            )
                .bind("knowledgeId", knowledgeId)
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
            UPDATE knowledge_embedding
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

    /** 대기 행을 버린다. 대상 knowledge가 사라졌거나 삭제된 경우에 쓴다. */
    suspend fun deletePending(pendingId: Long) {
        databaseClient.sql("DELETE FROM knowledge_embedding WHERE id = :id")
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
    val knowledgeId: Long
)

/** 임베딩이 끝난 텍스트 한 건. */
data class EmbeddedText(
    val text: String,
    val vector: List<Double>
) {
    /** pgvector가 받는 리터럴 형식: `[0.1,0.2,...]`. */
    fun toVectorLiteral(): String = vector.joinToString(prefix = "[", postfix = "]", separator = ",")
}
