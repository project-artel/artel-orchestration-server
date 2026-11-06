package kr.artel.orchestration.projectagent.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 대화가 어디까지 왔는지. DB CHECK 와 같은 네 값이다. */
object ProjectAgentStatus {
    const val IDLE = "IDLE"
    const val RUNNING = "RUNNING"
    const val AWAITING_TOOL = "AWAITING_TOOL"
    const val ERROR = "ERROR"

    val ALL = listOf(IDLE, RUNNING, AWAITING_TOOL, ERROR)
}

/** 메시지의 출처. DB CHECK 와 같은 다섯 값이다. */
object ProjectAgentRole {
    const val USER = "USER"
    const val ASSISTANT = "ASSISTANT"
    const val TOOL_RESULT = "TOOL_RESULT"
    const val EVENT = "EVENT"
    const val NOTICE = "NOTICE"
}

/** project agent 대화 한 건. 한 프로젝트 안에서 한 사용자의 개인 스레드다. */
@Table("project_agent_conversation")
data class ProjectAgentConversationEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("app_user_id")
    val appUserId: Long,

    @Column("title")
    val title: String? = null,

    @Column("status")
    val status: String = ProjectAgentStatus.IDLE,

    /** browser 가 만든 page catalog. 서버는 읽지 않고 step 호출에 그대로 실어 보낸다. */
    @Column("page_catalog")
    val pageCatalog: Json? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("last_message_at")
    val lastMessageAt: Instant,
)

/** 대화에 쌓이는 메시지 한 건. `payload` 의 모양은 `role` 마다 다르다. */
@Table("project_agent_message")
data class ProjectAgentMessageEntity(
    @Id
    val id: Long? = null,

    @Column("conversation_id")
    val conversationId: Long,

    @Column("role")
    val role: String,

    @Column("content")
    val content: String = "",

    @Column("payload")
    val payload: Json? = null,

    @Column("created_at")
    val createdAt: Instant,
)
