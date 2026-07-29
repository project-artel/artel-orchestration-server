package kr.artel.orchestration.testrun.repository

import kr.artel.orchestration.testrun.entity.TestRunScenarioEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * 런↔시나리오 조합 리포지토리.
 * - 정방향: 한 런의 시나리오들을 순서대로.
 * - 역방향: 한 시나리오를 담는 런들.
 */
interface TestRunScenarioRepository : ReactiveCrudRepository<TestRunScenarioEntity, Long> {

    fun findByTestRunIdOrderByPosition(testRunId: Long): Flux<TestRunScenarioEntity>

    fun findByTestScenarioId(testScenarioId: Long): Flux<TestRunScenarioEntity>

    fun deleteByTestRunId(testRunId: Long): Mono<Void>
}
