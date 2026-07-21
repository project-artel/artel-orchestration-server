package kr.artel.orchestration.project.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 프로젝트 기획서 한 버전. 새로 올리면 교체하지 않고 [version]을 올려 쌓으므로,
 * 한 프로젝트에 여러 행이 남고 가장 큰 버전이 현재 기획서다.
 *
 * 원본 바이트는 S3에 있고 여기에는 메타데이터만 둔다. [objectKey]는 외부에 노출하지 않는다.
 * [parseStatus]는 이후 파싱 파이프라인이 쓸 자리이며 지금은 PENDING에서 움직이지 않는다.
 */
@Table("project_document")
data class ProjectDocumentEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("version")
    val version: Int,

    @Column("object_key")
    val objectKey: String,

    @Column("file_name")
    val fileName: String,

    @Column("content_type")
    val contentType: String,

    @Column("size_bytes")
    val sizeBytes: Long,

    @Column("uploaded_by")
    val uploadedBy: Long,

    @Column("uploaded_at")
    val uploadedAt: Instant,

    @Column("parse_status")
    val parseStatus: String = ParseStatus.PENDING.name
)

/** 기획서 파싱 진행 상태. 파서가 아직 없어 지금은 [PENDING]만 기록된다. */
enum class ParseStatus {
    PENDING
}
