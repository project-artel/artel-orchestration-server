package kr.artel.orchestration.projectagent.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.llmmodel.service.LlmModelDisabledException
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.llmmodel.service.LlmModelUnknownException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.projectagent.dto.ProjectAgentFailureCause
import kr.artel.orchestration.projectagent.dto.ProjectAgentModelOfProjectResponse
import kr.artel.orchestration.projectagent.dto.UpdateUserProjectAgentModelRequest
import kr.artel.orchestration.projectagent.dto.UserProjectAgentModelResponse
import kr.artel.orchestration.projectagent.repository.ProjectAgentModelRepository
import kr.artel.orchestration.qa.service.QaRunKey
import kr.artel.orchestration.qa.service.QaSessionLlmKey
import kr.artel.orchestration.settings.service.FeatureModelService
import kr.artel.orchestration.settings.service.PlatformKeyPresence
import kr.artel.orchestration.userllmkey.entity.KeySource
import kr.artel.orchestration.userllmkey.service.LlmKeyMissingException
import kr.artel.orchestration.userllmkey.service.UserLlmKeyService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * step 하나가 쓸 model 과 key. [llmKey] 는 사용자 key 로 돌 때만 있다.
 *
 * `toString` 은 [QaSessionLlmKey] 가 key 원문을 가린다.
 */
data class ProjectAgentModelSelection(
    val model: String?,
    val keySource: KeySource,
    val llmKey: QaSessionLlmKey? = null,
)

/**
 * 사용자가 고른 model 을 지금 쓸 수 없다. step 은 다른 model 로 넘어가지 않고 이 원인으로 실패한다 — 사용자 key
 * 로 돌기를 고른 사람의 대화가 말없이 공용 key 로 돌면 안 된다.
 */
class UnusableProjectAgentModelException(
    val failureCause: String,
    val model: String,
    val keySource: KeySource,
) : RuntimeException("project agent model $model ($failureCause)")

/**
 * project agent 가 쓸 model 을 정한다. 우선순위는 사용자 선택 > 프로젝트 설정 > 관리자 설정 > 시나리오 저작 model >
 * null(agent-server 기본값)이다.
 *
 * | 단계 | 값 | 쓸 수 없으면 |
 * |---|---|---|
 * | 1 | `app_user.project_agent_model` + key source | step 이 [UnusableProjectAgentModelException] 으로 실패한다 |
 * | 2 | `project.project_agent_model`(공용 key) | 3 으로 넘어간다. 꺼졌거나 공용 key 가 없을 때다 |
 * | 3 | `platform_setting` 의 `llm.model.project_agent` | 4 로 넘어간다. 꺼졌을 때다 |
 * | 4 | [FeatureModelService.resolveAuthoringModel] | 그 함수가 정한다 |
 */
