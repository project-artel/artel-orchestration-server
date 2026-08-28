package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 이 씬에서 collection 으로 판정된 경로 family 하나 (ARTEL-649).
 *
 * 한 번 관측되면 남는다 — 인스턴스가 하나뿐인 순간이 와도 collection 이었다는 사실은 취소되지
 * 않는다. 근거는 `V58__drop_collection_families_from_discriminator.sql` 과
 * `ScreenObservationService.rememberedCollectionFamilies`.
 */
@Table("scene_collection_family")
data class SceneCollectionFamilyEntity(
    @Id
    val id: Long? = null,

    @Column("scene_id")
    val sceneId: Long,

    /** 형제 인덱스를 지운 경로. `CombineSystem[7]/CombineZone[1]` → `CombineSystem/CombineZone`. */
    @Column("family")
    val family: String,

    @Column("first_observed_at")
    val firstObservedAt: Instant? = null,
)
