package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.annotation.ReadOnlyProperty
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
     * 지금의 [verification] 을 만든 `capability_observation` 행(ARTEL-644).
     *
     * TC 생성기가 `confirmed` 를 사실로 읽으므로, 잘못 올린 한 행은 근거 없는 테스트 케이스가
     * 되고 그 테스트가 실패하면 사람은 게임을 의심한다 — 지도를 의심하지 않는다. 그래서
     * verification 은 조용할 수 없고, 이 포인터 끝에 agent 가 무엇을 보고 그렇게 말했는지가 있다.
     *
     * rationale 을 여기 복사하지 않는다. observation 행이 이미 rationale · qa_run_id · 시각을 들고
     * 있어, 복사하면 두 벌이 갈라진다. 대가는 `qa_run` 삭제가 CASCADE 로 observation 을 지우면
     * 이 칸이 NULL 이 되어 verification 이 설명을 잃는다는 것이고, 지금 `qa_run` 을 지우는 경로가
     * 없어 열어 둔다.
     */
    @Column("verification_observation_id")
    val verificationObservationId: Long? = null,

    /**
     * [ScenePresence] 중 하나 — 이 행이 **왜 이 `scene` 에 있나**(ARTEL-460).
     *
     * [verification] 과 다른 축이다. 저쪽은 "실행해 봤나"이고 이쪽은 "근거가 이 `scene` 을 말했나"다.
     * `DontDestroyOnLoad` 오브젝트의 capability 는 real `scene` 전부에 행을 갖는데, 그 행이
     * `placed` 행과 똑같이 보이면 `TurnBattleScene` 을 읽는 agent 가 tutorial capability 를 그
     * `scene` 의 사실로 읽는다.
     *
     * observed · inferred · human 출신에서도 `placed` 다. 그 행들은 agent 나 사람이 그 `scene` 을
     * 보고 적은 것이라, 그 `scene` 에 있다는 말이 곧 근거다.
     */
    @Column("scene_presence")
    val scenePresence: String = ScenePresence.PLACED.wire,

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

    /**
     * 이 기능을 든 타입을 **무엇이 만드는가**. `unplaced[type].createdBy` 항목 원문이다.
     * 예: `Cards.CardManager.cardPrefab`.
     *
     * 프리팹 위에만 사는 타입은 씬 오브젝트의 컴포넌트 목록에 없어 배선으로는 구조적으로 0건이고,
     * 씬에 귀속시키는 유일한 주소가 이것이다. 비면 씬만 남고 입구가 사라진다.
     *
     * 배선으로 찾은 기능에서는 null 이다. 값이 있으면 [controlPath] · [controlSelector] ·
     * [controlLabel] 은 비어야 하고, [interaction] 은 [Interaction.NONE] · [actionability] 는
     * [Actionability.NOT_A_STEP] 이어야 한다 — DB CHECK 가 강제한다. 축까지 묶는 이유는
     * [actionability] 의 기본값이 `runnable` 이라, 명시하지 않으면 조작 없는 행이 TC 창구를
     * 통과하기 때문이다.
     *
     * `createdBy` 는 목록이지만 이 칸은 **씬 귀속을 실제로 결정한 하나**만 담는다. 한 씬에 후보가
     * 둘 이상이면 비우고 근거의 gaps 에 사유를 남긴다.
     */
    @Column("spawned_by_field")
    val spawnedByField: String? = null,

    /**
     * [spawnedByField] 를 쥔 씬 오브젝트 경로. 예: `CardSystem/CardManager`.
     * 근거의 `objects[].components[].refs[].carries` 가 줄 때만 찬다 — **NULL 이 정상이다.**
     *
     * 있으면 귀속이 정확(문서가 경로를 줬다)하고, 없으면 유도(오너 타입의 배치에서 씬만 얻었다)다.
     * 그 정밀도는 귀속 단계의 `capability_proof.resolution` 으로 들어가고, `analysis_confidence` 는
     * 사슬 전체의 최솟값에서 따라 나온다.
     *
     * 이 값을 [controlPath] 로 옮겨 담지 않는다 — 만드는 쪽의 주소를 조준 대상으로 내주면 TC 가
     * 만드는 쪽을 눌러 만들어지는 쪽을 확인했다고 말하게 된다.
     */
    @Column("spawned_by_scene_path")
    val spawnedByScenePath: String? = null,

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
     * 한 번 누르는 것과 **끝날 때까지** 누르는 것을 가른다. 대사 넘기기·웨이브 클리어처럼 같은
     * 조작을 반복해야 다음 자리에 닿는 구간이 이 계열이다.
     *
     * [Interaction] enum 을 넓히지 않고 형제 컬럼으로 둔 이유: enum 확장은 이 값을 읽는 모든
     * 소비자를 깨지만, 모르는 컬럼은 아무도 깨지 않는다. 기본값 `false` 라 모르는 소비자는 반복
     * 구간을 한 번짜리 조작으로 읽을 뿐 실패하지 않는다.
     *
     * 반복을 못 적으면 그 전제가 [givenText] 로 밀려난다. "대사를 모두 넘긴 상태"는 사람에게는
     * 지시가 되지만 **실행 에이전트가 확인할 수 없는 전제**다. 반복은 전제가 아니라 스텝이어야 한다.
     *
     * 종료 조건은 여기 없다. 반복이 끝내 만드는 효과가 곧 종료 조건이고, 그것은 이미
     * [CapabilityEffectEntity] 에 있다.
     */
    @Column("repeat_until_done")
    val repeatUntilDone: Boolean = false,

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

    /**
     * 실행 가능성 — 이 조작을 실제로 할 수 있는가. [Actionability] 중 하나.
     *
     * gap 이 남았는지를 보는 판정이 정한다. 관측 가능 여부는 여기서 보지 않는다.
     */
    @Column("actionability")
    val actionability: String = Actionability.RUNNABLE.wire,

    /**
     * 관측 가능성 — 그 결과를 볼 수 있는가. [Observability] 중 하나.
     *
     * `capability_effect.watchable` 이 정하는 상한이 이 축으로 올라온다.
     */
    @Column("observability")
    val observability: String = Observability.UNKNOWN.wire,

    /** 적용 가능성 — 이 빌드에 이 규칙이 적용되는가. [Applicability] 중 하나. */
    @Column("applicability")
    val applicability: String = Applicability.APPLIES.wire,

    /**
     * [SpecStatus] 중 하나. **세 축에서 유도된 값이라 쓰지 않는다.**
     *
     * DB 생성 컬럼이므로 여기에 값을 담아 보내면 INSERT 가 거절된다. 축을 정하면 이 값이 따라오고,
     * 그래서 축과 어긋난 행이 존재할 수 없다 — 어긋난 뒤에는 어느 쪽이 참인지 아무도 모른다.
     */
    @ReadOnlyProperty
    @Column("status")
    val status: String? = null,

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
