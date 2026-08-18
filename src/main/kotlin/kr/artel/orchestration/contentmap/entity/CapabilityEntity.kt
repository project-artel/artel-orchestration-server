package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * capability — 이 씬에서 무엇을 할 수 있나. content_map 의 본체.
 *
 * **축이 둘이다.**
 *
 * | 축 | 값 | 뜻 |
 * |---|---|---|
 * | [origin] | evidence / observed / inferred / human | 어디서 왔나 |
 * | [verification] | unverified / confirmed / contradicted | 실행으로 확인됐나 |
 *
 * 하나로 뭉치면 "IL 분석기가 확신함"과 "돌려봐서 됨"을 구분하지 못한다. QA agent 가 플레이하며
 * 배운 기능이 evidence 출신과 같은 통에 들어가는 순간, TC 가 근거 없는 것을 근거 있는 것처럼
 * 취급한다.
 *
 * evidence 출신만 갖는 컬럼은 [CapabilityEvidenceEntity] 로 뗐다. 여기 두면 관측으로 배운 기능이
 * `NOT NULL` 에 막혀 더미값을 넣게 되고, 그 순간 두 종류가 구분 불가능해진다.
 *
 * **액션 프로토콜의 어휘는 담지 않는다.** `button_click` 같은 이름은 SDK 의 것이고 배포마다
 * 바뀐다. 판독의 `offers` 가 그 오브젝트가 지금 무엇에 응답하는지 실어 주므로 실제 메서드는
 * agent 가 런타임에 정하고, 여기에는 프로토콜이 바뀌어도 그대로인 [interaction] 만 남긴다.
 */
@Table("capability")
data class CapabilityEntity(
    @Id
    val id: Long? = null,

    @Column("scene_id")
    val sceneId: Long,

    /**
     * 씬을 통해 이미 알 수 있는 값이지만 함께 든다. [capabilityKey] 의 유일성 범위가 content_map
     * 단위라, 그 제약을 DB 가 걸려면 같은 테이블에 있어야 한다.
     *
     * `(scene_id, content_map_id)` 복합 FK 가 씬의 것과 어긋날 수 없게 묶으므로 두 벌이 갈라지지
     * 않는다.
     */
    @Column("content_map_id")
    val contentMapId: Long,

    /**
     * 재적재를 넘어 살아남는 내용 기반 키. `(entry_id, branch_offset, 정규화한 condition_tree)`
     * 에서 만든다 — 산식은 적재기(ARTEL-442)가 확정한다.
     *
     * [id] 는 `BIGSERIAL` 이라 적재기가 evidence 기능을 지웠다 넣으면 새로 발급되고, 갈래를
     * 펼치면 번호가 통째로 밀린다. `scene_edge.capability_id` 와 `screen_transition.capability_id`
     * 는 **런타임에 알아낸 지식을 든 참조**라, 번호가 밀리면 그 지식이 엉뚱한 기능에 붙는다.
     *
     * null 인 이유: observed · inferred · human 출신은 `entry_id` 도 `branch_offset` 도 없어
     * 산식의 입력이 없다. 없는 것이 정상이고, 더미값을 넣으면 그 순간 키가 키가 아니게 된다.
     */
    @Column("capability_key")
    val capabilityKey: String? = null,

    /** [CapabilityOrigin] 중 하나. */
    @Column("origin")
    val origin: String,

    /** [VerificationState] 중 하나. */
    @Column("verification")
    val verification: String = VerificationState.UNVERIFIED.wire,

    /**
     * 식별자를 남긴 설명. 모든 [origin] 공통.
     *
     * 경로·타입·메서드·필드는 원문 그대로 쓰고 사이만 말로 잇는다. `MapMove.position` 을
     * "캐릭터가 옆으로 이동"으로 옮기는 것이 이 시스템에서 가장 비싼 거짓 명세다.
     */
    @Column("summary")
    val summary: String,

    /** 명세의 `given` 을 한 줄로. 트리 원본은 [CapabilityEvidenceEntity.conditionTree]. */
    @Column("given_text")
    val givenText: String? = null,

    /**
     * 형제 인덱스가 붙은 위치 경로(`Canvas[2]/MapSceneButton[1]`).
     *
     * 한 판독 안에서는 반드시 하나를 가리킨다 — `(부모, 형제 인덱스)` 가 transform 하나만
     * 지목하기 때문이다. 다만 **계층이 정적일 때만 실행 간 유지된다** — 형제가 생기거나
     * 사라지면 인덱스가 밀려 같은 문자열이 다른 오브젝트를 가리킨다.
     *
     * **조준에 직접 쓰지 못한다** — 현재 액션 프로토콜은 `int` instance id 를 받고 그 숫자는
     * 프로세스를 넘지 못한다. 실행 시 해석표는 판독을 받는 ARTEL-449 가 가져간다.
     */
    @Column("control_selector")
    val controlSelector: String? = null,

    /** 사람이 읽는 계층 경로. 표시용. */
    @Column("control_path")
    val controlPath: String? = null,

    /** 누를 수 있는 것에 쓰인 글자. 표시용이자 프롬프트 재료. */
    @Column("control_label")
    val controlLabel: String? = null,

    /** [Interaction] 중 하나. 프로토콜 메서드가 아니라 의도다. */
    @Column("interaction")
    val interaction: String,

    /** [Interaction.PRESS] 일 때의 키 이름. DB CHECK 가 이 쌍을 강제한다. */
    @Column("input_key")
    val inputKey: String? = null,

    /** [InputPhase] 중 하나. */
    @Column("input_phase")
    val inputPhase: String? = null,

    /**
     * 직전에 성공한 조작. **권위 없음** — agent 가 먼저 시도해 볼 값이지 따라야 하는 값이 아니고,
     * 실패하면 agent 가 다시 정하고 여기를 갱신한다. 첫 런의 판단 한 번을 아끼는 캐시다.
     */
    @Column("hint_action_method")
    val hintActionMethod: String? = null,

    @Column("hint_action_params")
    val hintActionParams: Json? = null,

    @Column("hint_from_qa_run_id")
    val hintFromQaRunId: Long? = null,

    /** [SpecStatus] 중 하나. */
    @Column("status")
    val status: String,

    /** 관측으로 발견한 것이 나중에 evidence 로도 확인되면 이쪽으로 접는다. */
    @Column("merged_into")
    val mergedInto: Long? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
