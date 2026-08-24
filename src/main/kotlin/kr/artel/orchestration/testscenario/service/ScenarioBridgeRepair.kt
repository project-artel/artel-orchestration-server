package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource

/**
 * 검증 스텝 **사이**를 계산된 경로로 메운다(ARTEL-468). DB를 모른다 — 답은 [ScenarioPathService]가
 * 내고 여기서는 스텝 배열만 만든다.
 *
 * **지적으로는 안 고쳐진다는 것이 실측(2026-08-18)이다.** 검사가 위반을 짚어 주고 다시 쓰게 해도
 * 해소 **0/3**, 오히려 늘어난 경우가 **2/3**였다. 같은 조건에서 코드가 계산값을 끼워 넣자 **0%,
 * 5회 전부**였다. 그래서 이 자리는 재작성 요청이 아니라 삽입이다.
 *
 * **케이스 순서 자체는 고치지 않는다.** `StagePosition == 2` 인 자리에서 `== 1` 을 요구하는 케이스를
 * 고른 것 같은 실수는 무엇을 검증할 것인가에 대한 판단이고, 그건 모델과 사람의 몫이다. 코드는
 * 고른 순서를 그대로 두고 **사이만** 메운다 — 순서까지 바꾸면 사용자가 요청한 것과 다른 시나리오가
 * 조용히 저장된다.
 */
object ScenarioBridgeRepair {

    /** 되풀이하라고 적힌 홉의 표시([ScenarioPathService] 가 붙인다). 그런 홉은 빼지 않는다. */
    private const val REPEATS = "되풀이한다"

    /**
     * 메울 자리 하나. [at]은 두 검증 구간 사이에 있는 브리지 스텝들의 범위이고 **비어 있을 수 있다** —
     * 씬을 통째로 건너뛴 결과가 바로 그 모양(빈 자리)이라, 길이 0인 구간이 오히려 주된 대상이다.
     */
    data class Gap(val fromCaseId: Long, val toCaseId: Long, val at: IntRange)

    /**
     * @property steps 교정된 스텝 배열.
     * @property notices 미상으로 남은 구간을 사람에게 알릴 문장. 비어 있으면 알릴 것이 없다.
     */
    data class Repaired(val steps: List<ChatScenarioStep>, val notices: List<String>)

    /**
     * 물어봐야 할 구간들. 같은 케이스를 검증하는 스텝들 사이는 묻지 않는다 — 그 안의 행위는 그
     * 케이스가 근거이고, 자기 자신으로 가는 길을 묻는 것은 답이 정해져 있다.
     *
     * 시나리오의 맨 앞·맨 뒤 브리지는 대상이 아니다. 한쪽 좌표가 없어 "어디서 어디로"가 성립하지
     * 않고, 비교할 상대가 없는 것을 틀렸다고 말할 수는 없다.
     */
    fun gaps(steps: List<ChatScenarioStep>): List<Gap> {
        val verified = steps.indices.filter { steps[it].caseId != null }
        return verified.zipWithNext()
            .filter { (a, b) -> steps[a].caseId != steps[b].caseId }
            .map { (a, b) -> Gap(steps[a].caseId!!, steps[b].caseId!!, (a + 1) until b) }
    }

