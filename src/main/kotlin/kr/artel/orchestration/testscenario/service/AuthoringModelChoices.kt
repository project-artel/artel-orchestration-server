package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.userllmkey.entity.KeySource
import kr.artel.orchestration.userllmkey.service.ProjectLlmKeyService
import kr.artel.orchestration.userllmkey.service.UserLlmKeyService
import org.springframework.stereotype.Component

/**
 * 시나리오 생성 챗봇에서 사용자가 고른 model 과 그것을 부를 key(ARTEL-1427). [llmKey] 가 null 이면 공용 key 다.
 *
 * [llmKey] 는 세션 개설 · 턴 본문에만 실리고 어디에도 남지 않는다([QaSessionLlmKey.toString] 이 key 를 뺀다).
 */
data class AuthoringModelChoice(
    val model: String,
    val keySource: KeySource,
    val llmKey: QaSessionLlmKey? = null,
)

/**
 * 챗봇 메시지의 `model` · `keySource` 를 **QA 실행과 같은 규칙으로** 푼다(`QaTryController.create`). model 은 꼭 골라야 한다.
 *
 * 고른 model 이 카탈로그에 없거나 꺼졌으면 400, 고른 key 가 없으면 400 `llm_key_missing` 이다. 공용 key 로 넘어가지
 * 않는다 — 사용자 key 로 돌기를 고른 사람의 대화가 말없이 공용 key 로 돌면 안 된다.
 */
@Component
class AuthoringModelChoices(
    private val llmModelService: LlmModelService,
    private val userLlmKeyService: UserLlmKeyService,
    private val projectLlmKeys: ProjectLlmKeyService,
) {
    /**
     * 기본 model 은 없다(ARTEL-1427). [model] 이 비면 400 `model_required` 다 — 화면은 고르기 전에 보내기를 막는다.
     */
    suspend fun resolve(projectId: Long, appUserId: Long, model: String?, keySource: String?): AuthoringModelChoice {
        val chosen = model?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("시나리오를 만들 모델을 먼저 고르세요.", MODEL_REQUIRED)
        llmModelService.requireSelectable(chosen)
        val key =
            if (keySource == KeySource.PROJECT.wire) projectLlmKeys.resolve(projectId, appUserId, chosen)
            else userLlmKeyService.resolveForRun(appUserId, chosen, keySource)
        return AuthoringModelChoice(chosen, key.keySource, key.llmKey)
    }
}

/** 챗봇에서 model 을 고르지 않고 보냈다(ARTEL-1427). */
const val MODEL_REQUIRED = "model_required"
