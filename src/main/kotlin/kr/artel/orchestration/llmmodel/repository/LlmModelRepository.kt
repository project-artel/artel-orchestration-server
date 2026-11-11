package kr.artel.orchestration.llmmodel.repository

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.llmmodel.entity.LlmModelEntity
import org.springframework.data.r2dbc.repository.Modifying
import org.springframework.data.r2dbc.repository.Query
import org.springframework.data.repository.kotlin.CoroutineCrudRepository
import java.time.Instant

interface LlmModelRepository : CoroutineCrudRepository<LlmModelEntity, String> {

    /** 켜진 행만, 선택 목록에 나오는 순서로. */
    suspend fun findAllByEnabledTrueOrderBySortOrderAsc(): List<LlmModelEntity>

    suspend fun findAllByOrderBySortOrderAsc(): List<LlmModelEntity>

    /** 바뀐 행 수를 돌려준다. id 가 없으면 0 이다. */
    @Modifying
    @Query("UPDATE llm_model SET enabled = :enabled, updated_by = :updatedBy, updated_at = :updatedAt WHERE id = :id")
    suspend fun updateEnabled(id: String, enabled: Boolean, updatedBy: Long, updatedAt: Instant): Int

    @Modifying
    @Query("""
        INSERT INTO llm_model (
            id, label, provider, supports_strict_json, max_input_tokens, input_modalities,
            reasoning_kind, reasoning_efforts, enabled, sort_order, updated_by, updated_at
        ) VALUES (
            :id, :label, :provider, :supportsStrictJson, :maxInputTokens, :inputModalities,
            :reasoningKind, :reasoningEfforts, TRUE,
            (SELECT COALESCE(MAX(sort_order), 0) + 1 FROM llm_model), :updatedBy, :updatedAt
        )
        ON CONFLICT (id) DO NOTHING
    """)
    suspend fun insertDiscovered(
        id: String, label: String, provider: String, supportsStrictJson: Boolean,
        maxInputTokens: Int, inputModalities: Json, reasoningKind: String?, reasoningEfforts: Json?,
        updatedBy: Long, updatedAt: Instant
    ): Int
}
