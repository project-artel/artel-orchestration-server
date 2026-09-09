package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.annotation.JsonInclude
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 지도 전체의 화면 간선 하나 — 어디서, 어디로, 무엇을 해서(ARTEL 실험: 흐름 없이 저작).
 *
 * [SceneExit] 는 케이스가 서 있는 화면의 것만 딸려 나가서, **케이스가 없는 화면의 간선은
 * 모델에게 안 보인다.** 그 빈 자리를 모델은 "못 간다"로 읽는다 — 짝 행렬이 찾아냈던 헛막힘
 * 1,126칸이 그 종류였다. 그래서 세션을 열 때 지도 수준의 간선 전체를 한 번 더 싣는다.
 *
 * @property by [SceneExit.by] 와 같은 규칙 — null 이면 저절로 간다(지시할 수 없다).
 */
data class SceneEdge(
    @JsonProperty("from_scene") val fromScene: String,
    @JsonProperty("to_scene") val toScene: String,
    @JsonInclude(JsonInclude.Include.NON_NULL) val by: String? = null,
)
