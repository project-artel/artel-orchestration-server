package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column

/**
 * **누를 것은 없고 볼 것은 있는 기능** 한 줄(ARTEL-681).
 *
 * `v_content_map_capability` 는 `not-a-step` 을 거르므로 여기 오지 않는다. 그런데 그것들이
 * 게임이 스스로 하는 일 — 화면을 열면 무엇이 보이나, 값이 이러하면 무엇이 보이나 — 이고,
 * QA 가 가장 먼저 보는 자리다. 지도 31에서 137개가 있고 케이스는 0건이었다.
 *
 * 칸은 [ContentMapCapabilityRow] 와 같고 [triggerRoot] 하나가 더 있다. 조작 행과 같은 길로
 * 흘러야 아래(효과 읽기·갈래 펴기·합치기)가 두 벌이 되지 않는다.
 *
 * 효과(`then`)는 여기 없다. 기능 하나에 여러 개라 조인하면 행이 곱해지므로
 * [kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository] 로 따로 읽는다.
 */
data class ContentMapObservationRow(
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

    /**
     * **무엇이 이 일을 일으켰나.** `call_path` 첫 마디의 메서드 이름이다(ARTEL-681).
     *
     * `Start`·`Awake` 면 화면을 열 때, `Update` 면 머무르는 동안, `OnMouseEnter`·`OnDrag` 면
     * 조작의 부수 효과다. **유니티가 정한 이름이라** 개발자가 무엇을 어떻게 부르든 흔들리지 않는다 —
     * 메서드 이름으로 기능을 짓는 것과는 다른 이야기다. 저것은 의도를 주장하고 이것은 호출 자리를
     * 가리킨다.
     */
    @org.springframework.data.relational.core.mapping.Column("trigger_root")
    val triggerRoot: String? = null,
)

/**
 * 조작 행과 같은 모양으로 내놓는다(ARTEL-681).
 *
 * 아래(효과 읽기·갈래 펴기·합치기)가 두 벌이 되지 않게 하려는 것뿐이다. [ContentMapObservationRow.triggerRoot]
 * 는 부르는 쪽이 따로 들고 간다 — 문구를 지을 때만 쓴다.
 */
fun ContentMapObservationRow.asCapabilityRow(): ContentMapCapabilityRow = ContentMapCapabilityRow(
    contentMapId = contentMapId,
    capture = capture,
    sceneId = sceneId,
    sceneName = sceneName,
    sceneSummary = sceneSummary,
    capabilityId = capabilityId,
    capabilityKey = capabilityKey,
    origin = origin,
    verification = verification,
    // **등급은 케이스로서의 등급이다**(ARTEL-681). 지도는 이 행을 `not-a-step` 으로 적는데 그것은
    // *"누를 것이 없다"* 는 말이지 *"검증할 수 없다"* 가 아니다. 눈에 보이는 효과가 있는 것만
    // 여기까지 오므로(`keptAsObservation`), 케이스로서는 돌릴 수 있다 — 보면 된다.
    status = "runnable",
    actionability = actionability,
    observability = observability,
    applicability = applicability,
    summary = summary,
    givenText = givenText,
    controlSelector = controlSelector,
    controlPath = controlPath,
    controlLabel = controlLabel,
    interaction = interaction,
    inputKey = inputKey,
    inputPhase = inputPhase,
    repeatUntilDone = repeatUntilDone,
    hintActionMethod = hintActionMethod,
    hintActionParams = hintActionParams,
    entryId = entryId,
    branchOffset = branchOffset,
    recordKind = recordKind,
    triggerKind = triggerKind,
    analysisConfidence = analysisConfidence,
    conditionTree = conditionTree,
    gaps = gaps,
)
