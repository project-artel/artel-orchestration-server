package kr.artel.orchestration.contentmap.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

/**
 * 씬 하나의 기능 상태 분포. `capability` 를 씬으로 묶어 센 결과다.
 *
 * **`v_content_map_capability` 로는 셀 수 없다.** 그 뷰는 `status <> 'not-a-step'` 으로 이미 걸러
 * 내므로 [notAStep] 이 구조적으로 0 이 되고, 씬별 합이 이 지도의 기능 총수와 어긋난다. 실측 문서는
 * 기능 491행 중 뷰가 내주는 것이 51행뿐이라, 뷰로 세면 표가 아홉 배 작아 보인다.
 *
 * 그래서 뷰가 쓰는 나머지 필터(`merged_into IS NULL`)만 그대로 가져가고 `not-a-step` 은 세어서 낸다.
 * 그러면 `total - notAStep` 이 뷰의 행 수와 같다는 관계가 성립한다.
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
