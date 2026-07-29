package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
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
}
