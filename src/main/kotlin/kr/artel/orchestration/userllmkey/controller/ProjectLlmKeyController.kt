package kr.artel.orchestration.userllmkey.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.userllmkey.dto.UpdateUserLlmKeyRequest
import kr.artel.orchestration.userllmkey.service.ProjectLlmKeyService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

@RestController
@RequestMapping("/api/projects/{projectId}/llm-keys")
class ProjectLlmKeyController(private val keys: ProjectLlmKeyService) {
    @GetMapping
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId userId: Long) =
        keys.list(projectId, userId)

    @PutMapping("/{service}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun register(
        @PathVariable projectId: Long,
        @PathVariable service: String,
        @CurrentUserId userId: Long,
        @RequestBody request: UpdateUserLlmKeyRequest
    ) = keys.register(projectId, userId, service, request.apiKey)

    @PutMapping("/{service}/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun link(@PathVariable projectId: Long, @PathVariable service: String, @CurrentUserId userId: Long) =
        keys.link(projectId, userId, service)

    @DeleteMapping("/{service}/link")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun unlink(@PathVariable projectId: Long, @PathVariable service: String, @CurrentUserId userId: Long) =
        keys.remove(projectId, userId, service, ownLinkOnly = true)

    @DeleteMapping("/{service}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun remove(@PathVariable projectId: Long, @PathVariable service: String, @CurrentUserId userId: Long) =
        keys.remove(projectId, userId, service, ownLinkOnly = false)
}