    /**
     * [answers]는 [gaps]가 낸 순서와 같은 인덱스로 준다. 값이 없는 구간(조회 실패)은 손대지 않는다 —
     * 확인하지 못한 것을 고치면 그건 지어내는 것이다.
     */
    fun apply(
        steps: List<ChatScenarioStep>,
        answers: Map<Int, ScenarioPathAnswer>,
        describe: (Long) -> String = { "" },
    ): Repaired {
        val gaps = gaps(steps)
        // 메울 자리가 없어도 접기는 한다 — 중복은 사이가 비어서 생기는 것이 아니다.
        if (gaps.isEmpty()) return Repaired(collapseRepeats(steps.map { ground(it) }), emptyList())

        val out = mutableListOf<ChatScenarioStep>()
        val notices = mutableListOf<String>()
        var cursor = 0

        gaps.forEachIndexed { index, gap ->
            while (cursor < gap.at.first) out += ground(steps[cursor++])
            val existing = steps.subList(gap.at.first, gap.at.last + 1)
            val answer = answers[index]
            if (answer == null) {
                out += existing
            } else {
                val filled = bridge(existing, dropRepeatOf(steps.getOrNull(gap.at.first - 1), answer))
                out += filled
                // 알림은 **알림 블록이 실제로 들어갔을 때만** 낸다. 사람이 이미 손으로 채운 구간까지
                // 말하면, 답을 준 사용자에게 같은 것을 다시 묻는 꼴이 된다.
                if (filled.any { it.stepKind == ScenarioStepKind.GAP }) notices += notice(gap, answer, describe)
                // 뒤집으면 이어지는 자리는 **메운 사실만으로 끝내지 않는다.** 실행되게 스텝은
                // 넣되(그 편이 쓸 수 있다), 순서를 봐야 한다는 것을 말한다 — 이동 스텝으로 덮고
                // 조용히 지나가면 사용자는 순서가 이상하다는 것만 보고 이유를 모른다.
                if (answer.ordering == ScenarioOrdering.REVERSED) notices += reversed(gap, filled.size, describe)
                // 대신 **무엇을 사용자 말로 채웠는지는 말한다.** 사람이 말했다는 것은 코드가 확인할
                // 수 없는 주장이라, 검사할 수 없는 것은 보이게 하는 것이 유일한 대비다 — 말한 당사자가
                // 화면에서 그것을 읽고 아니라고 할 수 있다.
                else if (answer.result == ScenarioPathResult.UNKNOWN &&
                    filled.any { it.stepSource == ScenarioStepSource.HUMAN }
                ) {
                    notices += attributed(gap, answer, describe)
                }
            }
            cursor = gap.at.last + 1
        }
        while (cursor < steps.size) out += ground(steps[cursor++])

        return Repaired(collapseRepeats(out), notices.distinct())
    }

    /**
     * 잇달아 **완전히 같은** 스텝은 하나로 접는다(ARTEL-468).
     *
     * 실측(런 146)에서 모델이 `StoryScene에서 Space 입력을 한다` 를 글자까지 똑같이 두 줄 썼다.
     * 바로 다음 줄에 자기가 `…마지막 내용에 도달할 때까지 반복한다` 라고 적어 놓고서다. 몇 번
     * 눌러야 하는지 알 길이 없으니 "모르니까 두 번쯤"이 된 것이고, 그 근거가 없는 이유는
     * **지도가 아직 `repeat_until_done` 을 채우지 않기 때문**이다(골든 지도의 기능 324개가 전부
     * false 다). 반복은 코드가 브리지를 쓸 때만 문장이 되고, 모델이 쓴 검증 스텝에는 규칙이 없었다.
     *
     * 접어도 판정은 그대로다 — 연속된 같은 `case_id` 는 그 케이스의 검증 **구간**이고 판정은 구간의
     * 마지막에서 한 번 난다. 반대로 접지 않으면 소음으로 끝나지 않는다: 같은 조작이 한 번 더 들어가면
     * 대화가 한 줄 더 넘어가 **다음 스텝의 전제가 어긋난다**(런 146 의 `마지막 대화` 스텝이 그 자리다).
     *
     * **글자까지 같을 때만 접는다.** "비슷한" 두 줄을 같다고 보는 것은 판단이고, 두 번 눌러야 하는
     * 조작을 코드가 한 번으로 줄이면 그건 시나리오를 틀리게 고치는 것이다. 같은 문장이 떨어져 있으면
     * (사이에 다른 스텝이 있으면) 그대로 둔다 — 그 사이에 상태가 바뀌었을 수 있다.
     */
    fun collapseRepeats(steps: List<ChatScenarioStep>): List<ChatScenarioStep> =
        steps.filterIndexed { index, step ->
            index == 0 || !identical(steps[index - 1], step)
        }

    private fun identical(a: ChatScenarioStep, b: ChatScenarioStep): Boolean =
        a.caseId == b.caseId &&
            a.action.trim() == b.action.trim() &&
            a.input == b.input &&
            a.stepSource == b.stepSource &&
            a.stepKind == b.stepKind

