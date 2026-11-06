package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.settings.service.FeatureModelService
import kr.artel.orchestration.settings.service.InternalFeatureModels
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `artel-agent-server` 가 기능별 model 설정을 묻는 경로.
 *
 * `/internal/` 아래라 내부 포트(8081)에만 있고 인증이 없다. 공개 포트에서 이 경로는 404 다.
 */
@RestController
@RequestMapping("/internal/settings/models")
class InternalFeatureModelSettingsController(private val featureModelService: FeatureModelService) {

    @GetMapping
    suspend fun featureModels(): InternalFeatureModels = featureModelService.internalModels()
}
