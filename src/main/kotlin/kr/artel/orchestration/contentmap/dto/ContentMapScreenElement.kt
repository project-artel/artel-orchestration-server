package kr.artel.orchestration.contentmap.dto

import org.springframework.data.relational.core.mapping.Column

/**
 * 화면에 붙어 있는 UI 요소 하나(ARTEL-683).
 *
 * *"그 버튼이 보이는가"* 를 확인하는 케이스의 재료다. 바뀌는 것이 아니라 **있는 것**이라
 * `capability_effect` 에는 없다.
 */
data class ContentMapScreenElement(
    @Column("scene_name")
    val sceneName: String,

    /** 씬에서 찾을 경로. `Canvas/continue` 처럼 실행하는 쪽이 그대로 쓴다. */
    @Column("path")
    val path: String,
)
