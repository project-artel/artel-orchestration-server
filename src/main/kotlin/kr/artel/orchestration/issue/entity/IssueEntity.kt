package kr.artel.orchestration.issue.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * QA 실행 중 Agent가 보고한 이슈 한 건.
 *
 * [detail]에는 Agent가 보낸 payload 전체가 JSONB로 담긴다(qa_log.payload와 동일 방식).
 * created/updated는 컬럼 기본값 대신 서비스에서 stamp한다 — R2DBC는 insert 후 기본값 컬럼을
 * 다시 읽어오지 않아, 그러지 않으면 저장된 엔티티의 createdAt이 null로 나온다(QaLogEntity와 동일).
 */
@Table("issue")
data class IssueEntity(
    @Id val id: Long? = null,
    @Column("qa_try_id") val qaTryId: Long,
    @Column("message_id") val messageId: String? = null,
    @Column("correlation_id") val correlationId: String? = null,
    val severity: String,
    val title: String,
    val detail: Json = Json.of("{}"),
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
)
