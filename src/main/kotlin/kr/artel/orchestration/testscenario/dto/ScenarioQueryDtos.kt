package kr.artel.orchestration.testscenario.dto

import java.time.Instant

/**
 * 시나리오 단건 조회 응답. `payload`는 Agent가 생성한 ScenarioDraft로, FE가 canvas 렌더에 사용한다.
 */
data class ScenarioResponse(
    val testScenarioId: Long,
    val projectId: Long,
    val payload: ScenarioDraft
)

/**
 * 채팅 메시지 조회 응답(사용자별 프라이빗 스레드). 재방문 시 대화 복원에 사용한다.
 */
data class MessageResponse(
    val role: String,
    val content: String,
    val createdAt: Instant?
)