    /**
     * 한 구간의 스텝들.
     *
     * 사람(모델)이 쓴 문장은 **살린다.** 계산된 경로와 같은 수만큼은 문장을 그대로 두고 근거만
     * 얹고, 모자란 만큼을 뒤에 붙인다. 문장을 계산값으로 갈아치우면 "골드가 충분한 상태로 상점에
     * 들어간다" 같은 의도 설명이 "B 키를 누른다"로 바뀌어, 실행은 되지만 무엇을 하려는지 읽을 수
     * 없는 시나리오가 된다.
     *
     * 계산된 경로보다 **많이** 쓴 나머지는 그대로 둔다. 명세에 없다는 것이 틀렸다는 뜻은 아니고,
     * 여기서 지워 버리면 관측되지 않은 조작을 코드가 매번 삼키게 된다.
     */
    /**
     * 앞 케이스가 **방금 한 조작**을 첫 홉이 되풀이하면 그 홉을 뺀다(ARTEL-468).
     *
     * 런 149 에서 실제로 나온 모양이다:
     *
     * ```
     * 3  TC1248  CASE        click:Canvas/MapSceneButton   MapSceneButton 를 클릭한다
     * 4          CAPABILITY  click:Canvas/MapSceneButton   MapSceneButton 을(를) 클릭한다 (TitleScene → StoryScene)
     * ```
     *
     * 경로 계산이 "그 버튼을 누르면 StoryScene 으로 간다"고 답했는데, 케이스가 이미 그 버튼을
     * 누르고 있었다. 두 번 누르면 두 번째 클릭은 **다음 화면에서** 일어난다.
     *
     * 이미 [ScenarioReconcileService.performs] 가 같은 것을 막으려 하지만 **근거 키로 대조한다.**
     * 지도의 UI 기능은 `method_id` 가 비어 있는 일이 흔해(`Canvas/MapSceneButton` 이 그렇다) 대조할
     * 것이 없으면 못 알아본다. 조작 값은 그 대신 늘 있다 — 같은 `input` 이면 같은 조작이다.
     *
     * **첫 홉만** 본다. 그 뒤의 홉은 앞 조작이 일으킨 화면에서 일어나는 일이라 같은 값이어도
     * 되풀이가 아니다. 되풀이해야 하는 조작(`…되풀이한다`)도 빼지 않는다 — 그건 명세가 여러 번
     * 하라고 말한 자리다.
     */
    private fun dropRepeatOf(previous: ChatScenarioStep?, answer: ScenarioPathAnswer): ScenarioPathAnswer {
        val done = previous?.takeIf { it.caseId != null }?.input ?: return answer
        val first = answer.inputs.firstOrNull() ?: return answer
        if (first != done) return answer
        if (answer.actions.firstOrNull()?.contains(REPEATS) == true) return answer
        return answer.copy(
            capabilityIds = answer.capabilityIds.drop(1),
            actions = answer.actions.drop(1),
            inputs = answer.inputs.drop(1),
        )
    }

    private fun bridge(existing: List<ChatScenarioStep>, answer: ScenarioPathAnswer): List<ChatScenarioStep> {
        if (answer.result == ScenarioPathResult.NOT_REQUIRED) return existing
        // 사람이 손으로 채운 구간은 그대로 둔다. 명세가 모르는 것을 사용자가 알려준 자리라,
        // 계산값으로 덮으면 답을 받고도 버리는 셈이 된다.
        if (existing.any { it.stepSource == ScenarioStepSource.HUMAN }) return existing

        val known = answer.actions.indices.map { i ->
            val capabilityId = answer.capabilityIds.getOrNull(i)
            // `input` 은 계산된 조작(`key:Return`)이다. 모델이 쓴 문장을 살릴 때도 이 값은 덮어쓴다 —
            // 문장은 의도를 적은 것이고 이쪽은 실행하는 쪽이 그대로 쓰는 값이라, 둘이 다르면
            // 맞는 쪽은 명세에서 온 이것이다.
            val input = answer.inputs.getOrNull(i)
            existing.getOrNull(i)?.copy(
                input = input ?: existing[i].input,
                stepSource = ScenarioStepSource.CAPABILITY,
                stepKind = ScenarioStepKind.ACTION,
                stepSourceCapabilityId = capabilityId,
                stepUnknownReason = null,
            ) ?: ChatScenarioStep(
                action = answer.actions[i],
                input = input,
                stepSource = ScenarioStepSource.CAPABILITY,
                stepKind = ScenarioStepKind.ACTION,
                stepSourceCapabilityId = capabilityId,
            )
        }
        val leftover = existing.drop(answer.actions.size)
        if (answer.result == ScenarioPathResult.KNOWN) return known + leftover

        // 모르는 구간은 **빈 자리로 두지 않는다.** 아무 표시도 없으면 실행하는 쪽에서 그냥 이어지는
        // 줄로 읽히고, 그때는 막힌 이유를 아무도 모른다.
        //
        // 그렇다고 **할 일로 두지도 않는다.** 스텝으로 넣으면 "명세에 없다"는 문장을 실행 에이전트가
        // 수행하려 들고 판정 대상으로도 세어져, 못 한 일이 실패로 기록된다. 알림 블록 하나로 바꾼다.
        return known + gap(answer, leftover)
    }

