package kr.artel.orchestration.qa.service

import kr.artel.orchestration.sdk.dto.ActionItemDto

/**
 * QA 가 주고받은 액션을 관측 타임라인에 알리는 자리 (ARTEL-450).
 *
 * 구현은 `contentmap.observe.CapabilityObservationService` 하나다. 인터페이스를 두는 것은
 * [QaScreenFramePort] 와 같은 이유이고 방향만 반대다 — QA 배관이 content_map 의 적재 규칙을 알
 * 필요가 없고, 알면 액션 전달이 관측 스키마에 묶여 그것이 바뀔 때마다 따라 움직인다.
 *
 * **둘 다 던지지 않는다.** 관측은 QA 런의 곁가지라, 타임라인이 무엇을 못 해도 액션은 그대로
 * 나가고 결과는 그대로 중계돼야 한다.
 */
interface QaActionObservationPort {

    /**
     * 액션 묶음 하나가 SDK 로 나갔다.
     *
     * **겨눈 것이 없는 액션도 반드시 알려야 한다.** 좌표를 받는 액션은 관측을 만들지 않지만, 앞선
     * 액션의 귀속 창이 배타적이려면 "그 사이에 다른 조작이 있었다"는 사실이 타임라인에 닿아야 한다.
     *
     * @param requestId `qa_log` 가 발급한 outer id. `ACTION_RESULT` 가 `requestId` 로 되돌려 준다.
     */
    suspend fun dispatched(gameInstanceId: Long, requestId: Long, actions: List<ActionItemDto>)

    /**
     * SDK 가 그 액션의 결과를 돌려줬다.
     *
     * @param succeeded 묶음의 결과가 **전부** 성공이었나. 하나라도 거절당했으면 그 조작은 게임에
     *   닿지 못한 것이라 관측을 만들지 않는다.
     */
    suspend fun settled(gameInstanceId: Long, requestId: Long, succeeded: Boolean)
}
