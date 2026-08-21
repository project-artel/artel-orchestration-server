package kr.artel.orchestration.testscenario.dto

import java.time.Instant

/**
 * 시나리오 단건 조회 응답. `payload`는 Agent가 생성한 ScenarioDraft로, FE가 canvas 렌더에 사용한다.
 *
 * 저장이 컬럼으로 쪼개진 뒤에도(ARTEL-291) 이 필드명은 FE 계약이라 그대로 둔다 — 서버가 세 컬럼을
 * 다시 ScenarioDraft로 조립해 내려준다.
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
    val createdAt: Instant?,
    /**
     * 구조적 본문(ARTEL-487). 지금은 되묻는 질문 하나가 쓴다 — `kind: "question"` 과 선택지.
     *
     * 이력에도 싣는 이유는 **새로고침 뒤에도 답할 수 있어야** 하기 때문이다. SSE 로만 흘리면
     * 질문은 대화에 남았는데 누를 것만 사라진다.
     */
    val payload: Map<String, Any?>? = null,
)

/**
 * 프로젝트 시나리오 목록의 한 항목(요약). 목록 화면 렌더용으로 본문 전체 대신 제목만 담는다.
 * 제목은 test_scenario.title 컬럼에서 그대로 온다(스텝을 역직렬화하지 않는다).
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
