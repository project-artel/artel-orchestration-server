package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode

/**
 * 화면에 대한 프레임을 agent 로 내보내는 자리 (ARTEL-655 · ARTEL-668).
 *
 * 구현은 [QaSdkBridgeService] 하나다. 인터페이스를 두는 것은 방향 때문이다 — 프레임을 만드는 쪽
 * (`contentmap.observe`) 이 QA 세션의 배관을 알 필요가 없고, 알면 화면 관측이 QA 로그·세션·봉투에
 * 묶여 그 셋 중 하나가 바뀔 때마다 따라 움직인다.
 *
 * 둘 다 **보낼 곳이 없으면 `false` 를 돌려주고 던지지 않는다.** `false` 가 오는 자리는 둘이다 —
 * 이 인스턴스에 활성 QA try 가 없거나, try 는 있는데 agent 세션이 아직 안 붙었다(STARTING). 둘 다
 * 오류가 아니라 "지금은 말할 상대가 없다" 이고, 관측은 그 사실과 무관하게 계속 돈다.
 */
interface QaScreenFramePort {

    /**
     * 목록에 없는 selector 를 물어보는 제안 하나를 보낸다.
     *
     * `false` 를 받은 쪽은 집어 둔 질문을 놓아야 다음 기회에 다시 물을 수 있다 — 나가지 못한
     * 질문이 물어본 것으로 남으면 그 질문은 영영 안 나간다.
     *
     * @param messageId 이 제안의 식별자. 답의 `correlationId` 가 이 값으로 돌아온다.
     * @param summary QA 타임라인에 뜨는 한 줄.
     */
    suspend fun sendScreenSelectorProposal(
        gameInstanceId: Long,
        messageId: String,
        summary: String,
        payload: JsonNode,
    ): Boolean

    /**
     * 관측이 확정한 화면을 알린다 (ARTEL-668).
     *
     * 제안과 달리 **답이 없다.** `correlationId` 도 없고 무엇을 집어 두지도 않으므로, `false` 를
     * 받아도 놓을 것이 없다 — 다음에 화면이 바뀌면 그때 다시 나간다.
     *
     * @param summary QA 타임라인에 뜨는 한 줄.
     */
    suspend fun sendScreenSettled(
        gameInstanceId: Long,
        messageId: String,
        summary: String,
        payload: JsonNode,
    ): Boolean
}
