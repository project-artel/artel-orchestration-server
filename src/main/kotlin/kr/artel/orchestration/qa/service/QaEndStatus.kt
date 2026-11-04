package kr.artel.orchestration.qa.service

/**
 * qa_try · qa_run 의 끝 상태(V9 · V30 · V176). 끝 상태를 묻는 자리는 모두 이것을 쓴다 — 자리마다 집합을 따로
 * 적어 두면 상태가 늘 때 한 곳이 빠지고, 빠진 곳에서는 끝난 시도가 영영 도는 것으로 보인다.
 */
object QaEndStatus {
    /**
     * 게임을 검증하지 못하고 끝났다(ARTEL-1371). 모델 API 가 크레딧 · 인증 · 모델 오류로 거절해 agent 가 런을
     * 멈춘 시도, 그리고 그 때문에 시작하지 못한 시도다. 실패가 아니므로 통과율 · 실패 집계에 들어가지 않는다.
     */
    const val UNDECIDED = "UNDECIDED"

    /** 앞 시나리오가 멈춰 시작하지 못한 시도의 `stop_code`. 화면은 이것을 "실행 안 함" 으로 보인다. */
    const val NOT_STARTED = "not_started"

    val TERMINAL = setOf("COMPLETED", "FAILED", "CANCELLED", UNDECIDED)
}
