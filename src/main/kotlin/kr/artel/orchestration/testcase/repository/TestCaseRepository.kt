package kr.artel.orchestration.testcase.repository

import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * TestCase 조회 리포지토리. 조회는 프로젝트 스코프이며, 대분류(category)·검증상태로 선택 필터한다.
 * 멱등 적재(Agent 재전송 중복 방지)를 위해 (project_id, category, title) 존재 확인도 제공한다.
 */
interface TestCaseRepository : ReactiveCrudRepository<TestCaseEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flux<TestCaseEntity>

    fun findByProjectIdAndCategoryOrderByIdDesc(projectId: Long, category: String): Flux<TestCaseEntity>

    fun findByProjectIdAndVerificationStatusOrderByIdDesc(
        projectId: Long,
        verificationStatus: String
    ): Flux<TestCaseEntity>

    fun findByProjectIdAndCategoryAndTitle(projectId: Long, category: String, title: String): Mono<TestCaseEntity>
}
