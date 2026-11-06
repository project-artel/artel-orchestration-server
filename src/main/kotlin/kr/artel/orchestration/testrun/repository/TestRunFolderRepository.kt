package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunFolderEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/** 테스트 런 폴더(ARTEL-1049). */
interface TestRunFolderRepository : CoroutineCrudRepository<TestRunFolderEntity, Long> {

    fun findByProjectIdOrderByNameAscIdAsc(projectId: Long): Flow<TestRunFolderEntity>
}
