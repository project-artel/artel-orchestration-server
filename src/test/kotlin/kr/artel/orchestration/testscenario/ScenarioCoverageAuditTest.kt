package kr.artel.orchestration.testscenario

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ReviewedCases
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.service.ScenarioCoverageAudit
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * 검수 **규칙**을 못 박는다. DB를 태우지 않는 이유는, 통합 테스트만 있으면 "저장됐다/안 됐다"만
 * 알려주고 네 규칙 중 어느 것이 틀렸는지는 알려주지 않기 때문이다.
 *
 * 기준 사례는 실측이다(2026-08-13): word-venture 66건을 전부 쓰라고 시켰더니 65건만 담겼다.
 * 빠진 151번은 "Map_scene에서 Return 입력 → TurnBattleScene 전환"이었고, 그것을 빠뜨린 시나리오의
 * 제목이 "맵 화면 전투 진입과 튜토리얼"이었다 — 제목이 하겠다고 말한 일을 안 한 것이다.
 */
class ScenarioCoverageAuditTest {

    private val project = (1L..5L).toSet()

    private fun scenario(vararg caseIds: Long?) = ScenarioResult(
        title = "테스트 시나리오",
        steps = caseIds.map { ChatScenarioStep(action = "행위", caseId = it) },
    )

    @Test
    fun `판정이 없으면 검사를 통째로 건너뛴다`() {
        // 구버전 Agent와 함께 배포되기 위한 경로. 이 null 하나가 롤백 스위치다.
        val findings = ScenarioCoverageAudit.audit(project, reviewed = null, scenarios = listOf(scenario(99L)))

        assertThat(findings.rejected).isFalse()
        assertThat(findings.ghost).isEmpty()   // 99는 없는 번호지만 검사 자체를 안 했다
    }

    @Test
    fun `판정이 전량을 덮지 못하면 검토 누락으로 막는다`() {
        // in ∪ out = {1,2,3}. 4,5는 어느 배열에도 없다 = 보지 않았다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L, 2L), excluded = listOf(3L)),
            listOf(scenario(1L, 2L)),
        )

