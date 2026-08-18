package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * capability_evidence — evidence 출신 기능만 갖는 것.
 *
 * 서브테이블로 뗀 이유: 이 컬럼들을 [CapabilityEntity] 에 두면 QA 가 관측으로 배운 기능이
 * `NOT NULL` 에 막혀 더미값을 넣게 되고, **그 순간 두 종류가 구분 불가능해진다.**
 *
 * [analysisConfidence] 는 IL 분석기의 **자기 확신도**지 실행 확인이 아니다. 실행 확인은
 * [CapabilityEntity.verification] 이고, 이름이 겹쳐 혼동되던 자리라 여기로 내려두었다.
 *
 * `capability_id` 가 PK 다 — 기능 하나에 근거 한 벌.
 */
@Table("capability_evidence")
data class CapabilityEvidenceEntity(
    @Id
    @Column("capability_id")
    val capabilityId: Long,

    /**
     * `Assembly-CSharp|타입|메서드|시그니처`. 난독화에도 살아남는 안정 키.
     *
     * 표시용 이름이 아니라 이쪽으로 조인한다.
     */
    @Column("entry_id")
    val entryId: String,

    @Column("owner_type")
    val ownerType: String,

    /**
     * 실제 동작 단위의 메서드 이름. 진입점(`entry`)과 다를 수 있다 — 한 `Update()` 밑에 서로 다른
     * 기능 넷이 들어 있는 일이 흔하다.
     */
    @Column("method")
    val method: String,

    /** 다른 레코드의 `calls[].targetId` 가 가리키는 값. */
    @Column("method_id")
    val methodId: String? = null,

    /** [RecordKind] 중 하나. */
    @Column("record_kind")
    val recordKind: String,

    /**
     * [TriggerKind] 중 하나.
     *
     * `unity-event` 는 "인스펙터가 부를 수 있는 모양"이지 **배선됐다는 뜻이 아니다.** 실측에서
     * 106건이었는데 실제 배선은 7쌍이었다.
     */
    @Column("trigger_kind")
    val triggerKind: String,

    /** [AnalysisConfidence] 중 하나. */
    @Column("analysis_confidence")
    val analysisConfidence: String,

    /**
     * 조건 트리 원본.
     *
     * **평탄화 금지** — `either` 가 `every` 로 뒤집히면 "둘 중 하나"가 "둘 다"가 된다.
     * [CapabilityEntity.givenText] 는 사람용 보조지 원본이 아니다.
     */
    @Column("condition_tree")
    val conditionTree: Json,

    /** `m_OnClick` 등. 배선된 이벤트 이름. */
    @Column("binding_event")
    val bindingEvent: String? = null,

    @Column("binding_receiver")
    val bindingReceiver: String? = null,

    /** 진입점부터 실제 동작까지의 호출 경로. 사람이 검증할 유일한 출처. */
    @Column("call_path")
    val callPath: Json = Json.of("[]"),

    /**
     * 근거가 "못 읽었다"고 말한 것과 적재기가 판정한 것. `subject-null` ·
     * `callee-condition-not-composed` · `unread-condition` 등.
     *
     * 버리지 않고 그대로 올린다 — 이 값이 있으면 조건을 단정하면 안 된다.
     */
    @Column("gaps")
    val gaps: Json = Json.of("[]"),
)
