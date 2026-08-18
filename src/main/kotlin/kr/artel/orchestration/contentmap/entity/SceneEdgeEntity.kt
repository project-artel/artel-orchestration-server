package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * scene_edge — 씬 전이. 정적 후보 + 런타임 검증.
 *
 * `effects.kind='scene'` 으로 **정적으로 채워서 출발한다.** 빈 테이블로 시작하지 않는다.
 *
 * [verifiedAt] 이 null 인 간선이 곧 커버리지 구멍이고, QA agent 에게 다음에 무엇을 시도할지
 * 알려주는 유일한 신호다. [ScreenTransitionEntity] 에서 파생시키면 이 신호가 사라진다 — 아직
 * 못 가본 전이는 관측에 없기 때문이다.
 */
@Table("scene_edge")
data class SceneEdgeEntity(
    @Id
    val id: Long? = null,

    @Column("from_scene_id")
    val fromSceneId: Long,

    /** 이름으로 둔다. **아직 순회하지 못한 씬으로 가는 전이가 있다.** */
    @Column("to_scene_name")
    val toSceneName: String,

    /** 그 씬을 순회했으면 채워진다. */
    @Column("to_scene_id")
    val toSceneId: Long? = null,

    @Column("capability_id")
    val capabilityId: Long? = null,

    /** 같은 컨트롤이 조건으로 갈려 서로 다른 씬으로 가므로 조건을 함께 담는다. */
    @Column("given_text")
    val givenText: String? = null,

    /** [EdgeSource] 중 하나. `runtime` 은 정적 분석이 놓친 전이다. */
    @Column("source")
    val source: String,

    @Column("verified_at")
    val verifiedAt: Instant? = null,

    @Column("observed_count")
    val observedCount: Int = 0,

    @Column("first_observed_transition_id")
    val firstObservedTransitionId: Long? = null,
)
