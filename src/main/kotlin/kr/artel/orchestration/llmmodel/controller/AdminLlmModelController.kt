package kr.artel.orchestration.llmmodel.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.llmmodel.dto.AdminLlmModelResponse
import kr.artel.orchestration.llmmodel.dto.UpdateLlmModelRequest
import kr.artel.orchestration.llmmodel.service.LlmModelService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** 관리 화면의 model 카탈로그. ADMIN 만 부르고, 바꿀 수 있는 것은 켜고 끄는 것뿐이다. */
@RestController
@RequestMapping("/api/admin/llm-models")
class AdminLlmModelController(
    private val service: LlmModelService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun list(@CurrentUserId userId: Long): List<AdminLlmModelResponse> {
        platformAccessService.requireAdmin(userId)
        return service.listForAdmin()
    }

    /** id 가 없으면 404 `llm_model_not_found` 다. */
    @PatchMapping
    suspend fun update(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateLlmModelRequest
    ): AdminLlmModelResponse {
        platformAccessService.requireAdmin(userId)
        return service.setEnabled(request.id, request.enabled, userId)
    }
}
