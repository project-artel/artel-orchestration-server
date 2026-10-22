package kr.artel.orchestration.projectagent.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.projectagent.dto.ProjectAgentModelOfProjectResponse
import kr.artel.orchestration.projectagent.dto.UpdateProjectAgentModelOfProjectRequest
import kr.artel.orchestration.projectagent.dto.UpdateUserProjectAgentModelRequest
import kr.artel.orchestration.projectagent.dto.UserProjectAgentModelResponse
import kr.artel.orchestration.projectagent.service.ProjectAgentModelService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/**
 * project agent 가 쓸 model 의 사용자 선택과 프로젝트 설정. 우선순위는 사용자 > 프로젝트 > 관리자 설정이다.
 *
 * 선택 목록은 `GET /api/qa-models` 를 그대로 쓴다. 프로젝트 설정에는 `keySource` 가 `platform` 인 항목만 고를 수 있다.
 */
@RestController
class ProjectAgentModelController(private val service: ProjectAgentModelService) {

    /** [projectId] 를 주면 `effectiveModel` 이 그 프로젝트의 설정까지 따른다. 그 프로젝트의 멤버가 아니면 404 다. */
    @GetMapping("/api/auth/me/project-agent-model")
    suspend fun myProjectAgentModel(
        @CurrentUserId appUserId: Long,
        @RequestParam(required = false) projectId: Long?,
    ): UserProjectAgentModelResponse = service.userView(appUserId, projectId)

    /**
     * 400 `llm_model_unknown` · `llm_model_disabled`(model), 400 `llm_key_missing`(그 key 로 부를 수 없음),
     * 400 `invalid_request`(모르는 `keySource`) 다. QA run 을 시작할 때와 같은 확인이다.
     */
    @PutMapping("/api/auth/me/project-agent-model")
    suspend fun updateMyProjectAgentModel(
        @CurrentUserId appUserId: Long,
        @RequestBody request: UpdateUserProjectAgentModelRequest,
    ): UserProjectAgentModelResponse = service.updateUser(appUserId, request)

    /** 멤버 누구나 읽는다. */
    @GetMapping("/api/projects/{projectId}/project-agent-model")
    suspend fun projectAgentModelOfProject(
        @CurrentUserId appUserId: Long,
        @PathVariable projectId: Long,
    ): ProjectAgentModelOfProjectResponse = service.projectView(appUserId, projectId)

    /** OWNER 만 바꾼다(403). 공용 key 로 부를 수 없는 model 은 400 `llm_key_missing` 이다. */
    @PutMapping("/api/projects/{projectId}/project-agent-model")
    suspend fun updateProjectAgentModelOfProject(
        @CurrentUserId appUserId: Long,
        @PathVariable projectId: Long,
        @RequestBody request: UpdateProjectAgentModelOfProjectRequest,
    ): ProjectAgentModelOfProjectResponse = service.updateProject(appUserId, projectId, request.model)
}
