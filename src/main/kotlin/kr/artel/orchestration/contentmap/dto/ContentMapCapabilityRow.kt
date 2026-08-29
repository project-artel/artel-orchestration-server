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

    /**
     * 이 기능이 붙은 **씬의** capture. 근거 walk 를 지나지 않은 씬에서는 null 이다(ARTEL-642).
     *
     * 뷰의 열 이름은 그대로지만 값이 나오는 자리가 `content_map` 에서 `scene` 으로 내려갔다.
     */
    @Column("capture")
    val capture: String?,

    @Column("scene_id")
    val sceneId: Long,

    @Column("scene_name")
    val sceneName: String,

    @Column("scene_summary")
    val sceneSummary: String?,

    @Column("capability_id")
    val capabilityId: Long,

    /**
     * 재적재를 넘어 살아남는 참조 키. [capabilityId] 는 표시·조인용이고 **기억해 둘 값은 이쪽**이다.
     *
     * evidence 출신이 아니면 null 이다 — 산식의 입력인 `entry_id` 가 없다.
     */
    @Column("capability_key")
    val capabilityKey: String?,

    @Column("origin")
    val origin: String,

    @Column("verification")
    val verification: String,

    /**
     * 이 행이 왜 이 `scene` 에 있나(ARTEL-460). `placed` · `persistent-evidenced` ·
     * `persistent-unconfirmed`.
     *
     * `persistent-unconfirmed` 는 `scene` 을 넘어 살아남는 오브젝트가 여기 있다는 사실만 말한다 —
     * 그 기능이 여기서 되는지는 아직 아무도 안 봤다. `placed` 와 같은 줄로 읽으면 TC 생성기가
     * 확인된 적 없는 것을 그 `scene` 의 사실로 쓴다.
     */
    @Column("scene_presence")
    val scenePresence: String,

    /** 세 축에서 유도된 값. 아래 축들이 그 값을 낳은 이유다. */
    @Column("status")
    val status: String,

    /** 실행 가능성 — 이 조작을 실제로 할 수 있는가. */
    @Column("actionability")
    val actionability: String,

    /** 관측 가능성 — 그 결과를 볼 수 있는가. `unobservable` 은 조작 스텝으로는 쓸 수 있다. */
    @Column("observability")
    val observability: String,

    /** 적용 가능성 — 이 빌드에 이 규칙이 적용되는가. `not-applicable` 은 아예 쓸 수 없다. */
    @Column("applicability")
    val applicability: String,

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

    /**
     * 한 번인지 끝날 때까지인지. `false` 가 기본이라 이 칸을 모르는 소비자도 기존과 같이 읽는다.
     */
    @Column("repeat_until_done")
    val repeatUntilDone: Boolean,

    @Column("hint_action_method")
    val hintActionMethod: String?,

    @Column("hint_action_params")
    val hintActionParams: Json?,

    /** evidence 출신이 아니면 아래 여섯은 전부 null 이다. 그것이 정직한 상태다. */
    @Column("entry_id")
    val entryId: String?,

    /** [entryId] 만으로는 근거 주소가 메서드까지다. 갈래를 짚으려면 둘이 함께 있어야 한다. */
    @Column("branch_offset")
    val branchOffset: Int?,

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
