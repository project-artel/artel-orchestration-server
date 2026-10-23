package kr.artel.orchestration.projectagent.service

import kr.artel.orchestration.projectagent.dto.NoticeKind
import kr.artel.orchestration.projectagent.dto.ProjectAgentFailureCause
import kr.artel.orchestration.userllmkey.entity.KeySource

/** 화면에 보이는 NOTICE 의 한 문장. 사용자 locale 이 `ko` 가 아니면 영어로 쓴다. */
object ProjectAgentNotices {
    fun text(kind: String, locale: String?): String {
        val korean = isKorean(locale)
        return when (kind) {
            NoticeKind.ERROR -> errorText(null, null, null, locale)
            NoticeKind.STEP_LIMIT ->
                if (korean) "한 번에 실행할 수 있는 작업 수에 도달해 여기서 멈췄습니다." else "Reached the limit of actions for one request, so I stopped here."
            else ->
                if (korean) "작업을 멈췄습니다." else "Stopped."
        }
    }

    /**
     * NOTICE `error` 의 문장. 원인([ProjectAgentFailureCause])마다 사용자가 다음에 할 일을 적는다. 원인을 모르면
     * 다시 시도하라는 일반 문장이다. provider 의 오류 원문은 싣지 않는다.
     */
    fun errorText(cause: String?, keySource: KeySource?, model: String?, locale: String?): String {
        val korean = isKorean(locale)
        val modelSuffix = model?.let { "($it)" }.orEmpty()
        val userKey = keySource == KeySource.USER
        return when (cause) {
            ProjectAgentFailureCause.LLM_KEY_MISSING -> when {
                userKey && korean -> "선택한 모델$modelSuffix 에 쓸 개인 key 가 없습니다. 계정 설정에서 key 를 넣거나 모델을 바꿔 주세요."
                userKey -> "No personal key is set for the selected model$modelSuffix. Add a key in account settings or choose another model."
                korean -> "관리자가 이 모델$modelSuffix 의 key 를 넣지 않았습니다. 관리자에게 알리거나 모델을 바꿔 주세요."
                else -> "An administrator has not set the key for this model$modelSuffix. Tell an administrator or choose another model."
            }
            ProjectAgentFailureCause.LLM_AUTH_FAILED -> when {
                userKey && korean -> "개인 key 가 거절됐습니다$modelSuffix. 계정 설정에서 key 를 다시 확인해 주세요."
                userKey -> "Your personal key was rejected$modelSuffix. Check the key in account settings."
                korean -> "공용 key 가 거절됐습니다$modelSuffix. 관리자에게 알리거나 모델을 바꿔 주세요."
                else -> "The shared key was rejected$modelSuffix. Tell an administrator or choose another model."
            }
            ProjectAgentFailureCause.LLM_RATE_LIMITED ->
                if (korean) "모델 제공자의 요청 한도에 걸렸습니다$modelSuffix. 잠시 뒤 다시 시도하거나 모델을 바꿔 주세요."
                else "The model provider's rate limit was reached$modelSuffix. Try again shortly or choose another model."
            ProjectAgentFailureCause.LLM_MODEL_UNAVAILABLE ->
                if (korean) "이 모델$modelSuffix 을 지금 쓸 수 없습니다. 모델을 바꿔 주세요."
                else "This model$modelSuffix is not available right now. Choose another model."
            ProjectAgentFailureCause.LLM_PROVIDER_ERROR ->
                if (korean) "모델 제공자에게서 응답을 받지 못했습니다$modelSuffix. 다시 시도하거나 모델을 바꿔 주세요."
                else "No response came from the model provider$modelSuffix. Try again or choose another model."
            else ->
                if (korean) "응답을 받지 못했습니다. 다시 시도해 주세요." else "No response was received. Please try again."
        }
    }

    private fun isKorean(locale: String?): Boolean = locale == null || locale.startsWith("ko")
}
