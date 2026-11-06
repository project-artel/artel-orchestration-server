package kr.artel.orchestration.release.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.artel.orchestration.release.dto.ReleaseCriteria
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.time.Instant
import java.time.OffsetDateTime

data class StoredReleaseCriteria(val criteria: ReleaseCriteria, val updatedAt: Instant)

/** 프로젝트의 출시 기준 숫자(ARTEL-1386). 행이 없으면 아직 기본값이다. */
@Repository
class ReleaseCriteriaRepository(private val databaseClient: DatabaseClient) {

    suspend fun find(projectId: Long): StoredReleaseCriteria? =
        databaseClient.sql(
            """
            SELECT max_pending, max_regression, min_pass_rate, max_open_blockers, max_inconclusive_rate, updated_at
              FROM project_release_criteria
             WHERE project_id = :projectId
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .map { row: Readable ->
                StoredReleaseCriteria(
                    criteria = ReleaseCriteria(
                        maxPending = row.int("max_pending"),
                        maxRegression = row.int("max_regression"),
                        minPassRate = row.int("min_pass_rate"),
                        maxOpenBlockers = row.int("max_open_blockers"),
                        maxInconclusiveRate = row.int("max_inconclusive_rate")
                    ),
                    updatedAt = row.get("updated_at", OffsetDateTime::class.java)!!.toInstant()
                )
            }
            .one()
            .awaitFirstOrNull()

    suspend fun save(projectId: Long, criteria: ReleaseCriteria, updatedBy: Long) {
        databaseClient.sql(
            """
            INSERT INTO project_release_criteria
                (project_id, max_pending, max_regression, min_pass_rate, max_open_blockers, max_inconclusive_rate, updated_by, updated_at)
            VALUES (:projectId, :maxPending, :maxRegression, :minPassRate, :maxOpenBlockers, :maxInconclusiveRate, :updatedBy, now())
            ON CONFLICT (project_id) DO UPDATE SET
                max_pending = EXCLUDED.max_pending,
                max_regression = EXCLUDED.max_regression,
                min_pass_rate = EXCLUDED.min_pass_rate,
                max_open_blockers = EXCLUDED.max_open_blockers,
                max_inconclusive_rate = EXCLUDED.max_inconclusive_rate,
                updated_by = EXCLUDED.updated_by,
                updated_at = EXCLUDED.updated_at
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("maxPending", criteria.maxPending)
            .bind("maxRegression", criteria.maxRegression)
            .bind("minPassRate", criteria.minPassRate)
            .bind("maxOpenBlockers", criteria.maxOpenBlockers)
            .bind("maxInconclusiveRate", criteria.maxInconclusiveRate)
            .bind("updatedBy", updatedBy)
            .fetch()
            .awaitRowsUpdated()
    }

    /** 기본값으로 되돌린다. */
    suspend fun delete(projectId: Long) {
        databaseClient.sql("DELETE FROM project_release_criteria WHERE project_id = :projectId")
            .bind("projectId", projectId)
            .fetch()
            .awaitRowsUpdated()
    }

    private fun Readable.int(name: String): Int = get(name, Number::class.java)!!.toInt()
}
