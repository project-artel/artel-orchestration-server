package kr.artel.orchestration.testscenario.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testscenario.entity.TestScenarioCaseEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 시나리오↔케이스 조합 리포지토리(코루틴).
 * - 정방향: 한 시나리오의 케이스들을 순서대로.
 * - 역방향: 한 케이스를 쓰는 조합들(회귀 영향분석) — junction의 핵심 이점.
 * 재구성(재정렬)은 서비스가 delete 후 재삽입으로 처리한다.
 */
interface TestScenarioCaseRepository : CoroutineCrudRepository<TestScenarioCaseEntity, Long> {

    fun findByTestScenarioIdOrderByPosition(testScenarioId: Long): Flow<TestScenarioCaseEntity>

    fun findByTestCaseId(testCaseId: Long): Flow<TestScenarioCaseEntity>

    suspend fun deleteByTestScenarioId(testScenarioId: Long)
}
