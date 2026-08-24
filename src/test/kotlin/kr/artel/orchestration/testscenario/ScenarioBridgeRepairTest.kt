package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.service.ScenarioBridgeRepair
import kr.artel.orchestration.testscenario.service.ScenarioOrdering
import kr.artel.orchestration.testscenario.service.ScenarioPathAnswer
import kr.artel.orchestration.testscenario.service.ScenarioPathResult
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 삽입 규칙(ARTEL-468). 경로 계산은 [kr.artel.orchestration.testscenario.service.ScenarioPathService]가
 * 하고 여기서는 **그 답으로 스텝 배열을 어떻게 만드는가**만 본다 — 그래서 DB가 필요 없다.
 */
class ScenarioBridgeRepairTest {

    private fun verify(caseId: Long, action: String = "검증") = ChatScenarioStep(action = action, caseId = caseId)
    private fun bridge(action: String) = ChatScenarioStep(action = action)

    private fun known(vararg pairs: Pair<Long, String>) = ScenarioPathAnswer(
        result = ScenarioPathResult.KNOWN,
        capabilityIds = pairs.map { it.first },
        actions = pairs.map { it.second },
    )

    // ---- 구간 찾기 -------------------------------------------------------------------

    @Test
    fun `붙어 있는 두 검증 사이도 구간이다`() {
        // 씬을 통째로 건너뛴 결과가 바로 이 모양이다 — 빈 자리라서 눈에 안 띄는 것이 문제였다.
        val gaps = ScenarioBridgeRepair.gaps(listOf(verify(1), verify(2)))

        assertThat(gaps).hasSize(1)
        assertThat(gaps.single().fromCaseId).isEqualTo(1)
        assertThat(gaps.single().toCaseId).isEqualTo(2)
        assertThat(gaps.single().at).isEmpty()
    }

    @Test
    fun `같은 케이스를 검증하는 스텝들 사이는 묻지 않는다`() {
        val gaps = ScenarioBridgeRepair.gaps(listOf(verify(1), bridge("입력한다"), verify(1)))

        assertThat(gaps).isEmpty()
    }

    @Test
    fun `맨 앞과 맨 뒤 브리지는 구간이 아니다`() {
        // 한쪽 좌표가 없어 "어디서 어디로"가 성립하지 않는다.
        val gaps = ScenarioBridgeRepair.gaps(listOf(bridge("게임을 켠다"), verify(1), bridge("종료한다")))

        assertThat(gaps).isEmpty()
    }

    // ---- 삽입 -----------------------------------------------------------------------

