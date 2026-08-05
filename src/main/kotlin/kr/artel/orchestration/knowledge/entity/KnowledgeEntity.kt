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

    /**
     * 이 항목을 마지막으로 고친/지운 QA 런(ARTEL-188). 사람이나 문서 추출 경로가 만든 항목은 null이다.
     * 지우고 고치는 주체가 Agent라, 남기지 않으면 지식창고가 잘못 깎여도 알아챌 방법이 없다.
     * [deletedByQaTryId]는 되살린 뒤에도 지우지 않는다 — "직전에 누가 지웠었나"가 곧 감사 기록이다.
     */
    @Column("updated_by_qa_try_id")
    val updatedByQaTryId: Long? = null,

    @Column("deleted_by_qa_try_id")
    val deletedByQaTryId: Long? = null,

    /**
     * 현재 content의 버전(ARTEL-255). `knowledge_event`의 최대 content 버전과 같아야 한다 —
     * 그 불변식이 깨지면 쓰기 버그다.
     *
     * **content가 바뀔 때만 오른다**(CREATE/UPDATE). 소프트삭제와 복원은 본문을 건드리지 않으므로
     * 올리지 않는다. 여기서 content는 [tag]/[summary]/[description] 셋 전부다 — 이벤트의 `after`가
     * 셋을 통째로 스냅샷하므로, tag만 바뀐 변경에 버전을 안 올리면 그 버전의 스냅샷이 이 행과
     * 어긋난다. (임베딩 무효화 판정은 이와 별개로 [summary]/[description]만 본다.)
     *
     * 이 마이그레이션 이전 행은 기본값 1이고 이벤트가 없다. 백필하지 않는다.
     *
     * `replaces_id` 컬럼은 일부러 매핑하지 않는다. 이번 범위에서 채우지 않는 자리라, 엔티티에
     * 실으면 "쓰지 않는다"는 결정이 코드에서 보이지 않게 된다. R2DBC는 매핑 안 된 컬럼을
     * 건드리지 않으므로 UPDATE가 값을 지우지도 않는다.
     */
    @Column("version")
    val version: Int = 1,
)