        assertThat(findings.unreviewed).containsExactly(4L, 5L)
        assertThat(findings.rejected).isTrue()
    }

    @Test
    fun `관련 있다고 판정해 놓고 안 담으면 막는다`() {
        // 실측 65 대 66이 이 모양이었다. 시나리오는 완성돼 보이고 아무 데도 표시가 없다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L, 2L, 3L), excluded = listOf(4L, 5L)),
            listOf(scenario(1L, 2L)),
        )

        assertThat(findings.missing).containsExactly(3L)
        assertThat(findings.unreviewed).isEmpty()
        assertThat(findings.rejected).isTrue()
    }

    @Test
    fun `없는 케이스 번호를 가리키면 막는다`() {
        // 지금은 이 값이 조용히 case=null로 저장된다. 지어낸 번호가 시나리오에 남는다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L), excluded = listOf(2L, 3L, 4L, 5L)),
            listOf(scenario(1L, 42L)),
        )

        assertThat(findings.ghost).containsExactly(42L)
        assertThat(findings.rejected).isTrue()
    }

    @Test
    fun `판정 밖 케이스를 담아도 막지는 않는다`() {
        // 자연어 요청에서 선언은 Agent 자신의 추정이다. 절대 기준으로 삼으면 1패스에서 좁게 잡은
        // 실수를 2패스가 고칠 길이 사라진다. 기록만 남기고 통과시킨다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L), excluded = listOf(2L, 3L, 4L, 5L)),
            listOf(scenario(1L, 2L)),
        )

        assertThat(findings.excess).containsExactly(2L)
        assertThat(findings.rejected).isFalse()
    }

    @Test
    fun `case_id 없는 브리지 스텝은 아무 규칙에도 걸리지 않는다`() {
        // 대부분의 스텝은 이동·준비라 검증 대상이 없다. 이걸 초과나 유령으로 세면 정상 저작이 전부 막힌다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L), excluded = listOf(2L, 3L, 4L, 5L)),
            listOf(scenario(null, 1L, null)),
        )

        assertThat(findings.rejected).isFalse()
        assertThat(findings.excess).isEmpty()
        assertThat(findings.ghost).isEmpty()
    }

    @Test
    fun `여러 시나리오에 나눠 담아도 합집합으로 센다`() {
        // 한 요청이 TS 여러 개를 낳는 것이 정상이다. 시나리오별로 세면 나눌수록 누락으로 보인다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L, 2L, 3L), excluded = listOf(4L, 5L)),
            listOf(scenario(1L), scenario(2L, 3L)),
        )

        assertThat(findings.rejected).isFalse()
        assertThat(findings.missing).isEmpty()
    }

    @Test
    fun `판정에 남의 번호가 섞여 있어도 검토 누락으로 세지 않는다`() {
        // 판정 배열의 쓰레기는 담기지 않는 한 문제가 아니다. 여기서 막으면 프로젝트 밖 id 하나에
        // 정상 저작이 전부 걸린다.
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L), excluded = listOf(2L, 3L, 4L, 5L, 999L)),
            listOf(scenario(1L)),
        )

        assertThat(findings.rejected).isFalse()
        assertThat(findings.unreviewed).isEmpty()
    }

    // ---- 스텝 근거(ARTEL-467) -----------------------------------------------------------
    //
    // 여기서 보는 것은 **형식뿐이다.** 인용한 기능이 실재하는지, `UNKNOWN`이 정말 모르는 길인지는
    // 씬 명세를 봐야 알고 그건 reconcile 쪽에서 대조한다.

    private fun step(
        caseId: Long? = null,
        source: ScenarioStepSource? = null,
        capabilityId: Long? = null,
        unknownReason: String? = null,
    ) = ChatScenarioStep(
        action = "행위", caseId = caseId, stepSource = source,
        stepSourceCapabilityId = capabilityId, stepUnknownReason = unknownReason,
    )

    private fun withSteps(vararg steps: ChatScenarioStep) =
        listOf(ScenarioResult(title = "t", description = "d", steps = steps.toList()))

    @Test
    fun `근거를 아예 안 보낸 스텝은 통과시킨다`() {
        // 이 필드가 없는 구버전 Agent와 함께 배포되기 위한 자리다. 되돌리는 스위치가 이 null 하나뿐이다.
        val findings = ScenarioCoverageAudit.audit(
            project, null, withSteps(step(caseId = 1L), step()),
        )

        assertThat(findings.ungrounded).isEmpty()
        assertThat(findings.rejected).isFalse()
    }

    @Test
    fun `케이스를 보는 스텝이 다른 근거를 대면 막는다`() {
        val findings = ScenarioCoverageAudit.audit(
            project, null,
            withSteps(step(caseId = 1L, source = ScenarioStepSource.CAPABILITY, capabilityId = 7L)),
        )

        assertThat(findings.ungrounded).hasSize(1)
        assertThat(findings.ungrounded.single().reason).contains("CASE여야")
        assertThat(findings.rejected).isTrue()
    }

    @Test
    fun `기능을 인용했는데 어느 기능인지 없으면 막는다`() {
        val findings = ScenarioCoverageAudit.audit(
            project, null, withSteps(step(source = ScenarioStepSource.CAPABILITY)),
        )

        assertThat(findings.ungrounded.single().reason).contains("어느 기능인지 없다")
    }

    @Test
    fun `모른다고만 하고 무엇이 막는지 안 적으면 막는다`() {
        val findings = ScenarioCoverageAudit.audit(
            project, null, withSteps(step(source = ScenarioStepSource.UNKNOWN)),
        )

        assertThat(findings.ungrounded.single().reason).contains("무엇이 막는지 없다")
    }

    @Test
    fun `무엇이 막는지 적은 모름은 통과시킨다`() {
        // 모른다는 것도 답이다. 지어낸 스텝과 구분되는 지점이 바로 이 문장이다.
        val findings = ScenarioCoverageAudit.audit(
            project, null,
            withSteps(step(source = ScenarioStepSource.UNKNOWN, unknownReason = "StagePosition")),
        )

        assertThat(findings.ungrounded).isEmpty()
        assertThat(findings.rejected).isFalse()
    }

    @Test
    fun `어긋난 스텝의 자리를 함께 낸다`() {
        // 건수만 알려주면 에이전트가 전체를 다시 쓰고, 그때 이미 통과한 부분까지 흔들린다.
        val findings = ScenarioCoverageAudit.audit(
            project, null,
            withSteps(step(caseId = 1L, source = ScenarioStepSource.CASE), step(source = ScenarioStepSource.CAPABILITY)),
        )

        val ref = findings.ungrounded.single()
        assertThat(ref.scenarioIndex).isEqualTo(0)
        assertThat(ref.stepIndex).isEqualTo(1)
    }

    @Test
    fun `막을 때는 이유를 사람 말로 낸다`() {
        val findings = ScenarioCoverageAudit.audit(
            project,
            ReviewedCases(included = listOf(1L, 2L), excluded = listOf(3L)),
            listOf(scenario(1L, 42L)),
        )

        // 저장되지 않았다는 사실과 이유가 둘 다 들어가야 한다 — 사용자는 이것 말고 알 길이 없다.
        assertThat(findings.rejectionMessage())
            .contains("검토하지 않았습니다")
            .contains("빠졌습니다")
            .contains("존재하지 않는")
            .contains("저장하지 않았습니다")
    }

    @Test
    fun `함께 담을 수 없는 케이스는 막지 않는다`() {
        // 한때는 막았다(ARTEL-466). 실제 요청이 "TurnBattleScene 24건을 담아줘"였고, 거절당한
        // 사용자에게는 다음 수가 없었다 — 재작성 대상도 아니다. 무엇을 어떤 묶음으로 검증할지는
        // 요청이 정하는 것이라 지금은 저장하고 되묻는다(ARTEL-497).
        val findings = ScenarioCoverageAudit.audit(project, null, withSteps(step(caseId = 1L)))
            .copy(conflicting = listOf(1284L to 1293L))

        assertThat(findings.rejected).isFalse()
        assertThat(findings.summary()).contains("동거 불가 1쌍")
    }
}
