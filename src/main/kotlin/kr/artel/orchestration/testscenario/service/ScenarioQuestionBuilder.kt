package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioQuestion
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionOption
import kr.artel.orchestration.testscenario.dto.ScenarioQuestionSource

/**
 * 저장하고 나서 **한 가지를 되묻는다**(ARTEL-487).
 *
 * 코드가 이미 아는 것들이라 선택지까지 계산된다 — 메우지 못한 구간, 빠진 갈래, 담은 범위.
 * 지금까지 이것들은 통보로만 나갔고, 사용자는 읽고 나서 무엇을 어떻게 말해야 반영되는지 스스로
 * 지어내야 했다. 보기를 주면 한 번 누르는 것으로 끝난다.
 *
 * **한 번에 하나만 묻는다.** 여러 개를 쌓으면 사용자는 어느 것에 답한 것인지 말해 줄 방법이
 * 없고, 화면도 답을 어디에 실어 보낼지 정하지 못한다. 그래서 우선순위가 필요하다:
 *
 * 1. **메우지 못한 구간** — 실행하면 거기서 멎는다. 나머지 둘은 결과가 좁을 뿐 돌아는 간다.
 * 2. **빠진 갈래** — 무엇을 물어야 하는지가 가장 또렷하다(그 케이스를 넣을지 말지).
 * 3. **담은 범위** — 애매한 요청에서 경계가 흔들린 자리. 실측에서 회차마다 갈렸다.
 *
 * 보기 문구를 **사용자가 할 말 그대로** 쓴다("나머지도 담아 줘"). 고른 답은 그 문장으로 모델에게
 * 되돌아가므로, 모델이 따로 해석할 것이 없다 — 오케에 새 실행 경로를 만들지 않아도 된다.
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
     * @param untestedArms `담긴 케이스 → 빠진 갈래` 쌍.
     * @param scope 씬별 `담김/전체`. 전부 담긴 씬은 빼고 넘긴다.
     */
    fun from(
        blockedGaps: List<String>,
        untestedArms: List<Pair<Long, Long>>,
        scope: List<Triple<String, Int, Int>>,
        describe: (Long) -> String = { "" },
    ): ScenarioQuestion? {
        blockedGaps.distinct().firstOrNull()?.let { return gapQuestion(it) }
        untestedArms.firstOrNull()?.let { return armQuestion(it.first, it.second, describe) }
        if (scope.isNotEmpty()) return scopeQuestion(scope)
        return null
    }

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

    /**
     * 갈래 하나만 담긴 자리. 답이 둘 중 하나라 보기로 끝난다.
     *
     * **번호로 부르지 않는다.** 내부 case_id 는 사용자에게 내보내지 않는 값이고(화면은 등장
     * 순번만 쓴다), "1119번"은 읽는 사람에게 아무 뜻도 아니다. 씬·스텝·사전조건으로 부르면
     * 사용자도 알아보고 모델도 어느 케이스인지 좁힐 수 있다. id 는 `id` 필드로만 오간다.
     */
    private fun armQuestion(taken: Long, missing: Long, describe: (Long) -> String): ScenarioQuestion {
        val other = describe(missing).ifBlank { "같은 자리의 다른 갈래" }
        return ScenarioQuestion(
            id = "arm:$taken:$missing",
            text = "$other 도 시나리오로 만들까요?",
            why = describe(taken).ifBlank { null }?.let {
                "$it 과 같은 자리의 다른 갈래인데, 둘은 동시에 성립할 수 없어 함께 담을 수 없습니다."
            } ?: "같은 자리의 다른 갈래인데, 둘은 동시에 성립할 수 없어 함께 담을 수 없습니다.",
            // **보기는 짧게.** 무엇에 대한 답인지는 오케가 질문 문장을 붙여 보내므로 여기서
            // 되풀이할 필요가 없다 — 화면에서는 질문을 그대로 옮겨 적은 버튼이 세 줄로 접힌다.
            options = listOf(
                ScenarioQuestionOption("add", "네, 만들어 주세요", other),
                ScenarioQuestionOption("skip", "이번엔 그대로 두기"),
            ),
            source = ScenarioQuestionSource.CODE,
        )
    }

    /**
     * 담은 범위. 씬 이름을 보기로 풀어 **어디를 더 담을지 고르게** 한다 — "나머지 전부"만 주면
     * 스물몇 건짜리 씬까지 딸려 오고, 그것이 사용자가 원한 것인지는 알 수 없다.
     */
    private fun scopeQuestion(scope: List<Triple<String, Int, Int>>): ScenarioQuestion {
        val tally = scope.joinToString(" · ") { (scene, taken, all) -> "$scene $taken/$all" }
        return ScenarioQuestion(
            id = "scope:" + scope.joinToString(",") { it.first },
            text = "덜 담긴 씬이 있습니다. 더 담을까요?",
            why = "이번에 담은 범위 — $tally",
            options = scope.map { (scene, taken, all) ->
                ScenarioQuestionOption("scene:$scene", "$scene 마저 담기", "지금 $taken/$all 입니다.")
            } + ScenarioQuestionOption("keep", "이대로 좋아"),
            source = ScenarioQuestionSource.CODE,
        )
    }
}
