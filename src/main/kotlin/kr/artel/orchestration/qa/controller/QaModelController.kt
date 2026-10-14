package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.qa.dto.QaModelResponse
import kr.artel.orchestration.settings.service.AnthropicKeyService
import kr.artel.orchestration.userllmkey.service.UserLlmKeyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/qa-models")
class QaModelController(
    private val service: LlmModelService,
    private val userLlmKeyService: UserLlmKeyService,
    private val anthropicKeyService: AnthropicKeyService
) {
    /**
     * 켜진 model 만 `sort_order` 순서로 돌려준다. 공용 key 항목(`keySource: "platform"`)이 먼저이고, 그 뒤에
     * 사용자 key 항목(`keySource: "user"`)이 같은 순서로 온다. 펼치는 규칙은 `LlmModelService.listForPicker` 에 있다.
     */
    @GetMapping
    suspend fun list(@CurrentUserId userId: Long): List<QaModelResponse> =
        service.listForPicker(
            userKeyHints = userLlmKeyService.keyHints(userId),
            platformAnthropicKeyStored = anthropicKeyService.storedKey() != null
        )
}
