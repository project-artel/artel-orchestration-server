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
 * [parseStatus]는 추출 파이프라인의 진행 상태다. 업로드 직후 PENDING이고, Agent가 추출한
 * game_context가 knowledge로 적재되면 EXTRACTED로 갱신된다.
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
    val parseStatus: String = ParseStatus.PENDING.name,

    /**
     * 원본 파일의 SHA-256(hex). 업로드 확정(register) 때 Orche가 S3 원본을 스트리밍하며 계산한다.
     * 같은 프로젝트에 동일 hash가 이미 있으면 중복으로 보고 등록을 거부한다(프로젝트 단위 dedup).
     */
    @Column("content_hash")
    val contentHash: String? = null
)

/**
 * 참고자료 추출 진행 상태(업로드 → 추출 파이프라인).
 * - [PENDING]: 업로드만 된 상태(아직 추출 전).
 * - [EXTRACTING]: Agent /extract 호출~적재가 진행 중.
 * - [EXTRACTED]: game_context가 knowledge로 적재 완료.
 * - [FAILED]: presign/추출/적재 중 실패(원본은 남으므로 재추출로 복구 가능).
 */
enum class ParseStatus {
    PENDING,
    EXTRACTING,
    EXTRACTED,
    FAILED
}
