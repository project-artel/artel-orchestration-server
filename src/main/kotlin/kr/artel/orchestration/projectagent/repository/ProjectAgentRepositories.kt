package kr.artel.orchestration.projectagent.repository

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.projectagent.entity.ProjectAgentConversationEntity
import kr.artel.orchestration.projectagent.entity.ProjectAgentMessageEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface ProjectAgentConversationRepository : CoroutineCrudRepository<ProjectAgentConversationEntity, Long> {

    fun findByProjectIdAndAppUserIdOrderByLastMessageAtDesc(
        projectId: Long,
        appUserId: Long,
    ): Flow<ProjectAgentConversationEntity>

    suspend fun findByIdAndProjectIdAndAppUserId(
        id: Long,
        projectId: Long,
        appUserId: Long,
    ): ProjectAgentConversationEntity?

    fun findByStatus(status: String): Flow<ProjectAgentConversationEntity>

    /**
     * 입력을 받아 `RUNNING` 으로 넘긴다. 현재 status 가 [from] 안에 있을 때만 바뀌므로, 같은 대화에 입력 두 개가
     * 동시에 와도 하나만 1을 돌려받는다. [title] 은 비어 있을 때만 채우고, [pageCatalog] 는 있을 때만 바꾼다.
     *
     * @return 바뀐 행 수. 0 이면 이미 다른 입력이 대화를 잡고 있다.
     */
    @Modifying
    @Query(
        """
        UPDATE project_agent_conversation
           SET status = 'RUNNING',
               last_message_at = :now,
               title = COALESCE(title, :title),
               page_catalog = COALESCE(CAST(:pageCatalog AS jsonb), page_catalog)
         WHERE id = :id AND status IN (:from)
        """
    )
    suspend fun claim(id: Long, from: Collection<String>, now: Instant, title: String?, pageCatalog: String?): Int

    /** 제목만 바꾼다. 행 전체를 저장하면 그 사이에 바뀐 status 를 옛 값으로 덮는다. */
    @Modifying
    @Query("UPDATE project_agent_conversation SET title = :title WHERE id = :id")
    suspend fun updateTitle(id: Long, title: String): Int

    /** status 가 [from] 안에 있을 때만 [to] 로 바꾼다. 바뀐 행 수를 돌려준다. */
    @Modifying
    @Query(
        """
        UPDATE project_agent_conversation
           SET status = :to, last_message_at = :now
         WHERE id = :id AND status IN (:from)
        """
    )
    suspend fun move(id: Long, from: Collection<String>, to: String, now: Instant): Int
}

interface ProjectAgentMessageRepository : CoroutineCrudRepository<ProjectAgentMessageEntity, Long> {

    fun findByConversationIdOrderByIdAsc(conversationId: Long): Flow<ProjectAgentMessageEntity>
}
