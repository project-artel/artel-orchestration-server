package kr.artel.orchestration.llmmodel.service

import com.fasterxml.jackson.core.type.TypeReference
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.llmmodel.dto.AdminLlmModelResponse
import kr.artel.orchestration.llmmodel.dto.InternalLlmModelResponse
import kr.artel.orchestration.llmmodel.dto.LlmModelReasoning
import kr.artel.orchestration.llmmodel.entity.LlmModelEntity
import kr.artel.orchestration.llmmodel.repository.LlmModelRepository
import kr.artel.orchestration.qa.dto.QaModelResponse
import kr.artel.orchestration.qa.dto.QaReasoningCapability
import kr.artel.orchestration.settings.service.AgentCacheInvalidator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** 이 접두어로 시작하는 id 는 OpenRouter 가 아니라 AWS Bedrock 으로 부른다. */
const val BEDROCK_MODEL_PREFIX = "bedrock/"

/** 이 접두어로 시작하는 id 는 Claude API(Anthropic API)로 부른다. 뒤는 Anthropic model id 다. */
const val CLAUDE_MODEL_PREFIX = "claude/"
private const val ROUTE_BEDROCK = "bedrock"
private const val ROUTE_CLAUDE = "claude"
private const val ROUTE_OPENROUTER = "openrouter"
private val STRING_LIST = object : TypeReference<List<String>>() {}

/**
 * model 카탈로그(`llm_model`). 원본은 이 테이블이고 `artel-agent-server` 는 내부 포트로 읽는다.
 *
 * 선택 목록(`/api/qa-models`)은 켜진 행만 보고, agent-server 와 OpenRouter key 확인은 용도에 따라
 * 전체 행이나 켜진 OpenRouter 행을 본다.
 */
