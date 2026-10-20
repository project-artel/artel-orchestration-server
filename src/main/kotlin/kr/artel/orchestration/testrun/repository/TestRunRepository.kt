package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

/** TestRun 조회 리포지토리(코루틴). 프로젝트 스코프 목록. */
interface TestRunRepository : CoroutineCrudRepository<TestRunEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestRunEntity>

    /** 프로젝트 TR 마다 담은 시나리오 수(ARTEL-1048). 시나리오가 없는 TR 은 행이 없다. */
    @Query(
        """
        SELECT trs.test_run_id, count(*) AS scenarios
          FROM test_run_scenario trs
          JOIN test_run tr ON tr.id = trs.test_run_id
         WHERE tr.project_id = :projectId
         GROUP BY trs.test_run_id
        """
    )
    fun countScenariosByProject(projectId: Long): Flow<RunScenarioCount>

    /**
     * 프로젝트 TR 마다 마지막 QA 실행과 그 시나리오 결과 수(ARTEL-1048). QA 를 한 번도 돌리지 않은 TR 은 행이 없다.
     * 결과는 그 실행의 try 상태로 센다 — 통과는 COMPLETED, 실패는 FAILED 다.
     */
    @Query(
        """
        SELECT DISTINCT ON (qr.test_run_id)
               qr.test_run_id, qr.id AS qa_run_id, qr.status, qr.started_at,
               (SELECT count(*) FROM qa_try t WHERE t.qa_run_id = qr.id AND t.status = 'COMPLETED') AS passed,
               (SELECT count(*) FROM qa_try t WHERE t.qa_run_id = qr.id AND t.status = 'FAILED') AS failed,
               (SELECT count(*) FROM qa_try t WHERE t.qa_run_id = qr.id) AS total
          FROM qa_run qr
          JOIN test_run tr ON tr.id = qr.test_run_id
         WHERE tr.project_id = :projectId
         ORDER BY qr.test_run_id, qr.started_at DESC, qr.id DESC
        """
    )
    fun findLastQaRunsByProject(projectId: Long): Flow<RunLastQaRow>
}

data class RunScenarioCount(val testRunId: Long, val scenarios: Long)

data class RunLastQaRow(
    val testRunId: Long,
    val qaRunId: Long,
    val status: String,
    val startedAt: Instant,
    val passed: Long,
    val failed: Long,
    val total: Long,
)
