package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 근거 문서와 함께 온 씬 대표 이미지, 또는 이미지를 만들지 못한 이유. */
@Table("content_map_scene_capture")
data class ContentMapSceneCaptureEntity(
    @Id val id: Long? = null,
    @Column("document_id") val documentId: Long,
    @Column("scene_name") val sceneName: String,
    @Column("object_key") val objectKey: String? = null,
    @Column("content_type") val contentType: String? = null,
    val width: Int? = null,
    val height: Int? = null,
    @Column("failure_code") val failureCode: String? = null,
    @Column("captured_at") val capturedAt: Instant? = null,
)
