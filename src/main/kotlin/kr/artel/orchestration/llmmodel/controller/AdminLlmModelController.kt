package kr.artel.orchestration.llmmodel.controller

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.llmmodel.dto.AdminLlmModelResponse
import kr.artel.orchestration.llmmodel.dto.UpdateLlmModelRequest
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.llmmodel.service.LlmModelDiscovery
import kr.artel.orchestration.llmmodel.service.DiscoveryProvider
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PatchMapping
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/admin/llm-models")
class AdminLlmModelController(
    private val service: LlmModelService,
    private val discovery: LlmModelDiscovery,
    private val platformAccessService: PlatformAccessService
) {
    @GetMapping
    suspend fun list(@CurrentUserId userId: Long): List<AdminLlmModelResponse> {
        platformAccessService.requireAdmin(userId)
        return service.listForAdmin()
    }

    @GetMapping("/discovery")
    suspend fun discover(@CurrentUserId userId: Long, @RequestParam provider: String): List<ModelCandidate> {
        platformAccessService.requireAdmin(userId)
        val source = DiscoveryProvider.parse(provider)
        val existing = service.listForAdmin().mapTo(hashSetOf()) { it.id }
        return discovery.list(source).filterNot { it.id in existing }.map { ModelCandidate(it.id, it.label) }
    }

    @PostMapping("/import")
    suspend fun importModel(@CurrentUserId userId: Long, @RequestBody request: ImportModel): AdminLlmModelResponse {
        platformAccessService.requireAdmin(userId)
        val provider = DiscoveryProvider.parse(request.provider)
        return service.importModel(discovery.resolve(provider, request.id), userId)
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

data class ModelCandidate(val id: String, val label: String)

data class ImportModel(val provider: String, val id: String)
