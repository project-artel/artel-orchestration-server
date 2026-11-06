package kr.artel.orchestration.testscenario.service

/**
 * 턴이 **멎었나**를 재는 규칙(ARTEL-632).
 *
 * 재는 것은 "죽었나"이지 "느린가"가 아니다. 시한을 턴 시작부터 고정으로 세면 **살아서 일하는
 * 턴**도 끊긴다 — 실측(런 179)에서 저작이 도구를 47번 부르며 일하는 동안 정각 5분에 시한이 났고,
 * 사용자가 본 것은 "끝나지 않았습니다"였다. 결과는 그 뒤에 도착해 조용히 저장됐고, 물어야 할
 * 것 셋도 그 턴과 함께 사라졌다.
 *
 * 그래서 **아무 소식도 없는 시간**만 센다. 조회가 많은 턴은 오래 걸릴 뿐 멎은 것이 아니다.
 */
object TurnDeadline {

    /**
     * 다시 볼 때까지 잘 시간. **null 이면 이제 멎은 것으로 본다.**
     *
     * @param lastHeard 에이전트에게서 마지막으로 무언가 들은 때.
     * @param now 지금.
     * @param deadline 아무 소식 없이 이만큼 지나면 멎은 것으로 본다.
     */
    fun remainingWait(lastHeard: Long, now: Long, deadline: Long): Long? {
        val quiet = now - lastHeard
        // 시계가 뒤로 갔거나 미래에서 들은 것으로 적힌 경우다. 음수만큼 자면 즉시 깨어 헛돌므로
        // 한 바퀴를 통째로 다시 기다린다 — 모르면 더 기다리는 쪽이 안전하다.
        if (quiet < 0) return deadline
        if (quiet >= deadline) return null
        return deadline - quiet
    }
}
