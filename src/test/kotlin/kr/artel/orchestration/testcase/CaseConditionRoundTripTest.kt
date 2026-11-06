package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.evidence.ConditionNode
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.evidence.GroupKind
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * **적은 모양 그대로 되읽힌다**(ARTEL-627).
 *
 * `test_case.condition` 은 지도 조회 API 가 쓰는 표현([ConditionNodeResponse])으로 적히고,
 * 되읽는 것은 근거 문서를 읽던 [EvidenceParser.parseCondition] 이다. 둘이 같은 모양이라는 것이
 * 이 설계의 전제인데, 그건 **두 파일이 각자 우연히 맞춰 놓은 것**이라 한쪽만 고치면 조용히 깨진다.
 *
 * 깨져도 오류가 안 난다. 파서는 모르는 모양을 [ConditionNode.Unknown] 으로 읽고 넘어가므로,
 * 저작은 "전제를 모르는 케이스"를 받아 아무 순서도 못 정한 채 그럴듯한 시나리오를 낸다.
 * 그래서 여기서 왕복을 못 박는다.
 */
class CaseConditionRoundTripTest {

    private val mapper = ObjectMapper()
    private val parser = EvidenceParser(mapper)

    @Test
    fun `비교 하나가 왕복한다`() {
        val test = ConditionNode.Test(
            left = "CombineButton.combineZone.activeSelf",
            operator = "!=",
            right = "0",
            context = "CombineButton",
            offset = 17,
        )

        assertThat(roundTrip(test)).isEqualTo(test)
    }

    /**
     * **주인을 잃지 않는다.** 문장으로 렌더하던 때는 `activeSelf` 만 남아 무엇의 속성인지 사라졌고,
     * 그래서 지도에서 그 값을 쓰는 기능을 영영 못 찾았다.
     */
    @Test
    fun `대상의 전체 이름이 남는다`() {
        val test = ConditionNode.Test("MapMove.StagePosition", ">=", "3", null, 0)

        assertThat((roundTrip(test) as ConditionNode.Test).left).isEqualTo("MapMove.StagePosition")
    }

    /**
     * **갈래가 남는다.** 문장에서는 `또는` 을 되읽을 때 모든 갈래에 공통인 비교만 남겼고, 그래서
     * `== 5 또는 == 4` 가 아무 자리도 말하지 못했다. 트리에는 둘 다 있다.
     */
    @Test
    fun `갈래가 통째로 남는다`() {
        val either = ConditionNode.Group(
            GroupKind.EITHER,
            listOf(
                ConditionNode.Test("MapMove.StagePosition", "==", "5", null, 1),
                ConditionNode.Test("MapMove.StagePosition", "==", "4", null, 2),
            ),
        )

        assertThat(roundTrip(either)).isEqualTo(either)
    }

    /** 중첩도 그대로다 — 실측의 사전조건이 `A 그리고 (B 또는 C)` 꼴이다. */
    @Test
    fun `중첩된 무리가 왕복한다`() {
        val nested = ConditionNode.Group(
            GroupKind.EVERY,
            listOf(
                ConditionNode.Test("InteractionLock.IsLocked", "==", "0", null, 0),
                ConditionNode.Group(
                    GroupKind.EITHER,
                    listOf(
                        ConditionNode.Test("MapMove.position", "==", "4", null, 3),
                        ConditionNode.Test("MapMove.position", "==", "5", null, 4),
                    ),
                ),
            ),
        )

        assertThat(roundTrip(nested)).isEqualTo(nested)
    }

    /** 조건 없음도 값이다. `always` 와 "모름"이 같은 값이 되면 저작이 전제를 지어낸다. */
    @Test
    fun `조건 없음이 왕복한다`() {
        assertThat(roundTrip(ConditionNode.Always)).isEqualTo(ConditionNode.Always)
    }

    /** 못 읽은 조건은 못 읽은 채로 남아야 한다 — 그것이 "단정하면 안 된다"는 표시다. */
    @Test
    fun `못 읽은 조건이 왕복한다`() {
        val unknown = ConditionNode.Unknown(reason = "condition-not-an-object", unread = "x && y")

        assertThat(roundTrip(unknown)).isEqualTo(unknown)
    }

    /** 입력 판정도 조건이다. */
    @Test
    fun `입력 조건이 왕복한다`() {
        val gesture = ConditionNode.Gesture(input = "key:Return (down)", offset = 9)

        assertThat(roundTrip(gesture)).isEqualTo(gesture)
    }

    /** 쓰는 쪽([ConditionNodeResponse])과 읽는 쪽([EvidenceParser])을 실제로 이어 본다. */
    private fun roundTrip(node: ConditionNode): ConditionNode =
        parser.parseCondition(
            mapper.readTree(mapper.writeValueAsString(ConditionNodeResponse.of(node)))
        )
}
