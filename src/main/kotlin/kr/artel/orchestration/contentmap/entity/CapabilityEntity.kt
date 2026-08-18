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

    /** [CapabilityOrigin] 중 하나. */
    @Column("origin")
    val origin: String,

    /** [VerificationState] 중 하나. */
    @Column("verification")
    val verification: String = VerificationState.UNVERIFIED,

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
     * 씬 안에서 유일한 안정 식별자(`Canvas[2]/MapSceneButton[1]`). 실행 간 유지된다.
     *
     * **조준에 직접 쓰지 못한다** — 현재 액션 프로토콜은 `int` instance id 를 받고 그 숫자는
     * 프로세스를 넘지 못한다. 실행 시 해석은 [QaRunTargetEntity] 가 맡는다.
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
