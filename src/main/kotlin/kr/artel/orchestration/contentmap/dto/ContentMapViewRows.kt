package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

/**
 * 씬 하나의 기능 상태 분포. `capability` 를 씬으로 묶어 센 결과다.
 *
 * **`v_content_map_capability` 로 세지 않는다.** 그 뷰는 `capability_evidence` 를 LEFT JOIN 하고
 * 그 조인을 접는 장치가 없어, `evidence` 가 기능당 여러 행이 되는 날 이 수가 조용히 부풀어 오른다.
 * 뷰가 쓰는 필터(`merged_into IS NULL`)는 그대로 가져간다.
 *
 * V72 전에는 뷰가 `status <> 'not-a-step'` 도 들고 있어 [notAStep] 이 구조적으로 0 이 됐다. 그
 * 필터는 이제 [kr.artel.orchestration.contentmap.repository.ContentMapRepository.findStepCapabilityRows]
 * 에 있고, `total - notAStep` 이 그 질의의 행 수와 같다. 실측 문서는 기능 491행 중 그 질의가
 * 내주는 것이 51행이다.
 *
 * `origin` 을 가리지 않는다. [kr.artel.orchestration.contentmap.repository.CapabilityRepository.countEvidenceVerification]
 * 은 `origin='evidence'` 로 좁히지만(그 지표의 분모는 정적 분석 성능이다), 이쪽은 화면이 "이 씬에
 * 무엇이 있나"를 묻는 것이라 QA 가 관측으로 배운 기능도 세어야 한다. 두 수가 다른 것이 정상이다.
 */
data class SceneCapabilityCountRow(
    @Column("scene_id")
    val sceneId: Long,

    @Column("total")
    val total: Long,

    @Column("runnable")
    val runnable: Long,

    @Column("needs_probe")
    val needsProbe: Long,

    @Column("not_a_step")
    val notAStep: Long,

    @Column("unreachable_precondition")
    val unreachablePrecondition: Long,
)

/**
 * 지도 전체의 씬 전이 한 줄. `scene_edge` 에 기능 요약을 곁들인 것.
 *
 * [capabilitySummary] 를 함께 내는 이유: 없으면 화면이 간선에 붙일 글자가 [toSceneName] 뿐이라, 같은
 * 씬으로 가는 간선 여럿이 전부 같은 이름으로 보인다. `capability_id` 는 단일 FK 라 `LEFT JOIN` 이
 * **행을 곱하지 않는다** — 효과를 접지 않는 것과 다른 사정이다.
 *
 * [givenText] 는 간선 행에 이미 있는 칸이다. 같은 컨트롤이 조건으로 갈려 서로 다른 씬으로 가므로,
 * 이것이 없으면 두 간선을 가를 근거가 응답에 없다.
 */
data class ContentMapSceneEdgeRow(
    @Column("from_scene_id")
    val fromSceneId: Long,

    /** 이름으로 둔다. **아직 순회하지 못한 씬으로 가는 전이가 있다.** */
    @Column("to_scene_name")
    val toSceneName: String,

    /** 그 씬을 순회했으면 채워진다. */
    @Column("to_scene_id")
    val toSceneId: Long?,

    /** 자동 전이면 null. 재적재가 기능을 지운 뒤에도 null 이 될 수 있다(`ON DELETE SET NULL`). */
    @Column("capability_id")
    val capabilityId: Long?,

    @Column("capability_summary")
    val capabilitySummary: String?,

    @Column("given_text")
    val givenText: String?,

    @Column("condition_tree")
    val conditionTree: Json?,

    /** [kr.artel.orchestration.contentmap.entity.EdgeSource] 중 하나. */
    @Column("source")
    val source: String,

    /** null 이면 아직 못 가본 전이이고, 그것이 곧 커버리지 구멍이다. */
    @Column("verified_at")
    val verifiedAt: Instant?,
)

/**
 * 지도 전체의 화면 전이 한 줄. `screen_transition` 에 기능 요약을 곁들인 것.
 *
 * [capabilitySummary] 를 함께 내는 이유는 [ContentMapSceneEdgeRow] 와 같다 — 없으면 화면이 전이에
 * 붙일 글자가 없고, 같은 두 화면을 잇는 전이 여럿이 전부 똑같이 보인다. `capability_id` 는 단일
 * FK 라 `LEFT JOIN` 이 **행을 곱하지 않는다.**
 *
 * 씬 전이(`scene_edge`)와 답하는 질문이 다르다. 저쪽은 "이 게임의 구조가 어떻게 생겼나"라 아직 안
 * 가본 곳도 나오고, 이쪽은 "실제로 어떻게 흘렀나"라 관측된 것만 나온다.
 */
data class ContentMapScreenTransitionRow(
    @Column("id")
    val id: Long,

    @Column("from_screen_id")
    val fromScreenId: Long,

    @Column("to_screen_id")
    val toScreenId: Long,

    /** null 이면 자동 전이다 — 타이머·로딩 완료처럼 TC 가 지시할 수 없는 것. */
    @Column("capability_id")
    val capabilityId: Long?,

    @Column("capability_summary")
    val capabilitySummary: String?,

    /** [kr.artel.orchestration.contentmap.entity.TransitionKind] 중 하나. */
    @Column("kind")
    val kind: String,

    /** 씬 경계를 넘었나. 넘지 않은 전이는 팝업 열림 같은 씬 안의 상태 변화다. */
    @Column("crosses_scene")
    val crossesScene: Boolean,

    @Column("observed_count")
    val observedCount: Int,

    @Column("first_seen_qa_run_id")
    val firstSeenQaRunId: Long?,
)

