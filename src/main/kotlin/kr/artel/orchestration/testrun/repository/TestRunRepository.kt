package kr.artel.orchestration.testrun.repository

import kr.artel.orchestration.testrun.entity.TestRunEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux

/** TestRun 조회 리포지토리. 프로젝트 스코프 목록. */
interface TestRunRepository : ReactiveCrudRepository<TestRunEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flux<TestRunEntity>
}
