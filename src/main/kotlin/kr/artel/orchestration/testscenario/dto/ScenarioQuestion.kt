package kr.artel.orchestration.testscenario.dto

import com.fasterxml.jackson.annotation.JsonProperty

/**
 * 저작이 사용자에게 **되묻는** 한 건(ARTEL-487).
 *
 * **없던 것은 묻는 능력이 아니라 질문을 담을 자리였다.** 지금도 에이전트는 묻는다 — 어시스턴트
 * 메시지 189건 중 28건에 물음표가 있고 "진입 방법을 알려 주세요" 같은 문장이 실제로 나온다.
 * 그런데 산문 속에 묻혀 있어서 사용자는 설명으로 읽고 지나가고, 답하려 해도 무엇을 답해야
 * 하는지 알 수 없으며, 답해도 그 맥락이 다음 턴까지 살아남지 않았다.
 *
 * **묻는 쪽이 둘이다.** 코드가 이미 아는 것(메우지 못한 구간·빠진 갈래·담은 범위·뒤집힌 순서)은
 * 선택지까지 계산되므로 모델을 거치지 않고 묻는다. 모델이 모르는 것(요청의 뜻이 갈릴 때)은
 * 모델이 선택지를 지어 묻는다. 둘 다 이 한 모양으로 나간다 — 화면이 두 벌을 그릴 이유가 없다.
 *
 * **질문은 저장을 막지 않는다.** 답하지 않아도 그 턴의 결과물은 남는다. 막으면 답을 안 한
 * 사용자에게 아무것도 남지 않고, 그건 애매하게 묻는 사용자를 더 벌주는 셈이 된다. 이 서비스가
 * 상정하는 사용자가 바로 그 사람이다.
 *
 * @property id 답이 어느 질문에 대한 것인지 잇는 값. 턴을 넘어 살아남는다.
 * @property text 사람에게 보이는 물음.
 * @property why 왜 묻는지. 근거가 있는 질문과 그냥 되묻는 것을 사용자가 구분할 수 있어야 한다.
 * @property options 고를 수 있는 답. **비어 있을 수 있다** — 자유 서술만 받는 질문도 있다.
 * @property allowFreeText 보기 밖의 답을 받나. 보기가 답을 다 담지 못하는 경우가 대부분이다.
 */
data class ScenarioQuestion(
    val id: String,
    val text: String,
    val why: String? = null,
    val options: List<ScenarioQuestionOption> = emptyList(),
    @JsonProperty("allow_free_text") val allowFreeText: Boolean = true,
    /** 이 질문을 만든 쪽. `code` 는 계산된 사실에서, `agent` 는 모델이 물은 것. */
    val source: ScenarioQuestionSource = ScenarioQuestionSource.CODE,
) {
    /** 저장 본문. 사람이 읽는 문장은 메시지 `content` 에 그대로 두고 여기에는 누를 것만 담는다. */
    fun payload(): Map<String, Any?> = mapOf(
        "kind" to "question",
        "id" to id,
        "text" to text,
        "why" to why,
        "options" to options.map { mapOf("id" to it.id, "label" to it.label, "detail" to it.detail) },
        "allow_free_text" to allowFreeText,
        "source" to source.name.lowercase(),
    )
}

/**
 * @property id 답으로 되돌아오는 값.
 * @property label 화면에 보이는 짧은 문구.
 * @property detail 고르면 무슨 일이 일어나는지. 짧은 라벨만으로 갈리지 않을 때만 쓴다.
 */
data class ScenarioQuestionOption(
    val id: String,
    val label: String,
    val detail: String? = null,
)

enum class ScenarioQuestionSource { CODE, AGENT }

/**
 * 사용자가 고른 답(ARTEL-487).
 *
 * 고른 것과 적은 것을 **함께** 받는다. 보기를 고르고 한 줄 덧붙이는 것이 실제로 가장 흔한
 * 모양이고, 둘을 나누면 화면이 두 번 보내거나 하나를 버려야 한다.
 *
 * @property questionId 어느 질문에 대한 답인지. 맞지 않으면 평범한 메시지로 다룬다 — 오래된
 *   화면이 지난 질문의 답을 보낼 수 있고, 그때 턴을 잃는 것보다 그냥 말로 취급하는 편이 낫다.
 */
data class ScenarioQuestionAnswer(
    @JsonProperty("question_id") val questionId: String,
    @JsonProperty("option_ids") val optionIds: List<String> = emptyList(),
    val text: String? = null,
)
