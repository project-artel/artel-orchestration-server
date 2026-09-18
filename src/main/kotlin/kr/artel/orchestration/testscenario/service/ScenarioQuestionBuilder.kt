package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioQuestion
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionOption
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionSource

/**
 * 저장하고 나서 **메우지 못한 구간을 되묻는다**(ARTEL-487).
 *
 * 코드가 이미 아는 것이라 선택지까지 계산된다. 통보로만 나가던 동안 사용자는 읽고 나서 무엇을
 * 어떻게 말해야 반영되는지 스스로 지어내야 했고, 보기를 주면 한 번 누르는 것으로 끝난다.
 *
 * **묻는 것은 Gap 하나로 좁혔다**(ARTEL-903). 앞서는 셋을 물었다 — 메우지 못한 구간, 빠진
 * 갈래, 담은 범위. 뒤의 둘을 걷어낸 기준은 **답이 시나리오를 바꾸는가**다.
 *
 * - Gap 은 답이 곧 스텝이 된다("저절로 넘어감" → 그 문장이 자리에 들어간다). 답하지 않으면
 *   실행하는 사람이 거기서 멎는다.
 * - 담은 범위는 씬별 집계였는데, **씬은 저작의 단위가 아니다.** 시나리오는 여러 씬을 지나는
 *   흐름이라 "TurnBattleScene 8/29" 로는 다음에 무엇을 할지 정할 수 없다. 저작이 씬 단위였던
 *   시절의 물음이다. 이 런이 무엇을 담았는지는 조회로 본다(`GET …/test-runs/{runId}/coverage`).
 * - 빠진 갈래는 [ScenarioReconcileService.siblingNotices] 의 **알림으로 남긴다.** 사실 자체는
 *   계산으로만 알 수 있어 말해 주어야 하지만, "더 만들까요" 는 답해도 이 시나리오가 나아지지
 *   않는 물음이라 카드를 차지할 값이 없다.
 *
 * 보기 문구를 **사용자가 할 말 그대로** 쓴다. 고른 답은 그 문장으로 모델에게 되돌아가므로,
 * 모델이 따로 해석할 것이 없다 — 오케에 새 실행 경로를 만들지 않아도 된다.
 */
object ScenarioQuestionBuilder {

    /** 미상 구간 질문의 id 앞머리. 뒤에는 막은 것(`StoryScene→Map_scene`)이 그대로 붙는다. */
    const val GAP_PREFIX = "gap:"

    /** 조작 없이 넘어간다는 답. 사용자가 따로 적지 않으면 이 문장이 스텝이 된다. */
    const val GAP_AUTO = "auto"

    /** 지금은 채우지 않겠다는 답. */
    const val GAP_LEAVE = "leave"

    /** [GAP_AUTO] 를 골랐을 때 자리에 남길 말. */
    const val AUTOMATIC_HOP = "조작 없이 다음 화면으로 전환될 때까지 기다린다."

    /**
     * **함께 담을 수 없는 케이스는 묻지 않는다**(ARTEL-497). 계산되는 것을 물어 왕복하는 동안
     * 값을 치르고 정확도를 잃었고, 답이 무엇을 뜻하는지 모델이 몰라 **같은 질문이 답할 때마다
     * 다시 나갔다**(런 152). 지금은 [ScenarioConflictSplit] 이 나누고, 나눴다고 알린다.
     *
     * @param blockedGaps 메우지 못한 구간을 막은 것들(씬 쌍 또는 변수명).
     */
    fun from(blockedGaps: List<String>): ScenarioQuestion? = all(blockedGaps).firstOrNull()

    /**
     * **모르는 자리를 전부 낸다**(ARTEL-630).
     *
     * 앞서는 [from] 이 하나만 골라 냈다. 막힌 자리가 여럿이면 나머지는 **아무 말 없이 미상으로**
     * 남고, 사용자는 시나리오가 완성된 줄 안다 — 실측(런 178)에서 못 간다고 적은 자리가 일곱인데
     * 물은 것은 하나였다.
     *
     * 하나만 묻던 것은 "같은 질문이 매 턴 다시 나가는" 것을 막으려던 것이었는데(런 152), 그건
     * **답한 질문을 다시 안 묻는 것**으로 풀 일이지 모르는 것을 감춰서 풀 일이 아니다. 한 번에
     * 다 보여 주면 아는 것만 답하고 나머지는 그대로 둘 수 있다.
     */
    fun all(blockedGaps: List<String>): List<ScenarioQuestion> =
        blockedGaps.distinct().map(::gapQuestion)

    /**
     * 길을 모르는 자리. **자유 서술이 본체이고 보기는 곁들이다** — "어떻게 가는가"는 보기로 다
     * 담기지 않는다. 그래도 두 가지는 보기로 둘 만하다: 저절로 넘어가는 경우와, 그대로 두는 경우.
     */
    private fun gapQuestion(blockedBy: String) = ScenarioQuestion(
        id = "$GAP_PREFIX$blockedBy",
        text = "$blockedBy 구간을 어떻게 넘어가나요?",
        why = "이 전환이 씬 명세에 없어 시나리오에 미상으로 남겨 두었습니다.",
        options = listOf(
            ScenarioQuestionOption(
                GAP_AUTO, "저절로 넘어감",
                "조작 없이 전환되는 구간이면 그렇게 적습니다.",
            ),
            ScenarioQuestionOption(
                GAP_LEAVE, "그대로 두기",
                "지금은 미상으로 두고 나중에 채웁니다.",
            ),
        ),
        source = ScenarioQuestionSource.CODE,
    )

}
