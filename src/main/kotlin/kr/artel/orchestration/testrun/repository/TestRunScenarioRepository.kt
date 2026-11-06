package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 런↔시나리오 조합 리포지토리(코루틴).
 * - 정방향: 한 런의 시나리오들을 순서대로.
 * - 역방향: 한 시나리오를 담는 런들.
 */
interface TestRunScenarioRepository : CoroutineCrudRepository<TestRunScenarioEntity, Long> {

    fun findByTestRunIdOrderByPosition(testRunId: Long): Flow<TestRunScenarioEntity>

    fun findByTestScenarioId(testScenarioId: Long): Flow<TestRunScenarioEntity>

    suspend fun deleteByTestRunId(testRunId: Long)

    /** 시나리오 삭제 시 그 시나리오가 든 모든 런의 조합 링크를 함께 정리한다. */
    suspend fun deleteByTestScenarioId(testScenarioId: Long)

    /**
     * **이 런에만** 담긴 시나리오들(ARTEL-487). 런을 지울 때 같이 지울 수 있는 것이 정확히 이것이다.
     *
     * 런을 지워도 시나리오는 남는데, 커버리지는 런과 무관하게 프로젝트의 모든 시나리오를 센다
     * (`TestCaseRepository.findScenesOfUncovered`). 그래서 런만 지우면 어디에도 담기지 않은
     * 시나리오가 케이스를 계속 "담긴 것"으로 만들고, 사용자가 보기에는 지웠는데 숫자가 그대로다.
     *
     * 다른 런에도 든 시나리오는 **절대 고르지 않는다** — 한 런을 정리하다 남의 조합을 무너뜨리는
     * 삭제는 되돌릴 방법이 없다.
     */
    @Query(
        """
        SELECT rs.test_scenario_id FROM test_run_scenario rs
        WHERE rs.test_run_id = :testRunId
          AND NOT EXISTS (
            SELECT 1 FROM test_run_scenario other
            WHERE other.test_scenario_id = rs.test_scenario_id
              AND other.test_run_id <> :testRunId
          )
        ORDER BY rs.position ASC
        """
    )
    fun findScenarioIdsOnlyInRun(testRunId: Long): Flow<Long>
}
