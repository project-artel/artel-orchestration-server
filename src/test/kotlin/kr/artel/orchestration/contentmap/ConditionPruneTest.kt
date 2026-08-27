package kr.artel.orchestration.contentmap

import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.ConditionPrune
import kr.artel.orchestration.contentmap.evidence.GroupKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 군더더기를 걷되 **뜻은 건드리지 않는다**(ARTEL-624).
 *
 * 여기서 버는 것은 읽기 편함뿐이다. 하나라도 뜻이 바뀌면 전제가 조용히 거짓이 되고, 그것은
 * 이 시스템이 없애려는 바로 그것이다 — 그래서 확실하지 않으면 남기는 쪽을 시험한다.
 */
class ConditionPruneTest {

    @Test
    fun `바깥이 못 박은 비교를 안쪽 갈래에서 걷는다`() {
        // A 그리고 (X 또는 (Y 그리고 A))  →  A 그리고 (X 또는 Y)
        val pruned = ConditionPrune.of(
            every(lock, either(stage(1), every(stage(2), lock)))
        )

        assertThat(ConditionPrune.signature(pruned))
            .isEqualTo("every(|IsLocked|==|0,either(|StagePosition|==|1,|StagePosition|==|2))")
    }

    /**
     * **갈래 하나가 통째로 지워지면 그 OR 은 이미 참이다.**
     *
     * `A 그리고 (A 또는 X)` 에서 왼쪽 갈래는 바깥 조건만으로 참이라, OR 은 언제나 참이 된다.
     * 남기면 실행하는 사람이 만들 것이 있는 줄 알고 갈래를 들여다본다.
     */
    @Test
    fun `이미 참인 갈래가 있으면 그 무리는 사라진다`() {
        val pruned = ConditionPrune.of(every(lock, either(lock, stage(1))))

        assertThat(ConditionPrune.signature(pruned)).isEqualTo("|IsLocked|==|0")
    }

    /**
     * **함의를 따지지 않는다.** `x >= 1` 이 `x >= 2` 를 함의하는지는 값의 종류를 알아야 답할 수 있고,
     * 모르면서 지우면 전제가 거짓이 된다. 글자 그대로 겹칠 때만 걷는다.
     */
    @Test
    fun `비슷하지만 다른 비교는 남긴다`() {
        val pruned = ConditionPrune.of(every(stage(1), either(stage(2), lock)))

        assertThat(ConditionPrune.signature(pruned))
            .isEqualTo("every(|StagePosition|==|1,either(|StagePosition|==|2,|IsLocked|==|0))")
    }

    /**
     * **OR 갈래끼리는 아는 것을 나누지 않는다.** 한 갈래가 참이라고 옆 갈래가 참인 것은 아니다.
     */
    @Test
    fun `한 갈래가 참인 것을 옆 갈래에 물려주지 않는다`() {
        val pruned = ConditionPrune.of(either(every(lock, stage(1)), every(lock, stage(2))))

        assertThat(ConditionPrune.signature(pruned)).isEqualTo(
            "either(every(|IsLocked|==|0,|StagePosition|==|1),every(|IsLocked|==|0,|StagePosition|==|2))"
        )
    }

    /**
     * **코드 위치가 달라도 같은 비교다.** `Test` 는 `offset` 을 값에 넣어, 같은 검사가 다른 줄에서
     * 나오면 다른 것이 된다. 실측의 되풀이는 전부 다른 줄에서 오므로 위치를 보면 하나도 못 걷는다.
     */
    @Test
    fun `코드 위치가 달라도 같은 비교로 본다`() {
        val pruned = ConditionPrune.of(
            every(lock.copy(offset = 3), either(stage(1), every(stage(2), lock.copy(offset = 91))))
        )

        assertThat(ConditionPrune.signature(pruned))
            .isEqualTo("every(|IsLocked|==|0,either(|StagePosition|==|1,|StagePosition|==|2))")
    }

    private val lock = ConditionNode.Test("IsLocked", "==", "0", null, 0)

    private fun stage(value: Int) =
        ConditionNode.Test("StagePosition", "==", "$value", null, value)

    private fun every(vararg parts: ConditionNode) =
        ConditionNode.Group(GroupKind.EVERY, parts.toList())

    private fun either(vararg parts: ConditionNode) =
        ConditionNode.Group(GroupKind.EITHER, parts.toList())
}
