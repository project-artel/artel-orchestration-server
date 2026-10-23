package kr.artel.orchestration.testrun.repository

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.testrun.entity.TestRunMapEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/**
 * 테스트 런 맵 저장(ARTEL-1244). 두 쓰기 모두 **읽은 버전이 그대로일 때만** 바뀐다. 바뀐 행 수가 0이면 그사이
 * 다른 사람이 먼저 저장한 것이다.
 */
interface TestRunMapRepository : CoroutineCrudRepository<TestRunMapEntity, Long> {

    /** 아직 맵이 없는 런의 첫 저장. 동시에 둘이 처음 저장하면 하나만 들어간다. */
    @Modifying
    @Query(
        """
        INSERT INTO test_run_map (test_run_id, layout, annotations, version, updated_by, updated_at)
        VALUES (:testRunId, :layout, :annotations, 1, :updatedBy, :updatedAt)
        ON CONFLICT (test_run_id) DO NOTHING
        """
    )
    suspend fun insertFirst(testRunId: Long, layout: Json, annotations: Json, updatedBy: Long, updatedAt: Instant): Int

    @Modifying
    @Query(
        """
        UPDATE test_run_map
        SET layout = :layout, annotations = :annotations, version = version + 1, updated_by = :updatedBy, updated_at = :updatedAt
        WHERE test_run_id = :testRunId AND version = :version
        """
    )
    suspend fun updateIfVersion(
        testRunId: Long,
        version: Int,
        layout: Json,
        annotations: Json,
        updatedBy: Long,
        updatedAt: Instant,
    ): Int
}