@Service
class ProjectAgentModelService(
    private val repository: ProjectAgentModelRepository,
    private val llmModelService: LlmModelService,
    private val userLlmKeyService: UserLlmKeyService,
    private val featureModelService: FeatureModelService,
    private val platformKeyPresence: PlatformKeyPresence,
    private val projectAccessService: ProjectAccessService,
    private val clock: Clock,
) {
    private val logger = LoggerFactory.getLogger(ProjectAgentModelService::class.java)

    /** step 직전에 부른다. 사용자 선택을 쓸 수 없으면 [UnusableProjectAgentModelException] 이다. */
    suspend fun resolveForStep(appUserId: Long, projectId: Long): ProjectAgentModelSelection {
        val choice = repository.findUserChoice(appUserId)
        val model = choice?.model ?: return ProjectAgentModelSelection(defaultModel(projectId), KeySource.PLATFORM)
        val key = resolveUserChoice(appUserId, model, choice.keySource)
        return ProjectAgentModelSelection(model, key.keySource, key.llmKey)
    }

    /** [projectId] 를 주면 그 프로젝트의 설정까지 따라 `effective*` 를 정한다. 그 프로젝트의 멤버가 아니면 404 다. */
    suspend fun userView(appUserId: Long, projectId: Long?): UserProjectAgentModelResponse {
        projectId?.let { projectAccessService.requireMember(it, appUserId) }
        val choice = repository.findUserChoice(appUserId) ?: throw UnauthorizedException()
        val model = choice.model
        if (model == null) {
            return UserProjectAgentModelResponse(
                model = null,
                keySource = null,
                effectiveModel = defaultModel(projectId),
                effectiveKeySource = KeySource.PLATFORM.wire,
                problem = null,
            )
        }
        val problem = try {
            resolveUserChoice(appUserId, model, choice.keySource)
            null
        } catch (error: UnusableProjectAgentModelException) {
            error.failureCause
        }
        val keySource = keySourceOf(choice.keySource).wire
        return UserProjectAgentModelResponse(model, keySource, model, keySource, problem)
    }

    /**
     * 사용자 선택을 바꾼다. QA run 과 같은 확인을 거친다 — 카탈로그에 없으면 400 `llm_model_unknown`, 꺼졌으면 400
     * `llm_model_disabled`, key 가 없으면 400 `llm_key_missing`. [UpdateUserProjectAgentModelRequest.model] 이
     * 비면 두 칸을 모두 지운다.
     */
    suspend fun updateUser(appUserId: Long, request: UpdateUserProjectAgentModelRequest): UserProjectAgentModelResponse {
        val model = request.model?.trim()?.takeIf { it.isNotEmpty() }
        val keySource = if (model == null) {
            null
        } else {
            llmModelService.requireSelectable(model)
            userLlmKeyService.resolveForRun(appUserId, model, request.keySource).keySource.wire
        }
        if (repository.updateUserChoice(appUserId, model, keySource, Instant.now(clock)) == 0L) {
            throw UnauthorizedException()
        }
        logger.info("app_user {} set project agent model={} keySource={}", appUserId, model, keySource)
        return userView(appUserId, null)
    }

    /** 프로젝트 멤버 누구나 읽는다. 멤버가 아니면 404 다. */
    suspend fun projectView(appUserId: Long, projectId: Long): ProjectAgentModelOfProjectResponse {
        projectAccessService.requireMember(projectId, appUserId)
        val model = repository.findProjectModel(projectId)
        return ProjectAgentModelOfProjectResponse(
            model = model,
            effectiveModel = defaultModel(projectId),
            problem = model?.let { projectModelProblem(it) },
        )
    }

    /**
     * 프로젝트 설정을 바꾼다. OWNER 만 부른다(아니면 403). 공용 key 로 부를 수 없는 model 은 400
     * `llm_key_missing` 이다 — 프로젝트 설정은 멤버 모두에게 걸리므로 누군가의 개인 key 로 돌 수 없다.
     */
    suspend fun updateProject(appUserId: Long, projectId: Long, requestedModel: String?): ProjectAgentModelOfProjectResponse {
        projectAccessService.requireOwner(projectId, appUserId, "project agent model 은 프로젝트 소유자만 바꿀 수 있습니다.")
        val model = requestedModel?.trim()?.takeIf { it.isNotEmpty() }
        if (model != null) {
            llmModelService.requireSelectable(model)
            if (!platformKeyPresence.has(model)) {
                throw LlmKeyMissingException("공용 key 로 부를 수 없는 model 입니다: $model")
            }
        }
        repository.updateProjectModel(projectId, model)
        logger.info("app_user {} set project agent model of project {} to {}", appUserId, projectId, model)
        return projectView(appUserId, projectId)
    }

    /** 사용자 선택이 없는 사람이 쓸 model. 표의 2 ~ 4 단계다. */
    private suspend fun defaultModel(projectId: Long?): String? =
        projectId?.let { usableProjectModel(it) }
            ?: featureModelService.resolveProjectAgentAdminModel()
            ?: featureModelService.resolveAuthoringModel()

    private suspend fun usableProjectModel(projectId: Long): String? {
        val model = repository.findProjectModel(projectId) ?: return null
        val problem = projectModelProblem(model) ?: return model
        logger.warn("프로젝트의 project agent model 을 쓸 수 없어 건너뛴다 [project={}, model={}, cause={}]", projectId, model, problem)
        return null
    }

    private suspend fun projectModelProblem(model: String): String? = when {
        !selectable(model) -> ProjectAgentFailureCause.LLM_MODEL_UNAVAILABLE
        !platformKeyPresence.has(model) -> ProjectAgentFailureCause.LLM_KEY_MISSING
        else -> null
    }

    private suspend fun resolveUserChoice(appUserId: Long, model: String, storedKeySource: String?): QaRunKey {
        val keySource = keySourceOf(storedKeySource)
        if (!selectable(model)) {
            throw UnusableProjectAgentModelException(ProjectAgentFailureCause.LLM_MODEL_UNAVAILABLE, model, keySource)
        }
        return try {
            userLlmKeyService.resolveForRun(appUserId, model, keySource.wire)
        } catch (error: BadRequestException) {
            // resolveForRun 의 400 은 모두 그 key 로 그 model 을 부를 수 없다는 뜻이다(llm_key_missing 이나 그 앞의 형식 오류).
            throw UnusableProjectAgentModelException(ProjectAgentFailureCause.LLM_KEY_MISSING, model, keySource)
        }
    }

    private suspend fun selectable(model: String): Boolean = try {
        llmModelService.requireSelectable(model)
        true
    } catch (error: LlmModelUnknownException) {
        false
    } catch (error: LlmModelDisabledException) {
        false
    }

    /** 칸이 비었거나 읽지 못하면 `platform` 이다. 칸에는 CHECK 제약이 있어 그 밖의 값이 들어오지 않는다. */
    private fun keySourceOf(stored: String?): KeySource = stored?.let(KeySource::fromWire) ?: KeySource.PLATFORM
}