/**
 * 씬 하나의 기능 한 줄. [SceneCapabilityCountRow] 가 센 그 행들이다.
 *
 * 두 질의의 필터가 같아야 `목록의 크기 == total` 이 성립한다.
 * `CapabilityRepository.findSceneCapabilities` 가 그 이유를 적어 둔다.
 *
 * 컨트롤 정보(`control_label` · `control_path` · `input_key`)는 담지 않는다. 그 칸이 찬 행은 조작이
 * 있는 행이고, 조작이 있는 행은 이미 [SceneStepResponse] 로 나간다 — 같은 값을 응답에 두 벌 실으면
 * 이 목록이 아홉 배 커지는 자리에서 그 비용이 그대로 두 배가 된다. 두 목록은 `capability.id` 로
 * 이어진다.
 *
 * 효과(`capability_effect`)도 없다. 기능 하나에 여러 행이라 접으면 곱해진다 —
 * `v_content_map_capability` 가 효과를 빼는 것과 같은 판단이다.
 */
data class SceneCapabilityRow(
    @Column("scene_id")
    val sceneId: Long,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("summary")
    val summary: String,

    /** 세 축에서 유도된 값이다. [actionability] · [observability] · [applicability] 를 함께 낸다. */
    @Column("status")
    val status: String,

    /** [kr.artel.orchestration.contentmap.entity.CapabilityOrigin] 중 하나. */
    @Column("origin")
    val origin: String,

    /** [kr.artel.orchestration.contentmap.entity.VerificationState] 중 하나. */
    @Column("verification")
    val verification: String,

    /**
     * [kr.artel.orchestration.contentmap.entity.ScenePresence] 중 하나. 이 행이 왜 이 `scene` 에
     * 있나(ARTEL-460).
     */
    @Column("scene_presence")
    val scenePresence: String,

    @Column("actionability")
    val actionability: String,

    @Column("observability")
    val observability: String,

    @Column("applicability")
    val applicability: String,

    /** [kr.artel.orchestration.contentmap.entity.Interaction] 중 하나. 프로토콜 메서드가 아니라 의도다. */
    @Column("interaction")
    val interaction: String,
)

/**
 * `screen` 하나에 묶인 `capability` 한 줄. `screen_capability` 에 `capability` 를 곁들인 것.
 *
 * **[SceneCapabilityRow] 와 답하는 질문이 다르다.** 저쪽은 "이 `scene` 어딘가에서 무엇을 할 수 있나"
 * 이고 이쪽은 "이 `screen` 에서 실제로 무엇이 되더라"이다. 정적 `evidence` 가 아는 것은 "이 타입이
 * 이 `scene` 에 놓였다"까지고, 어느 `screen` 상태에서 실제로 눌리는지는 런타임만 안다 — 그래서
 * `scene` 의 목록으로 이 목록을 대신 채울 수 없다. 빈 목록은 "이 `screen` 에서 아직 아무것도 확인
 * 안 됐다"는 사실이지 결함이 아니다.
 *
 * [observedCount] 와 [firedCount] 는 `capability` 가 아니라 **연결 행 자체가 든 값**이라 여기 말고
 * 나갈 자리가 없다. 둘의 차이가 결함 신호다 — 눌렀는데 아무것도 안 변한 횟수.
 *
 * 판정 세 축(`actionability` · `observability` · `applicability`)은 담지 않는다. 같은
 * `capability.id` 로 `scene` 의 [SceneCapabilityRow] 에 이미 나가 있고, `screen` 이 수십 개인 `scene`
 * 에서 같은 값을 다시 실으면 그 비용이 `screen` 수만큼 곱해진다. 두 목록은 `capability.id` 로
 * 이어진다 — [SceneCapabilityRow] 가 컨트롤 정보를 빼는 것과 같은 판단이다.
 */
data class ScreenCapabilityRow(
    @Column("screen_id")
    val screenId: Long,

    @Column("capability_id")
    val capabilityId: Long,

    @Column("summary")
    val summary: String,

    /** 세 축에서 유도된 값이다. 축 자체는 `scene` 의 `capabilityList` 에서 같은 id 로 찾는다. */
    @Column("status")
    val status: String,

    /** [kr.artel.orchestration.contentmap.entity.CapabilityOrigin] 중 하나. */
    @Column("origin")
    val origin: String,

    /** [kr.artel.orchestration.contentmap.entity.VerificationState] 중 하나. */
    @Column("verification")
    val verification: String,

    /** 이 `screen` 에서 이 `capability` 를 몇 번 봤나. */
    @Column("observed_count")
    val observedCount: Int,

    /** 그중 실제로 무언가 변한 횟수. [observedCount] 와의 차이가 결함 신호다. */
    @Column("fired_count")
    val firedCount: Int,
)

/**
 * `screen` upsert 한 번의 결과 (ARTEL-456).
 *
 * upsert 는 새로 앉힌 것과 다시 본 것을 같은 모양으로 돌려준다. [inserted] 가 그 둘을 가르고,
 * 화면 `screen capture` 는 **`true` 일 때만** 요청된다 — 관측마다 요청하면 같은 화면이 볼 때마다 다시
 * 찍혀 "처음 것만 남긴다" 가 무너진다.
 *
 * 판정의 근거는 `xmax` 다. 어떻게 읽는지는
 * [kr.artel.orchestration.contentmap.repository.ScreenRepository.observe] 의 KDoc 에 있다.
 */
data class ScreenObservationRow(
    @Column("id")
    val id: Long,

    @Column("inserted")
    val inserted: Boolean,
)