@Service
class LlmModelService(
    private val repository: LlmModelRepository,
    private val objectMapper: ObjectMapper,
    private val backfillProperties: KnowledgeBackfillProperties,
    private val agentCacheInvalidator: AgentCacheInvalidator,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(LlmModelService::class.java)

    /** agent-server 용. 꺼진 행도 싣는다. */
    suspend fun listForAgent(): List<InternalLlmModelResponse> =
        repository.findAllByOrderBySortOrderAsc().map(::toInternal)

    /** 선택 목록용. 켜진 행만 싣고 응답 모양은 예전 `GET /models` 와 같다. */
    suspend fun listEnabledForPicker(): List<QaModelResponse> =
        repository.findAllByEnabledTrueOrderBySortOrderAsc().map(::toQaModel)

    suspend fun listForAdmin(): List<AdminLlmModelResponse> =
        repository.findAllByOrderBySortOrderAsc().map(::toAdmin)

    /**
     * model 을 켜고 끈다. 바꾼 뒤에는 agent-server 의 카탈로그 캐시를 비우라고 요청한다. 그 요청이 실패해도
     * 바꾼 것은 그대로이고, agent-server 는 30초 안에 새 카탈로그를 읽는다.
     */
    suspend fun setEnabled(id: String, enabled: Boolean, adminUserId: Long): AdminLlmModelResponse {
        val changed = repository.updateEnabled(id, enabled, adminUserId, Instant.now(clock))
        if (changed == 0) throw NotFoundException("model 을 찾을 수 없습니다: $id", code = "llm_model_not_found")
        logger.info("llm_model {} set enabled={} by app_user {}", id, enabled, adminUserId)
        val updated = repository.findById(id) ?: throw NotFoundException("model 을 찾을 수 없습니다: $id", code = "llm_model_not_found")
        agentCacheInvalidator.invalidateAfterChange("setting llm_model $id enabled=$enabled")
        return toAdmin(updated)
    }

    /**
     * QA run 과 try 를 시작하기 전에 요청의 model 을 확인한다. 값이 없으면 확인하지 않는다 — agent-server 가
     * 자기 기본 model 을 고른다.
     *
     * 카탈로그에 없으면 400 `llm_model_unknown`, 꺼져 있으면 400 `llm_model_disabled` 다.
     */
    suspend fun requireSelectable(id: String?) {
        if (id.isNullOrBlank()) return
        val model = repository.findById(id) ?: throw LlmModelUnknownException(id)
        if (!model.enabled) throw LlmModelDisabledException(id)
    }

    /** Bedrock key 확인이 부를 model. 켜진 Bedrock 행의 id 를 `sort_order` 순서로 돌려준다. */
    suspend fun enabledBedrockModelIds(): List<String> =
        repository.findAllByEnabledTrueOrderBySortOrderAsc()
            .map { it.id }
            .filter { it.startsWith(BEDROCK_MODEL_PREFIX) }

    /** Claude API key 확인이 볼 model. 켜진 `claude/` 행의 id 를 `sort_order` 순서로 돌려준다. */
    suspend fun enabledClaudeModelIds(): List<String> =
        repository.findAllByEnabledTrueOrderBySortOrderAsc()
            .map { it.id }
            .filter { it.startsWith(CLAUDE_MODEL_PREFIX) }

    /**
     * OpenRouter key 하나로 닿아야 하는 model: 켜진 행 중 Bedrock 과 Claude API 가 아닌 것, 그리고 embedding
     * model(`artel.knowledge.backfill.model`). 꺼진 model 은 key 로 닿을 필요가 없다.
     */
    suspend fun requiredOpenRouterModels(): List<String> =
        (repository.findAllByEnabledTrueOrderBySortOrderAsc()
            .map { it.id }
            .filter { routeOf(it) == ROUTE_OPENROUTER } + backfillProperties.model)
            .distinct()

    /** id 의 접두어로 정해지는 호출 경로. `GET /api/admin/llm-models` 의 `route` 값이다. */
    private fun routeOf(id: String): String = when {
        id.startsWith(BEDROCK_MODEL_PREFIX) -> ROUTE_BEDROCK
        id.startsWith(CLAUDE_MODEL_PREFIX) -> ROUTE_CLAUDE
        else -> ROUTE_OPENROUTER
    }

    private fun toInternal(entity: LlmModelEntity) = InternalLlmModelResponse(
        id = entity.id,
        label = entity.label,
        provider = entity.provider,
        supportsStrictJson = entity.supportsStrictJson,
        maxInputTokens = entity.maxInputTokens,
        inputModalities = strings(entity.inputModalities),
        reasoning = reasoning(entity),
        pricing = entity.pricing?.let { objectMapper.readTree(it.asString()) },
        enabled = entity.enabled,
        sortOrder = entity.sortOrder
    )

    private fun toQaModel(entity: LlmModelEntity): QaModelResponse {
        val modalities = strings(entity.inputModalities)
        return QaModelResponse(
            id = entity.id,
            label = entity.label,
            provider = entity.provider,
            supportsStrictJson = entity.supportsStrictJson,
            supportsVision = "image" in modalities,
            inputModalities = modalities,
            multimodal = modalities.size > 1,
            reasoning = reasoning(entity)?.let {
                QaReasoningCapability(it.kind, it.efforts, it.minTokens, it.maxTokens, it.step)
            }
        )
    }

    private fun toAdmin(entity: LlmModelEntity) = AdminLlmModelResponse(
        id = entity.id,
        label = entity.label,
        provider = entity.provider,
        route = routeOf(entity.id),
        enabled = entity.enabled,
        supportsVision = "image" in strings(entity.inputModalities),
        maxInputTokens = entity.maxInputTokens,
        updatedAt = entity.updatedAt
    )

    private fun reasoning(entity: LlmModelEntity): LlmModelReasoning? {
        val kind = entity.reasoningKind ?: return null
        return LlmModelReasoning(
            kind = kind,
            efforts = entity.reasoningEfforts?.let(::strings),
            minTokens = entity.reasoningMinTokens,
            maxTokens = entity.reasoningMaxTokens,
            step = entity.reasoningStep,
            defaultTokens = entity.reasoningDefaultTokens
        )
    }

    private fun strings(json: Json): List<String> = objectMapper.readValue(json.asString(), STRING_LIST)
}

/** 요청이 고른 model 이 `llm_model` 에 없다. */
class LlmModelUnknownException(id: String) :
    BadRequestException("model 을 찾을 수 없습니다: $id", code = "llm_model_unknown")

/** 요청이 고른 model 이 관리 화면에서 꺼져 있다. */
class LlmModelDisabledException(id: String) :
    BadRequestException("관리자가 끈 model 입니다: $id", code = "llm_model_disabled")
