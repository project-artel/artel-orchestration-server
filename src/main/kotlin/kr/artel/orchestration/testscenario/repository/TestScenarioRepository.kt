package kr.artel.orchestration.testscenario.repository

import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Mono

/**
 * TestScenario 엔티티에 대한 R2DBC 리액티브 리포지토리.
 *
 * `client_id`에 UNIQUE 제약이 있어 clientId당 시나리오는 최대 1건이며, 조회 결과는 Mono다.
 */
interface TestScenarioRepository : ReactiveCrudRepository<TestScenarioEntity, Long> {
    fun findByClientId(clientId: String): Mono<TestScenarioEntity>
}
