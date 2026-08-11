package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.testrun.entity.TestRunMessageEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 작성 챗봇 대화 메시지 리포지토리(코루틴).
 *
 * 대화는 사용자별 프라이빗이므로 조회는 (testRunId, appUserId)로 스코프하여 시간순으로 반환한다.
 */
interface TestRunMessageRepository : CoroutineCrudRepository<TestRunMessageEntity, Long> {
    fun findByTestRunIdAndAppUserIdOrderByCreatedAtAsc(
        testRunId: Long,
        appUserId: Long
    ): Flow<TestRunMessageEntity>
}
