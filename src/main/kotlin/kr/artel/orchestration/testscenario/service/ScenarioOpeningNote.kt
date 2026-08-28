package kr.artel.orchestration.testscenario.service

/**
 * **여기까지 와야 시작한다**(ARTEL-636).
 *
 * 시나리오는 하나가 끝날 때마다 게임을 초기화한다. 그런데 검증하는 순간은 게임 곳곳에 흩어져
 * 있다 — 초반, 중반, 엔딩. 그래서 엔딩을 보는 시나리오는 **매번 엔딩까지 다시 가야** 하고,
 * 중반을 보는 것은 중반까지 다시 가야 한다.
 *
 * 순서를 정해 그 왕복을 줄이는 것이 옳지만 그건 나중 일이고, 지금 당장 필요한 것은 **실행하는
 * 쪽이 그 먼 자리를 찾아갈 수 있게 하는 것**이다. 첫 스텝이 `StagePosition >= 4` 를 요구하는데
 * 초기화 직후 그 값이 0이면, 아무 말도 없이 시작하는 것은 "알아서 네 번 이겨라"와 같다.
 *
 * ## 길을 찾아 주지는 않는다
 *
 * 어떻게 가는지는 실행하는 쪽과 지도가 풀 문제다. 여기서 하는 것은 **무엇이 참이어야 시작하나**를
 * 한 줄로 적는 일뿐이고, 그 재료는 전부 지도에서 온다 — 값 이름, 얼마나, 어디서 오르는지.
 *
 * ## 없으면 안 적는다
 *
 * 시작 상태로도 성립하는 시나리오에는 아무 말도 붙이지 않는다. 매번 한 줄이 붙으면 그것이
 * 곧 소음이고, 정작 먼 자리에서 시작하는 시나리오가 묻힌다.
 */
object ScenarioOpeningNote {

    /**
     * @param requirements 이 시나리오가 시작하려면 참이어야 하는 것들 — `값 → (비교, 오르는 화면들)`.
     *   앞 스텝이 만들어 주는 것은 부르는 쪽이 이미 걸러서 준다.
     *
     * @return 첫 스텝 앞에 붙일 한 줄. 적을 것이 없으면 null.
     */
    fun of(requirements: List<Requirement>): String? {
        if (requirements.isEmpty()) return null
        val said = requirements.joinToString(", ") { phrase(it) }
        return "$said 인 상태에서 시작한다. 게임을 처음부터 시작했다면 거기까지 진행한 뒤 이어서 한다."
    }

    /**
     * @property variable 값 이름.
     * @property comparison `>= 4` 처럼 요구하는 자리.
     * @property raisedIn 그 값이 오르는 화면들. 비어 있으면 지도가 말하지 않는 것이라 적지 않는다 —
     *   모르는 자리를 아는 척하면 실행하는 쪽이 없는 곳을 찾아 헤맨다.
     */
    data class Requirement(
        val variable: String,
        val comparison: String,
        val raisedIn: List<String>,
    )

    private fun phrase(requirement: Requirement): String {
        val head = "${requirement.variable} ${requirement.comparison}"
        if (requirement.raisedIn.isEmpty()) return head
        return "$head (${requirement.raisedIn.joinToString(" · ")} 에서 오른다)"
    }
}
