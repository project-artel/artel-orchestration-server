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
