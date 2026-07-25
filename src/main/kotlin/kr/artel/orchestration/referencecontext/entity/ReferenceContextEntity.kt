package kr.artel.orchestration.referencecontext.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 참고자료에서 추출한 게임 이해 요약본(reference_context)의 R2DBC 엔티티.
 *
 * 한 문서(`sourceDocumentId` = project_document.id)의 game_context를 **타입(섹션)별로 분해**하여
 * 타입당 1행으로 저장한다(`type` + `content`). `sourceDocumentId`로 원본 문서(→ S3 object_key)를 추적한다.
 * 같은 (projectId, sourceDocumentId, type)은 유일하며, 문서 재추출 시 그 행이 교체된다(멱등).
 * `id`가 null이면 INSERT, 값이 있으면 UPDATE로 동작하며 created/updated는 Auditing이 채운다.
 */
@Table("reference_context")
data class ReferenceContextEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("source_document_id")
    val sourceDocumentId: Long,

    @Column("type")
    val type: String,

    @Column("content")
    val content: Json,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
