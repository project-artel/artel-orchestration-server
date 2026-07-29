package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** TestRun 조회 리포지토리(코루틴). 프로젝트 스코프 목록. */
interface TestRunRepository : CoroutineCrudRepository<TestRunEntity, Long> {

    fun findByProjectIdOrderByIdDesc(projectId: Long): Flow<TestRunEntity>
}
