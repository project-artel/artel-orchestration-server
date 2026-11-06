package kr.artel.orchestration.contentmap.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * screen_transition — 화면 전이. **관측만.**
 *
 * 정적으로 만들지 않는다. 추측을 넣으면 "실제로 어떻게 흘렀나"가 오염된다. 같은 씬 안 전이(팝업
 * 열림)가 있어 [SceneEdgeEntity] 로 대체할 수도 없다.
 *
 * 두 그래프가 답하는 질문이 다르다.
 * - [SceneEdgeEntity] — "이 게임의 화면 구조가 어떻게 생겼나". 아직 안 가본 곳도 나온다
 * - 이 표 — "실제로 어떻게 흘렀나". 재현 경로와 결함 맥락
 *
 * [capabilityId] 가 null 이면 자동 전이 — TC 가 지시할 수 없는 것이다.
 */
@Table("screen_transition")
data class ScreenTransitionEntity(
    @Id
    val id: Long? = null,

    @Column("from_screen_id")
    val fromScreenId: Long,

    @Column("to_screen_id")
    val toScreenId: Long,

    @Column("capability_id")
    val capabilityId: Long? = null,

    /** [TransitionKind] 중 하나. */
    @Column("kind")
    val kind: String,

    /** 씬 경계를 넘었나. 넘었으면 대응 [SceneEdgeEntity] 를 검증됨으로 올린다. */
    @Column("crosses_scene")
    val crossesScene: Boolean,

    @Column("observed_count")
    val observedCount: Int = 0,

    @Column("first_seen_qa_run_id")
    val firstSeenQaRunId: Long? = null,
)
