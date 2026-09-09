package kr.artel.orchestration.testcase.dto

/**
 * 화면 그래프의 간선 한 줄, 조회가 낸 그대로(ARTEL-628).
 *
 * @property byOperation 무엇을 눌러야 가나. **null 이면 저절로 가는 자리다** — 실측 19간선 중
 *   12건이 `not-a-step` 이고, 그건 누를 것을 찾을 필요가 없다는 정보이지 모른다는 뜻이 아니다.
 */
data class SceneExitRow(
    val fromScene: String,
    val toScene: String,
    val byOperation: String?,
)

/**
 * [SceneExitRow] 에 그 간선이 **어느 기능인지**를 더한 것 — 다리 근거 되찾기가 쓴다.
 * `scene_edge.capability_id` 가 처음부터 들고 있던 값이라 새 판단이 없다.
 */
data class GroundedSceneEdgeRow(
    val fromScene: String,
    val toScene: String,
    val byOperation: String?,
    val capabilityId: Long,
)
