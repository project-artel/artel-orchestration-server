package kr.artel.orchestration.settings.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmmodel.repository.LlmModelRepository
import kr.artel.orchestration.settings.config.AgentServerProperties
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock
import java.time.Instant

/** 시나리오 저작 agent 가 쓰는 model 의 `llm_model.id`. 행이 없으면 환경 변수, 그것도 없으면 agent-server 기본값이다. */
const val SCENARIO_AUTHORING_MODEL_SETTING = "llm.model.scenario_authoring"

/** Jev decision 질문에 답하는 model. `llm_model.id` 이거나 [JEV_MODEL_ID] 다. 행이 없으면 Jev 다. */
const val DECISION_MODEL_SETTING = "llm.model.decision"

/**
 * project agent 가 쓰는 model 의 `llm_model.id`. 사용자 선택(`app_user.project_agent_model`)과 프로젝트 설정
 * (`project.project_agent_model`)이 없을 때 쓰인다. 행이 없으면 시나리오 저작 model 을 따른다.
 */
const val PROJECT_AGENT_MODEL_SETTING = "llm.model.project_agent"

/** OpenRouter alpha decisions endpoint 의 Jev model. `llm_model` 에 행이 없지만 decision model 로는 고를 수 있다. */
const val JEV_MODEL_ID = "typesafe/jev-1.13"

/** `GET/PUT /api/admin/settings/models` 응답. */
data class FeatureModelStatus(
    /** 저장된 시나리오 저작 model. 없으면 null 이다. */
    val scenarioAuthoringModel: String?,
    /** [scenarioAuthoringModel] 이 null 일 때 쓰이는 값. `artel.agent.model` 이 있으면 그 값, 없으면 null(agent-server 기본값). */
    val scenarioAuthoringFallback: String?,
    /** 저장된 decision model. null 이면 Jev([JEV_MODEL_ID]) 다. */
    val decisionModel: String?,
    /** 저장된 project agent model. 없으면 null 이다. */
    val projectAgentModel: String?,
    /**
     * [projectAgentModel] 이 null 이거나 쓸 수 없을 때 쓰이는 값. 지금 풀리는 시나리오 저작 model 이고, 그것도
     * 없으면 null(agent-server 기본값)이다.
     */
    val projectAgentFallback: String?,
    /**
     * 저장된 세 model 중 공용 key 가 없는 id. 저장은 막지 않는다 — 화면이 경고하고, 실행은 agent-server 가
     * `llm_key_missing` 으로 거절한다.
     */
    val keyMissing: List<String>,
    /** 세 설정 중 가장 늦게 바뀐 시각. 모두 없으면 null 이다. */
    val updatedAt: Instant?
)

/** `GET /internal/settings/models` 응답. */
data class InternalFeatureModels(val scenarioAuthoringModel: String?, val decisionModel: String?)

/**
 * 기능별 LLM model 설정. `platform_setting` 의 평문 세 행([SCENARIO_AUTHORING_MODEL_SETTING],
 * [DECISION_MODEL_SETTING], [PROJECT_AGENT_MODEL_SETTING])을 읽고 쓴다.
 */