    /**
     * 메우지 못한 자리를 알리는 블록 하나(ARTEL-468).
     *
     * 모델이 그 자리에 써 둔 문장이 있으면 **버리지 않고 제안으로 인용한다.** 지어낸 조작일 수는
     * 있어도 사람이 답을 아는 실마리이고, 확인되지 않은 것을 할 일처럼 두지만 않으면 된다.
     */
    private fun gap(answer: ScenarioPathAnswer, guesses: List<ChatScenarioStep>): ChatScenarioStep {
        val reason = answer.blockedBy ?: "unknown"
        val suggestion = guesses.map { it.action }.filter { it.isNotBlank() }
        return ChatScenarioStep(
            action = buildString {
                append("이 구간의 경로를 확인할 수 없습니다 — ")
                append(answer.note.ifBlank { "$reason 를 만드는 방법이 명세에 없습니다." })
                append(" 실행 방법을 알려주시면 스텝으로 채웁니다.")
                if (suggestion.isNotEmpty()) {
                    append(" (작성 중 제안된 것: ")
                    append(suggestion.joinToString(" · "))
                    append(")")
                }
            },
            stepSource = ScenarioStepSource.UNKNOWN,
            stepKind = ScenarioStepKind.GAP,
            stepUnknownReason = reason,
        )
    }

    /** 검증 스텝의 근거는 그 케이스다. 다른 것이 적혀 있으면 덮어쓴다 — 계약상 다른 답이 없다. */
    private fun ground(step: ChatScenarioStep): ChatScenarioStep =
        if (step.caseId == null) step
        else step.copy(
            stepSource = ScenarioStepSource.CASE,
            stepKind = ScenarioStepKind.ACTION,
            stepSourceCapabilityId = null,
            stepUnknownReason = null,
        )

    /**
     * 스텝에만 적고 말하지 않으면 사용자는 모른다 — 프로토타입에서 코드가 조용히 채우자 "모른다"고
     * 말한 실행이 4/5에서 1/5로 줄었다. 그래서 무엇이 막았는지를 문장에 담는다.
     */
    /**
     * 뒤집으면 그대로 이어지는 자리. **틀렸다고 말하지 않는다.**
     *
     * 게임이 선형이라는 보장이 없다. 같은 값을 양방향으로 움직이는 케이스가 실제로 있고
     * (실측에서 `position 0→1` 과 `1→0` 이 둘 다 케이스다), 되돌아가서 확인하는 것이 요청한
     * 바로 그것일 수 있다. 그래서 판정이 아니라 **비용**을 말한다 — 이 순서는 사이 스텝 몇 개를
     * 쓰고, 뒤집으면 그것 없이 이어진다는 사실. 어느 쪽이 맞는지는 사람이 안다.
     *
     * 코드가 재배열하지 않는 이유도 같다. 순서는 판단이고, 코드가 바꾸면 사용자가 요청한 것과
     * 다른 시나리오가 조용히 저장된다.
     */
    private fun reversed(gap: Gap, inserted: Int, describe: (Long) -> String): String =
        "${between(gap, describe)} 구간은 이 순서로는 되돌아가는 스텝 ${inserted}개가 필요합니다. " +
            "순서를 뒤집으면 두 케이스가 선언한 상태가 그대로 이어집니다 — 되돌아가는 것이 " +
            "의도라면 그대로 두셔도 됩니다."

    /**
     * 두 검증 사이를 **사람이 알아보는 말로** 부른다.
     *
     * 내부 case_id 를 사용자에게 내보내지 않는다 — 화면은 등장 순번만 쓰고 에이전트 프롬프트에도
     * 같은 금지가 있다. 부를 말이 없으면 자리만 말한다.
     */
    private fun between(gap: Gap, describe: (Long) -> String): String {
        val from = describe(gap.fromCaseId)
        val to = describe(gap.toCaseId)
        return if (from.isBlank() || to.isBlank()) "두 검증 사이" else "$from 에서 $to 로 가는"
    }

    /** 사용자 말로 채운 구간. 그렇게 적혔다는 사실을 당사자에게 되돌려 준다. */
    private fun attributed(gap: Gap, answer: ScenarioPathAnswer, describe: (Long) -> String): String =
        "${between(gap, describe)} 의 ${answer.blockedBy ?: "구간"} 은(는) 명세에 없어 " +
            "알려주신 방법으로 채웠습니다. 다르면 고쳐 주세요."

    private fun notice(gap: Gap, answer: ScenarioPathAnswer, describe: (Long) -> String): String =
        "${between(gap, describe)} 의 ${answer.blockedBy ?: "구간"} 은(는) 명세에 없어 " +
            "실행 방법 미상으로 두었습니다. 알려주시면 채웁니다."
}
