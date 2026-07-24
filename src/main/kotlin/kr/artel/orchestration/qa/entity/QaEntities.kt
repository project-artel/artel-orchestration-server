package kr.artel.orchestration.qa.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("qa_try")
data class QaTryEntity(
    @Id val id: Long? = null,
    @Column("test_scenario_id") val testScenarioId: Long,
    @Column("game_instance_id") val gameInstanceId: Long,
    @Column("started_by") val startedBy: Long,
    @Column("agent_session_id") val agentSessionId: String? = null,
    val status: String,
    @Column("started_at") val startedAt: Instant,
    @Column("completed_at") val completedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
)

@Table("qa_log")
data class QaLogEntity(
    @Id val id: Long? = null,
    @Column("qa_try_id") val qaTryId: Long,
    @Column("message_id") val messageId: String? = null,
    @Column("correlation_id") val correlationId: String? = null,
    val direction: String,
    val type: String,
    val message: String? = null,
    val payload: Json = Json.of("{}"),
    @Column("created_at") val createdAt: Instant? = null
)
