package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.settings.service.FeatureModelService
import kr.artel.orchestration.settings.service.FeatureModelStatus
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/** `PUT /api/admin/settings/models` 요청 본문. 세 필드를 늘 보낸다. null 이면 그 설정을 지운다. */
data class UpdateFeatureModelsRequest(
    val scenarioAuthoringModel: String? = null,
    val decisionModel: String? = null,
    val projectAgentModel: String? = null
)

/** 시나리오 저작, Jev decision 질문, project agent 에 쓸 LLM model 설정. ADMIN 만 부른다. */
@RestController
@RequestMapping("/api/admin/settings/models")
class AdminFeatureModelSettingsController(
    private val featureModelService: FeatureModelService,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun featureModels(@CurrentUserId userId: Long): FeatureModelStatus {
        platformAccessService.requireAdmin(userId)
        return featureModelService.status()
    }

    @PutMapping
    suspend fun updateFeatureModels(
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateFeatureModelsRequest
    ): FeatureModelStatus {
        platformAccessService.requireAdmin(userId)
        return featureModelService.update(
            request.scenarioAuthoringModel, request.decisionModel, request.projectAgentModel, userId
        )
    }
}