    @Test
    fun `빈 자리에 계산된 경로를 끼워 넣는다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(0 to known(11L to "Enter 키를 누른다", 12L to "우측 문을 클릭한다")),
        )

        assertThat(repaired.steps.map { it.action })
            .containsExactly("검증", "Enter 키를 누른다", "우측 문을 클릭한다", "검증")
        assertThat(repaired.steps[1].stepSource).isEqualTo(ScenarioStepSource.CAPABILITY)
        assertThat(repaired.steps[1].stepSourceCapabilityId).isEqualTo(11L)
        assertThat(repaired.notices).isEmpty()
    }

    @Test
    fun `사람이 쓴 문장은 살리고 근거만 얹는다`() {
        // 문장을 계산값으로 갈아치우면 무엇을 하려는 스텝인지 읽을 수 없게 된다.
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), bridge("상점으로 이동한다"), verify(2)),
            mapOf(0 to known(11L to "우측 문을 클릭한다")),
        )

        assertThat(repaired.steps.map { it.action })
            .containsExactly("검증", "상점으로 이동한다", "검증")
        assertThat(repaired.steps[1].stepSource).isEqualTo(ScenarioStepSource.CAPABILITY)
        assertThat(repaired.steps[1].stepSourceCapabilityId).isEqualTo(11L)
    }

    @Test
    fun `모자란 만큼만 뒤에 붙인다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), bridge("맵으로 나간다"), verify(2)),
            mapOf(0 to known(11L to "ESC 키를 누른다", 12L to "스테이지 2를 클릭한다")),
        )

        assertThat(repaired.steps.map { it.action })
            .containsExactly("검증", "맵으로 나간다", "스테이지 2를 클릭한다", "검증")
        assertThat(repaired.steps[2].stepSourceCapabilityId).isEqualTo(12L)
    }

    @Test
    fun `계산된 경로보다 많이 쓴 나머지는 그대로 둔다`() {
        // 명세에 없다는 것이 틀렸다는 뜻은 아니다. 여기서 지우면 관측 안 된 조작을 코드가 삼킨다.
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), bridge("문을 연다"), bridge("잠시 기다린다"), verify(2)),
            mapOf(0 to known(11L to "우측 문을 클릭한다")),
        )

        assertThat(repaired.steps.map { it.action })
            .containsExactly("검증", "문을 연다", "잠시 기다린다", "검증")
        assertThat(repaired.steps[2].stepSource).isNull()
    }

    @Test
    fun `필요 없는 구간은 손대지 않는다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), bridge("한 번 더 본다"), verify(2)),
            mapOf(0 to ScenarioPathAnswer(ScenarioPathResult.NOT_REQUIRED)),
        )

        assertThat(repaired.steps.map { it.action }).containsExactly("검증", "한 번 더 본다", "검증")
    }

    @Test
    fun `조회하지 못한 구간은 손대지 않는다`() {
        // 확인하지 못한 것을 고치는 것은 지어내는 것이다.
        val steps = listOf(verify(1), bridge("어떻게든 간다"), verify(2))
        val repaired = ScenarioBridgeRepair.apply(steps, emptyMap())

        assertThat(repaired.steps.map { it.action }).containsExactly("검증", "어떻게든 간다", "검증")
        assertThat(repaired.steps[1].stepSource).isNull()
    }

    // ---- 모르는 구간 -----------------------------------------------------------------

    @Test
    fun `모르는 구간은 빈 자리로 두지 않는다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(
                0 to ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    blockedBy = "StagePosition",
                    note = "StagePosition 를 == 2 로 만드는 방법이 명세에 없다.",
                )
            ),
        )

        assertThat(repaired.steps).hasSize(3)
        assertThat(repaired.steps[1].stepSource).isEqualTo(ScenarioStepSource.UNKNOWN)
        assertThat(repaired.steps[1].stepUnknownReason).isEqualTo("StagePosition")
        // 할 일이 아니라 알림이다. 스텝으로 두면 실행이 이 문장을 수행하려 들고 판정 대상으로 센다.
        assertThat(repaired.steps[1].stepKind).isEqualTo(ScenarioStepKind.GAP)
        assertThat(repaired.steps[1].action)
            .contains("경로를 확인할 수 없습니다")
            .contains("스텝으로 채웁니다")
    }

    @Test
    fun `아는 데까지 넣고 나머지를 미상으로 남긴다`() {
        // 씬 이동은 알고 그 뒤 변수만 모르는 경우다. 아는 것까지 버릴 이유가 없다.
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(
                0 to ScenarioPathAnswer(
                    ScenarioPathResult.UNKNOWN,
                    capabilityIds = listOf(11L), actions = listOf("ESC 키를 누른다 (Battle → Map)"),
                    blockedBy = "StagePosition",
                )
            ),
        )

        assertThat(repaired.steps.map { it.stepSource }).containsExactly(
            ScenarioStepSource.CASE, ScenarioStepSource.CAPABILITY, ScenarioStepSource.UNKNOWN, ScenarioStepSource.CASE,
        )
        // 아는 데까지는 할 일, 나머지 한 줄만 알림이다.
        assertThat(repaired.steps.map { it.stepKind }).containsExactly(
            ScenarioStepKind.ACTION, ScenarioStepKind.ACTION, ScenarioStepKind.GAP, ScenarioStepKind.ACTION,
        )
    }

    @Test
    fun `모르는 구간에 모델이 쓴 문장은 알림 안에 제안으로 남긴다`() {
        // 지어낸 조작일 수는 있어도 사람이 답을 아는 실마리다. 버리지 않되, 확인되지 않은 것을
        // 할 일처럼 두지도 않는다.
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), bridge("전투를 이겨 다음 스테이지로 간다"), verify(2)),
            mapOf(0 to ScenarioPathAnswer(ScenarioPathResult.UNKNOWN, blockedBy = "StagePosition")),
        )

        assertThat(repaired.steps).hasSize(3)
        assertThat(repaired.steps[1].stepKind).isEqualTo(ScenarioStepKind.GAP)
        assertThat(repaired.steps[1].action).contains("전투를 이겨 다음 스테이지로 간다")
        // 수행 대상이 아니므로 케이스도 달리지 않는다.
        assertThat(repaired.steps[1].caseId).isNull()
    }

    @Test
    fun `아는 구간의 스텝은 할 일로 확정한다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(0 to known(11L to "Enter 키를 누른다")),
        )

        assertThat(repaired.steps.map { it.stepKind })
            .containsExactly(ScenarioStepKind.ACTION, ScenarioStepKind.ACTION, ScenarioStepKind.ACTION)
    }

    @Test
    fun `미상 구간은 무엇이 막았는지와 함께 사용자에게 알린다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(0 to ScenarioPathAnswer(ScenarioPathResult.UNKNOWN, blockedBy = "StoryScene→Map_scene")),
        )

        assertThat(repaired.notices).hasSize(1)
        // 막는 것의 이름은 싣고 케이스 번호는 싣지 않는다 — 부를 말이 없으면 자리만 말한다.
        assertThat(repaired.notices.single())
            .contains("StoryScene→Map_scene")
            .contains("두 검증 사이")
    }

    @Test
    fun `사람이 손으로 채운 구간은 알림으로 접지 않는다`() {
        // 명세가 모르는 것을 사용자가 알려준 자리다. 계산값으로 덮으면 답을 받고도 버리는 셈이다.
        val written = ChatScenarioStep(
            action = "튜토리얼 대화를 끝까지 넘긴 뒤 자동 전환을 기다린다",
            stepSource = ScenarioStepSource.HUMAN,
        )
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), written, verify(2)),
            mapOf(0 to ScenarioPathAnswer(ScenarioPathResult.UNKNOWN, blockedBy = "StoryScene→Map_scene")),
        )

        assertThat(repaired.steps).containsExactly(ground(verify(1)), written, ground(verify(2)))
        // 다시 묻지는 않는다. 다만 사람 말로 채웠다는 사실은 당사자에게 되돌려 준다 — 코드가
        // 확인할 수 없는 주장이라, 보이게 하는 것이 유일한 대비다.
        assertThat(repaired.notices.single())
            .contains("알려주신 방법으로 채웠습니다")
            .contains("StoryScene→Map_scene")
    }

    private fun ground(step: ChatScenarioStep) = step.copy(
        stepSource = ScenarioStepSource.CASE, stepKind = ScenarioStepKind.ACTION,
    )

    @Test
    fun `뒤집으면 이어지는 구간은 메우고도 순서를 말한다`() {
        // 실행되게 스텝은 넣는다. 다만 이동 스텝으로 덮고 조용히 지나가면 사용자는 순서가
        // 이상하다는 것만 보고 이유를 모른다.
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(0 to ScenarioPathAnswer(
                result = ScenarioPathResult.KNOWN,
                capabilityIds = listOf(11L), actions = listOf("RightArrow 키를 누른다"),
                ordering = ScenarioOrdering.REVERSED,
            )),
        )

        assertThat(repaired.steps.map { it.action })
            .containsExactly("검증", "RightArrow 키를 누른다", "검증")
        // 판정이 아니라 비용으로 말한다 — 되돌아가는 것이 의도일 수 있다(게임이 선형이 아니다).
        assertThat(repaired.notices.single())
            .contains("되돌아가는 스텝 1개가 필요합니다")
            .contains("의도라면 그대로 두셔도 됩니다")
            .doesNotContain("1번")
    }

    @Test
    fun `순서에 이견이 없으면 아무 말도 하지 않는다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(verify(1), verify(2)),
            mapOf(0 to known(11L to "Enter 키를 누른다")),
        )

        assertThat(repaired.notices).isEmpty()
    }

    // ---- 근거 덮어쓰기 ---------------------------------------------------------------

    @Test
    fun `케이스를 보는 스텝의 근거는 계약대로 덮어쓴다`() {
        val repaired = ScenarioBridgeRepair.apply(
            listOf(
                ChatScenarioStep(
                    action = "검증", caseId = 1L,
                    stepSource = ScenarioStepSource.CAPABILITY, stepSourceCapabilityId = 99L,
                ),
                verify(2),
            ),
            mapOf(0 to ScenarioPathAnswer(ScenarioPathResult.NOT_REQUIRED)),
        )

        assertThat(repaired.steps[0].stepSource).isEqualTo(ScenarioStepSource.CASE)
        assertThat(repaired.steps[0].stepSourceCapabilityId).isNull()
    }

    // --- 되풀이된 스텝 접기 (ARTEL-468) ---------------------------------------------

    @Test
    fun `잇달아 똑같은 스텝은 한 줄로 접는다`() {
        // 런 146 에서 실제로 나온 모양이다. 모델이 같은 문장을 두 줄 쓰고, 바로 다음 줄에
        // 자기가 "…까지 반복한다"라고 적었다. 몇 번인지 알 근거가 없어서 생기는 일이다.
        val space = ChatScenarioStep(action = "StoryScene에서 Space 입력을 한다.", caseId = 1255, input = "key:Space")
        val repeat = ChatScenarioStep(
            action = "마지막 내용에 도달할 때까지 Space 입력을 반복한다.", caseId = 1255, input = "key:Space",
        )

        val out = ScenarioBridgeRepair.collapseRepeats(listOf(space, space, repeat))

        assertThat(out.map { it.action }).containsExactly(space.action, repeat.action)
    }

    @Test
    fun `문구가 다르면 접지 않는다`() {
        // "비슷하다"는 판단이다. 두 번 눌러야 하는 조작을 코드가 한 번으로 줄이면 시나리오를
        // 틀리게 고치는 것이 된다.
        val first = ChatScenarioStep(action = "Space 입력을 한다.", caseId = 1255)
        val second = ChatScenarioStep(action = "Space 입력을 한 번 더 한다.", caseId = 1255)

        assertThat(ScenarioBridgeRepair.collapseRepeats(listOf(first, second))).hasSize(2)
    }

    @Test
    fun `사이에 다른 스텝이 있으면 접지 않는다`() {
        // 그 사이에 상태가 바뀌었을 수 있다. 떨어져 있는 같은 문장은 되풀이가 아니라 재방문이다.
        val space = ChatScenarioStep(action = "Space 입력을 한다.", caseId = 1255)
        val other = ChatScenarioStep(action = "대화 내용을 확인한다.", caseId = 1252)

        assertThat(ScenarioBridgeRepair.collapseRepeats(listOf(space, other, space))).hasSize(3)
    }

    @Test
    fun `케이스가 다르면 문장이 같아도 접지 않는다`() {
        // 검증 대상이 다르다. 같은 조작으로 다른 케이스를 보는 것은 정상이고, 접으면 한 케이스의
        // 판정 구간이 통째로 사라진다.
        val a = ChatScenarioStep(action = "Space 입력을 한다.", caseId = 1255)
        val b = ChatScenarioStep(action = "Space 입력을 한다.", caseId = 1250)

        assertThat(ScenarioBridgeRepair.collapseRepeats(listOf(a, b))).hasSize(2)
    }

    @Test
    fun `메울 자리가 없는 시나리오에서도 접는다`() {
        // 중복은 사이가 비어서 생기는 것이 아니다.
        val step = ChatScenarioStep(action = "확인한다.", caseId = 7)

        val repaired = ScenarioBridgeRepair.apply(listOf(step, step), emptyMap())

        assertThat(repaired.steps).hasSize(1)
    }
}
