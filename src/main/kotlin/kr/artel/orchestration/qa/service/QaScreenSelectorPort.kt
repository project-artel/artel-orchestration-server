package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode

/**
 * 화면 판정 목록에 대한 제안을 agent 로 내보내는 자리 (ARTEL-655).
 *
 * 구현은 [QaSdkBridgeService] 하나다. 인터페이스를 두는 것은 방향 때문이다 — 제안을 만드는 쪽
 * (`contentmap.observe`) 이 QA 세션의 배관을 알 필요가 없고, 알면 화면 관측이 QA 로그·세션·봉투에
 * 묶여 그 셋 중 하나가 바뀔 때마다 따라 움직인다.
 */
interface QaScreenSelectorPort {

    /**
     * 제안 프레임 하나를 보낸다. 보낼 곳이 없으면 `false` 를 돌려주고 **던지지 않는다.**
     *
     * `false` 가 오는 자리는 둘이다 — 이 인스턴스에 활성 QA try 가 없거나, try 는 있는데 agent
     * 세션이 아직 안 붙었다(STARTING). 둘 다 오류가 아니라 "지금은 물어볼 상대가 없다" 이고,
     * 부르는 쪽은 그때 집어 둔 질문을 놓아야 다음 기회에 다시 물을 수 있다.
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
}
