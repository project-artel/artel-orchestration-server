package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.service.Guard
import kr.artel.orchestration.testscenario.service.ScenarioStartCheck
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **처음부터 걸어갈 수 있나**(ARTEL-635).
 *
 * 지금까지의 검사는 전부 "이것들이 함께 설 수 있나"를 물었다. 그래서 아무도 "출발점에서 첫
 * 스텝이 서나"를 묻지 않았고, 실측(런 184)에서 절대 실행되지 않는 시나리오가 통과했다.
 */
class ScenarioStartCheckTest {

    private val raisedInBattle: (String) -> List<String> = { name ->
        if (name == "StagePosition") listOf("TurnBattleScene") else emptyList()
    }
    private val automatic: (String) -> Boolean = { it == "StagePosition" }

    /**
     * **실측 그대로의 모양**(런 184, 시나리오 510).
     *
     * ```
     * 스텝  1   StagePosition >= 1 요구
     * 스텝 13   TurnBattleScene 진입          ← 그 값을 올리는 유일한 길이 맨 끝
     * ```
     *
     * 초기화 직후 그 값은 0이다. 첫 스텝을 하려면 전투를 이겨야 하는데, 전투에 들어가는 것이
     * 이 시나리오의 마지막 스텝이다. 순환이고, 절대 실행되지 않는다.
     */
    @Test
    fun `값을 올리는 화면이 뒤에 있으면 첫 스텝에서 막힌다`() {
        val blocked = ScenarioStartCheck.firstBlocked(
            guards = mapOf(
                1 to listOf(Guard("StagePosition", ">=", "1")),
                13 to emptyList(),
            ),
            arrivesAt = mapOf(1 to null, 13 to "TurnBattleScene"),
            raisesValue = raisedInBattle,
            madeAutomatically = automatic,
        )

        assertThat(blocked).isNotNull
        assertThat(blocked!!.step).isEqualTo(1)
        assertThat(blocked.variable).isEqualTo("StagePosition")
        assertThat(blocked.needed).isEqualTo(">= 1")
        // 고칠 방법을 함께 든다 — "틀렸다"만 돌려주면 같은 것이 다시 온다(런 152).
        assertThat(blocked.raisedIn).containsExactly("TurnBattleScene")
    }

    /** 앞에서 그 화면을 지났으면 시나리오가 스스로 만든 것이다. 막을 일이 아니다. */
    @Test
    fun `값을 올리는 화면을 앞에서 지나면 통과한다`() {
        val blocked = ScenarioStartCheck.firstBlocked(
            guards = mapOf(1 to emptyList(), 2 to listOf(Guard("StagePosition", ">=", "1"))),
            arrivesAt = mapOf(1 to "TurnBattleScene", 2 to null),
            raisesValue = raisedInBattle,
            madeAutomatically = automatic,
        )

        assertThat(blocked).isNull()
    }

    /**
     * **지시로 만들 수 있는 값은 여기서 볼 일이 아니다.** 앞 스텝이 만들었을 수 있고, 그
     * 판단은 순서 검사와 나눔이 이미 한다 — 여기서 또 막으면 될 것을 못 하게 한다.
     */
    @Test
    fun `지시로 만드는 값은 막지 않는다`() {
        val blocked = ScenarioStartCheck.firstBlocked(
            guards = mapOf(1 to listOf(Guard("position", ">=", "2"))),
            arrivesAt = mapOf(1 to null),
            raisesValue = { emptyList() },
            madeAutomatically = { false },
        )

        assertThat(blocked).isNull()
    }

    /**
     * **시작 상태로도 성립하는 요구는 막지 않는다.** `!= 5` 와 `== 0` 은 초기화 직후에도 참일
     * 수 있다 — 막으면 될 것을 못 하게 한다.
     */
    @Test
    fun `시작 상태로 성립할 수 있는 요구는 막지 않는다`() {
        val guards = mapOf(
            1 to listOf(Guard("StagePosition", "!=", "5"), Guard("StagePosition", "==", "0")),
        )

        val blocked = ScenarioStartCheck.firstBlocked(
            guards, mapOf(1 to null), raisedInBattle, automatic,
        )

        assertThat(blocked).isNull()
    }

    /**
     * **아무 데서도 안 오르는 값**은 순서를 고쳐서 될 일이 아니다. 그래도 걸리기는 해야 한다 —
     * 그 케이스는 담을 수 없다는 뜻이고, 부르는 쪽이 그렇게 말한다.
     */
    @Test
    fun `아무 데서도 안 오르는 값도 걸리되 갈 곳을 못 든다`() {
        val blocked = ScenarioStartCheck.firstBlocked(
            guards = mapOf(1 to listOf(Guard("flag", ">=", "1"))),
            arrivesAt = mapOf(1 to null),
            raisesValue = { emptyList() },
            madeAutomatically = { true },
        )

        assertThat(blocked).isNotNull
        assertThat(blocked!!.raisedIn).isEmpty()
    }

    /** 첫 자리만 낸다. 다섯 줄을 늘어놓으면 무엇부터 고쳐야 하는지가 흐려진다. */
    @Test
    fun `막힌 자리가 여럿이어도 첫 것만 낸다`() {
        val blocked = ScenarioStartCheck.firstBlocked(
            guards = mapOf(
                2 to listOf(Guard("StagePosition", ">=", "3")),
                1 to listOf(Guard("StagePosition", ">=", "1")),
            ),
            arrivesAt = mapOf(1 to null, 2 to null),
            raisesValue = raisedInBattle,
            madeAutomatically = automatic,
        )

        assertThat(blocked!!.step).isEqualTo(1)
    }
}
