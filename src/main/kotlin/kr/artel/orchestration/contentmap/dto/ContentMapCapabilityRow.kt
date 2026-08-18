package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column

/**
 * `v_content_map_capability` 한 줄. **TC 생성기가 읽는 유일한 창구.**
 *
 * 읽는 곳을 한 군데로 못 박지 않으면 TC 생성기가 근거 문서를 직접 보게 되고, 그 순간 "TC 입력은
 * content_map 단독"이라는 계약이 무너진다.
 *
 * 효과(`then`)는 여기 없다. 기능 하나에 여러 개라 조인하면 행이 곱해지므로
 * [kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository] 로 따로 읽는다.
 *
 * 뷰가 이미 거른 것: `not-a-step`(조작이 없어 단독 명세가 될 수 없다), `merged_into` 가 찍힌 행.
 */
data class ContentMapCapabilityRow(
    @Column("content_map_id")
    val contentMapId: Long,

    @Column("capture")
    val capture: String,

    @Column("scene_id")
    val sceneId: Long,

    @Column("scene_name")
    val sceneName: String,

    @Column("scene_summary")
    val sceneSummary: String?,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("origin")
    val origin: String,

    @Column("verification")
    val verification: String,

    @Column("status")
    val status: String,

    @Column("summary")
    val summary: String,

    @Column("given_text")
    val givenText: String?,

    @Column("control_selector")
    val controlSelector: String?,

    @Column("control_path")
    val controlPath: String?,

    @Column("control_label")
    val controlLabel: String?,

    @Column("interaction")
    val interaction: String,

    @Column("input_key")
    val inputKey: String?,

    @Column("input_phase")
    val inputPhase: String?,

    @Column("hint_action_method")
    val hintActionMethod: String?,

    @Column("hint_action_params")
    val hintActionParams: Json?,

    /** evidence 출신이 아니면 아래 다섯은 전부 null 이다. 그것이 정직한 상태다. */
    @Column("entry_id")
    val entryId: String?,

    @Column("record_kind")
    val recordKind: String?,

    @Column("trigger_kind")
    val triggerKind: String?,

    @Column("analysis_confidence")
    val analysisConfidence: String?,

    /** **평탄화 금지.** `either` 가 `every` 로 뒤집히면 "둘 중 하나"가 "둘 다"가 된다. */
    @Column("condition_tree")
    val conditionTree: Json?,

    @Column("gaps")
    val gaps: Json?,
)
