package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ScenarioQuestion

/**
 * "그대로 두기"에 코드가 답한다(ARTEL-487).
 *
 * 되묻기의 보기 중 절반은 **아무것도 바꾸지 말라는 답**이다. 그 답을 모델에게 넘기고 있었는데,
 * 값을 치르고도 얻는 것이 없었다 — 모델이 시나리오를 그대로 돌려주면 검수가 다시 돌고, 조건이
 * 그대로이므로 **방금 거절한 질문이 다시 나갔다.** 사용자가 본 것은 "안 할래"를 누른 뒤 같은
 * 질문이 또 뜨는 화면이었다.
 *
 * 거절은 계산이다: 저작 결과가 바뀌지 않고, 무엇을 그대로 두었는지도 질문 id 에 들어 있다.
 * 그래서 모델을 부르지 않고 여기서 답한다.
 *
 * **다음 수를 함께 준다.** 거절이 막다른 길이 되면 사용자는 마음이 바뀌었을 때 무엇을 말해야
 * 하는지 스스로 지어내야 한다. 질문 종류마다 다음에 할 수 있는 일이 다르므로 문구도 다르다.
 */
object ScenarioDeclineReply {

    /**
     * "그대로 두기"류 보기의 id.
     *
     * **코드가 만든 질문의 보기만 있다.** 모델이 지어낸 보기는 무엇을 뜻하는지 코드가 알 수 없어
     * 평소대로 모델에게 넘긴다 — 뜻을 모르는 답을 코드가 대신 처리하면 사용자의 말이 사라진다.
     */
    val OPTIONS = setOf(
        ScenarioQuestionBuilder.GAP_LEAVE, // 미상 구간을 그대로 둔다
        "keep", // 동거 불가·담은 범위를 그대로 둔다
        "skip", // 빠진 갈래를 이번엔 담지 않는다
    )

    /**
     * 이 답이 거절인가.
     *
     * @param said 사용자가 한 마디라도 덧붙였나. 덧붙였으면 거절이 아니다 — "그대로 둘게요, 대신
     *   전투는 빼 줘"는 요청이고, 그것까지 코드가 삼키면 사용자가 한 말이 사라진다.
     */
    fun isDecline(question: ScenarioQuestion?, optionIds: List<String>, said: Boolean): Boolean {
        if (question == null || said || optionIds.isEmpty()) return false
        return optionIds.all { it in OPTIONS }
    }

    /** 무엇을 그대로 뒀는지, 마음이 바뀌면 무엇을 하면 되는지. */
    fun advice(question: ScenarioQuestion): String {
        val id = question.id
        return when {
            id.startsWith(ScenarioQuestionBuilder.GAP_PREFIX) ->
                "${id.removePrefix(ScenarioQuestionBuilder.GAP_PREFIX)} 구간은 미상으로 두었습니다. " +
                    "나중에 알려 주시면 그 자리에 넣습니다 — 스텝의 [+ 스텝 추가]로 직접 채우셔도 됩니다."

            id.startsWith(CONFLICT_PREFIX) -> {
                val pairs = id.removePrefix(CONFLICT_PREFIX).split(",").count { it.isNotBlank() }
                "함께 담을 수 없는 ${pairs}쌍을 그대로 두었습니다. 실행하면 뒤쪽에서 멎을 수 있습니다 — " +
                    "나누고 싶어지면 “나눠 줘”라고 말씀해 주세요."
            }

            id.startsWith(ARM_PREFIX) ->
                "같은 자리의 다른 갈래는 담지 않았습니다. 필요해지면 그 갈래를 말씀해 주세요 — " +
                    "따로 시나리오로 만듭니다."

            id.startsWith(SCOPE_PREFIX) ->
                "이번에 담은 범위 그대로 두었습니다. 더 담고 싶어지면 씬 이름과 함께 말씀해 주세요."

            // 모델이 물은 것. 무엇을 물었는지는 코드가 모르므로 다음 수도 일반적인 말로만 한다.
            else -> "그대로 두었습니다. 필요해지면 다시 말씀해 주세요."
        }
    }

    private const val CONFLICT_PREFIX = "conflict:"
    private const val ARM_PREFIX = "arm:"
    private const val SCOPE_PREFIX = "scope:"
}
