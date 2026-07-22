package kr.artel.orchestration.testscenario.repository

import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux

/**
 * TestScenario 엔티티에 대한 R2DBC 리액티브 리포지토리.
 *
 * 프로젝트당 시나리오가 여러 개일 수 있어 `findByProjectId`는 Flux를 반환한다.
 */
interface TestScenarioRepository : ReactiveCrudRepository<TestScenarioEntity, Long> {
    fun findByProjectId(projectId: Long): Flux<TestScenarioEntity>
}
