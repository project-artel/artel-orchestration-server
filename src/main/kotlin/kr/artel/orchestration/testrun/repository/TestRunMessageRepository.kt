package kr.artel.orchestration.testrun.repository

import kotlinx.coroutines.flow.Flow
import org.springframework.data.r2dbc.repository.Query
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

    /**
     * **이 프로젝트에서 답한 것 전부**(ARTEL-676).
     *
     * 답을 판 단위로만 읽으면 다음 판에서 같은 것을 또 묻는다 — `Map_scene→TurnBattleScene 을
     * 어떻게 가나요` 는 지도가 바뀌지 않는 한 답이 같은데도 판마다 다시 나갔다. 첫 판에 많이
     * 묻고 그 뒤로 줄어드는 것이 이 되묻기의 전제라, 답은 프로젝트에 쌓여야 한다.
     *
     * 사람 단위로 좁히지 않는다. 같은 프로젝트를 여럿이 저작할 때 한 사람이 답한 것을 다른
     * 사람에게 다시 묻는 것은, 그 답이 게임에 대한 사실이라 뜻이 없다.
     */
    @Query(
        """
        SELECT m.* FROM test_run_message m
        JOIN test_run r ON r.id = m.test_run_id
        WHERE r.project_id = :projectId
        ORDER BY m.created_at ASC
        """
    )
    fun findAnsweredInProject(projectId: Long): Flow<TestRunMessageEntity>
}
