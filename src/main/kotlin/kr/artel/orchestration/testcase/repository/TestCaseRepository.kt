package kr.artel.orchestration.testcase.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * TestCase 조회 리포지토리(코루틴). 조회는 프로젝트 스코프이며, 대분류(category)·검증상태로 선택 필터한다.
 * 멱등 적재(Agent 재전송 중복 방지)를 위해 (project_id, category, title) 조회도 제공한다.
 */
interface TestCaseRepository : CoroutineCrudRepository<TestCaseEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestCaseEntity>

    fun findByProjectIdAndCategoryOrderByIdDesc(projectId: Long, category: String): Flow<TestCaseEntity>

    fun findByProjectIdAndVerificationStatusOrderByIdDesc(
        projectId: Long,
        verificationStatus: String
    ): Flow<TestCaseEntity>

    suspend fun findByProjectIdAndCategoryAndTitle(projectId: Long, category: String, title: String): TestCaseEntity?
}
