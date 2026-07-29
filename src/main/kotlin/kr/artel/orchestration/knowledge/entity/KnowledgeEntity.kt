package kr.artel.orchestration.knowledge.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 통합 지식창고(knowledge) 한 항목의 R2DBC 엔티티. reference_context를 대체한다.
 *
 * source(DOCS/QA)와 tag로 성질을 구분하고, 원본 추적용 메타는 컬럼으로 승격했다:
 * - [sourceId]: DOCS면 project_document.id, QA면 qa_try.id (nullable — 있을 때만).
 * - [contentHash]: DOCS 파일 hash (nullable, 멱등키 아님 — 저장만).
 * summary/description는 Agent가 만든 요약/설명으로 TEXT(String) 저장.
 * 강한 유니크 제약은 두지 않는다(팀 결정: DB 제약 약하게). id가 null이면 INSERT.
 */
@Table("knowledge")
data class KnowledgeEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("source")
    val source: String,

    @Column("source_id")
    val sourceId: Long? = null,

    @Column("content_hash")
    val contentHash: String? = null,

    @Column("tag")
    val tag: String,

    @Column("summary")
    val summary: String,

    @Column("description")
    val description: String,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,

    /**
     * 소프트삭제 표식(ARTEL-188). null이면 살아있는 항목이다.
     * 하드 삭제하지 않는 이유는 지우는 주체가 Agent라 오판이 곧 데이터 소실이 되기 때문이고,
     * 되살리기는 이 값을 null로 되돌리는 것만으로 되어야 한다.
     */
    @Column("deleted_at")
    val deletedAt: Instant? = null,
)
