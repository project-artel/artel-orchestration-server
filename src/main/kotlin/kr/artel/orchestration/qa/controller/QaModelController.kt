package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.qa.dto.QaModelResponse
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/qa-models")
class QaModelController(
    private val service: LlmModelService
) {
    /** 켜진 model 만 `sort_order` 순서로 돌려준다. */
    @GetMapping
    suspend fun list(): List<QaModelResponse> = service.listEnabledForPicker()
}
