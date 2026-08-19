package kr.artel.orchestration.contentmap.dto

import org.springframework.data.relational.core.mapping.Column

/**
 * `v_spec_gap` 한 줄. 명세의 어느 칸을 못 채웠나.
 *
 * **QA 결함이 아니라 개발 우선순위 신호다.** `then-missing` 이 많으면 수집기(SDK)를 고칠 차례이고,
 * `given-subject-unknown` 이 많으면 조건 분석기의 주어 추적이 약한 것이다. agent 가 메울 수 있는
 * 것이 아니다 — 근거에 없는 것을 메우면 그럴듯한 거짓말이 된다.
 *
 * [reason] 이 null 이면 그 기능은 세 칸이 다 찼다는 뜻이다.
 */
data class SpecGapRow(
    @Column("content_map_id")
    val contentMapId: Long,

    @Column("scene_id")
    val sceneId: Long,

    @Column("capability_id")
    val capabilityId: Long,

    /** [kr.artel.orchestration.contentmap.entity.SpecGapReason] 중 하나이거나 null. */
    @Column("reason")
    val reason: String?,
)