@Service
class FeatureModelService(
    private val platformSettingRepository: PlatformSettingRepository,
    private val llmModelRepository: LlmModelRepository,
    private val agentCacheInvalidator: AgentCacheInvalidator,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
    private val agentServerProperties: AgentServerProperties,
    private val platformKeyPresence: PlatformKeyPresence
) {
    private val logger = LoggerFactory.getLogger(FeatureModelService::class.java)

    suspend fun status(): FeatureModelStatus {
        val authoring = platformSettingRepository.findById(SCENARIO_AUTHORING_MODEL_SETTING)
        val decision = platformSettingRepository.findById(DECISION_MODEL_SETTING)
        val projectAgent = platformSettingRepository.findById(PROJECT_AGENT_MODEL_SETTING)
        val storedModels = listOfNotNull(authoring?.settingValue, decision?.settingValue, projectAgent?.settingValue)
        return FeatureModelStatus(
            scenarioAuthoringModel = authoring?.settingValue,
            scenarioAuthoringFallback = envModel(),
            decisionModel = decision?.settingValue,
            projectAgentModel = projectAgent?.settingValue,
            projectAgentFallback = resolveAuthoringModel(),
            keyMissing = platformKeyPresence.missingAmong(storedModels),
            updatedAt = listOfNotNull(authoring?.updatedAt, decision?.updatedAt, projectAgent?.updatedAt).maxOrNull()
        )
    }

    /** agent-server 용. */
    suspend fun internalModels(): InternalFeatureModels = InternalFeatureModels(
        scenarioAuthoringModel = storedAuthoringModel(),
        decisionModel = platformSettingRepository.findById(DECISION_MODEL_SETTING)?.settingValue
    )

    /**
     * 시나리오 저작 agent 에 보낼 model. 저장된 값 → `artel.agent.model` → null(agent-server 기본값) 순이다.
     * 세션을 열 때마다 읽는다.
     */
    suspend fun resolveAuthoringModel(): String? =
        storedAuthoringModel()?.takeIf { usable(it, SCENARIO_AUTHORING_MODEL_SETTING) } ?: envModel()

    /**
     * 관리자가 고른 project agent model. 저장된 값이 꺼졌거나 카탈로그에 없으면 null 이다 — 그때 부르는 쪽은
     * [resolveAuthoringModel] 로 넘어간다. 공용 key 는 보지 않는다. key 가 없으면 agent-server 가
     * `llm_key_missing` 으로 거절하고, 그 원인이 대화에 남는다.
     */
    suspend fun resolveProjectAgentAdminModel(): String? =
        platformSettingRepository.findById(PROJECT_AGENT_MODEL_SETTING)?.settingValue
            ?.takeIf { usable(it, PROJECT_AGENT_MODEL_SETTING) }

    /**
     * 저장한 뒤 카탈로그에서 꺼지거나 빠진 model 은 건너뛴다. agent-server 는 꺼진 model 을 거절하므로,
     * 그대로 보내면 대화가 매번 실패한다.
     */
    private suspend fun usable(id: String, settingKey: String): Boolean {
        val enabled = llmModelRepository.findById(id)?.enabled == true
        if (!enabled) logger.warn("저장된 model 이 꺼져 있거나 카탈로그에 없어 건너뛴다 [setting=$settingKey, model=$id]")
        return enabled
    }

    /**
     * 세 설정을 함께 바꾼다. null 이나 빈 문자열이면 행을 지운다. 검증에 실패하면 아무것도 바꾸지 않는다.
     * 저장한 뒤에는 agent-server 의 캐시를 비우라고 요청한다. 그 요청이 실패해도 저장은 성공이다.
     */
    suspend fun update(
        scenarioAuthoringModel: String?,
        decisionModel: String?,
        projectAgentModel: String?,
        adminUserId: Long
    ): FeatureModelStatus {
        val authoring = scenarioAuthoringModel?.trim()?.takeIf { it.isNotEmpty() }
        val decision = decisionModel?.trim()?.takeIf { it.isNotEmpty() }
        val projectAgent = projectAgentModel?.trim()?.takeIf { it.isNotEmpty() }
        authoring?.let { requireEnabledModel(it) }
        decision?.takeIf { it != JEV_MODEL_ID }?.let { requireEnabledModel(it) }
        projectAgent?.let { requireEnabledModel(it) }
        transactionalOperator.executeAndAwait {
            store(SCENARIO_AUTHORING_MODEL_SETTING, authoring, adminUserId)
            store(DECISION_MODEL_SETTING, decision, adminUserId)
            store(PROJECT_AGENT_MODEL_SETTING, projectAgent, adminUserId)
        }
        logger.info(
            "Feature models set by app_user {}: scenarioAuthoring={}, decision={}, projectAgent={}",
            adminUserId, authoring, decision, projectAgent
        )
        agentCacheInvalidator.invalidateAfterChange("changing the feature models")
        return status()
    }

    private suspend fun store(key: String, value: String?, adminUserId: Long) {
        if (value == null) {
            platformSettingRepository.deleteById(key)
        } else {
            platformSettingRepository.upsert(key, value, false, adminUserId, Instant.now(clock))
        }
    }

    private suspend fun requireEnabledModel(id: String) {
        val model = llmModelRepository.findById(id)
            ?: throw BadRequestException("카탈로그에 없는 model 입니다: $id", code = "llm_model_not_found")
        if (!model.enabled) {
            throw BadRequestException("꺼져 있는 model 입니다: $id", code = "llm_model_disabled")
        }
    }

    private suspend fun storedAuthoringModel(): String? =
        platformSettingRepository.findById(SCENARIO_AUTHORING_MODEL_SETTING)?.settingValue

    private fun envModel(): String? = agentServerProperties.model.takeIf { it.isNotBlank() }
}
