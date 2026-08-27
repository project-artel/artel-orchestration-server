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
     * 항상 `evidence`. 값을 담으려는 컬럼이 아니라 **복합 FK 의 한쪽 다리**다 —
     * `(capability_id, origin)` 이 `capability (id, origin)` 을 참조해, 이 행이 evidence 출신
     * 기능에만 붙는다는 것을 DB 가 강제한다. 없으면 관측 출신 기능에 IL 근거를 붙일 수 있고
     * "축이 둘"이라는 전제가 반대쪽에서 무너진다.
     */
    @Column("origin")
    val origin: String = CapabilityOrigin.EVIDENCE.wire,

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

    /**
     * 다른 레코드의 `calls[].targetId` 가 가리키는 값.
     *
     * null 이면 [gaps] 에 [EvidenceGap.METHOD_ID_MISSING] 이 있어야 한다. DB CHECK 가 강제한다 —
     * 조용히 비면 "적재기가 못 채운 것"과 "근거에 원래 없는 것"이 구분되지 않는다.
     */
    @Column("method_id")
    val methodId: String? = null,

    /**
     * 이 기능이 갈라져 나온 IL 위치.
     *
     * [entryId] 만으로는 근거 주소가 **메서드까지만** 남아, 한 `Update()` 안의 서로 다른 지점에서
     * 나온 기능들이 하나로 눌린다. 실측에서 `CharacterMove` 가 9건이어야 할 자리에 3건이었다.
     *
     * 근거 원본이 이미 주는 값이라 새로 분석할 것이 없다.
     */
    @Column("branch_offset")
    val branchOffset: Int? = null,

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

    /**
     * 진입점부터 실제 동작까지의 호출 경로. 사람이 검증할 유일한 출처.
     *
     * 비어 있으면 [gaps] 에 [EvidenceGap.CALL_PATH_MISSING] 이 있어야 한다. DB CHECK 가 강제한다.
     */
    /**
     * 이 근거가 **부르는** 메서드들(ARTEL-554). 문서의 `records[].calls` 를 그대로 싣는다.
     *
     * 조작 갈래와 결과 갈래를 잇는 **유일한 인과**다. 코루틴·상태 머신에서는 입력을 받는 갈래와
     * 결과를 내는 갈래가 다른 행이라, 공통 호출자를 통해서만 이어진다 — 실측에서
     * `StoryController.StoryTelling()` 이 `IsAdvanceKeyDown` 과 `LoadMapScene` 을 둘 다 부른다.
     *
     * [callPath] 와 다르다. 저쪽은 **이 근거가 지나온 길**이고 여기는 **이 근거가 부르는 것**이다.
     * 실측에서 `call_path` 로 이으면 161 중 6밖에 안 닿았다.
     */
    @Column("calls")
    val calls: Json = Json.of("[]"),

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

    /**
     * 되돌아가는 지점의 IL 위치(ARTEL-613). 문서의 `records[].loopsBackTo` 다.
     *
     * 이 갈래의 가드를 **뒤집으면** "다 돌고 나온 자리"가 된다 — `i < 총개수` 로 되돌아가는
     * 루프의 바깥은 `i >= 총개수` 다. 실행하는 사람은 `i` 를 읽을 수 없지만 **끝까지 눌러 그
     * 자리를 만들 수는 있으므로**, 그 조건은 사전조건에서 지울 것이 아니라 스텝으로 옮길 것이다.
     *
     * 기능이 아니라 근거에 두는 이유는 마이그레이션 주석에 적었다 — 루프를 도는 것은 코루틴이라
     * 조작이 없고, 조작은 그것을 부르는 입력 갈래에 있다.
     */
    @Column("loops_back_to")
    val loopsBackTo: Int? = null,
)
