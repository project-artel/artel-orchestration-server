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

/**
 * 프로젝트 시나리오 목록의 한 항목(요약). 목록 화면 렌더용으로 payload 전체 대신 제목만 담는다.
 * 제목은 payload(ScenarioDraft)의 title에서 추출한다.
 */
data class ScenarioSummary(
    val testScenarioId: Long,
    val projectId: Long,
    val title: String,
    val createdAt: Instant?,
    val updatedAt: Instant?
)

/**
 * 프로젝트 시나리오 목록 응답. 팀 목록 API 관례에 맞춰 `items` 배열로 감싼다.
 */
data class ScenarioListResponse(
    val items: List<ScenarioSummary>
)
