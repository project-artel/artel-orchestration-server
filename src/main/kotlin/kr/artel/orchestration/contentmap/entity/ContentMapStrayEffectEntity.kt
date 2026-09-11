package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * 씬 귀속에 실패한 unplaced 타입의 상태 변경 한 줄. 왜 남기는지는 V95 마이그레이션에 있다 —
 * 걷기의 "이 값이 움직이나" 판정 전용이고, 조작 근거로는 쓰지 않는다.
 */
@Table("content_map_stray_effect")
data class ContentMapStrayEffectEntity(
    @Id val id: Long? = null,
    @Column("content_map_id") val contentMapId: Long,
    val kind: String,
    val target: String,
    val detail: String? = null,
    @Column("source_type") val sourceType: String? = null,
)
