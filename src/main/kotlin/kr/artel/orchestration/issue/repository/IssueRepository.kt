package kr.artel.orchestration.issue.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.issue.entity.IssueEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface IssueRepository : CoroutineCrudRepository<IssueEntity, Long> {
    /** 재전송 프레임을 원래 행으로 되돌리는 멱등 조회. 라우터가 messageId(UUID)를 보장한다. */
    suspend fun findByQaTryIdAndMessageId(qaTryId: Long, messageId: String): IssueEntity?

    /**
     * 한 실행이 남긴 이슈, 최신순 한 페이지.
     *
     * 선택 커서를 `(:beforeId IS NULL OR ...)`로 흡수하는 것은 `QaLogRepository.findPage`와 같다.
     * 커서 유무로 메서드를 쪼개면 같은 정렬·같은 상한을 두 벌 관리하게 된다.
     *
     * 접근 판정은 여기 없다. 서비스가 `QaTryRepository.findAccessibleById`로 먼저 실행을 확인한 뒤
     * 부르므로, 이 질의가 멤버십 조인을 한 번 더 할 이유가 없다.
     */
    @Query(
        """
        SELECT * FROM issue
        WHERE qa_try_id = :qaTryId
          AND (:beforeId IS NULL OR id < :beforeId)
        ORDER BY id DESC
        LIMIT :limit
        """
    )
    fun findPageByQaTry(qaTryId: Long, beforeId: Long?, limit: Int): Flow<IssueEntity>

    /**
     * 한 프로젝트의 모든 실행이 남긴 이슈, 최신순 한 페이지.
     *
     * `project_member` 조인이 **없다**. 비참여자를 조인으로 걸러내면 200 + 빈 목록이 나가고,
     * 화면은 그것을 "이슈가 없는 프로젝트"와 구분할 수 없다. 멤버십은 서비스가
     * `ProjectAccessService`로 먼저 판정하고, 아니면 404를 준다.
     */
    @Query(
        """
        SELECT i.* FROM issue i
        JOIN qa_try qt ON qt.id = i.qa_try_id
        JOIN test_scenario ts ON ts.id = qt.test_scenario_id
        WHERE ts.project_id = :projectId
          AND (:status IS NULL OR i.status = :status)
          AND (:severity IS NULL OR i.severity = :severity)
          AND (:beforeId IS NULL OR i.id < :beforeId)
        ORDER BY i.id DESC
        LIMIT :limit
        """
    )
    fun findPageByProject(
        projectId: Long,
        status: String?,
        severity: String?,
        beforeId: Long?,
        limit: Int
    ): Flow<IssueEntity>

    /**
     * 해결 표시. `@Modifying`이 있어야 영향 행 수가 돌아온다(`QaTryRepository.transition`과 동일).
     *
     * `WHERE status = 'OPEN'`이라 이미 해결된 이슈에는 0행이 걸리고, 서비스는 그것을 멱등 성공으로
     * 읽는다 — 되돌릴 수 없는 전이가 아니므로 재요청을 409로 볼 이유가 없다. 이 조건이 없다면
     * 재요청이 `resolved_at`을 나중 시각으로 덮어써 "언제 처리했나"를 잃는다.
     */
    @Modifying
    @Query(
        """
        UPDATE issue
        SET status = 'RESOLVED', resolved_at = :resolvedAt, resolved_by = :resolvedBy,
            updated_at = :updatedAt
        WHERE id = :id AND status = 'OPEN'
        """
    )
    suspend fun resolve(id: Long, resolvedAt: Instant, resolvedBy: Long, updatedAt: Instant): Int

    /** 해결 취소. 처리 흔적을 지워 신규 이슈와 같은 모양으로 되돌린다. */
    @Modifying
    @Query(
        """
        UPDATE issue
        SET status = 'OPEN', resolved_at = NULL, resolved_by = NULL, updated_at = :updatedAt
        WHERE id = :id AND status = 'RESOLVED'
        """
    )
    suspend fun reopen(id: Long, updatedAt: Instant): Int
}
