package kr.artel.orchestration.llmmodel.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** model 카탈로그의 한 줄. 추가는 migration 이 하고, 런타임에 바뀌는 것은 [enabled] 뿐이다. */
@Table("llm_model")
data class LlmModelEntity(
    @Id
    @Column("id")
    val id: String,

    @Column("label")
    val label: String,

    @Column("provider")
    val provider: String,

    @Column("supports_strict_json")
    val supportsStrictJson: Boolean,

    @Column("max_input_tokens")
    val maxInputTokens: Int,

    /** 문자열 배열. */
    @Column("input_modalities")
    val inputModalities: Json,

    @Column("reasoning_kind")
    val reasoningKind: String?,

    /** 문자열 배열 또는 null. */
    @Column("reasoning_efforts")
    val reasoningEfforts: Json?,

    @Column("reasoning_min_tokens")
    val reasoningMinTokens: Int?,

    @Column("reasoning_max_tokens")
    val reasoningMaxTokens: Int?,

    @Column("reasoning_step")
    val reasoningStep: Int?,

    /** camelCase 키의 객체 또는 null. */
    @Column("pricing")
    val pricing: Json?,

    @Column("enabled")
    val enabled: Boolean,

    @Column("sort_order")
    val sortOrder: Int,

    @Column("updated_by")
    val updatedBy: Long?,

    @Column("updated_at")
    val updatedAt: Instant
)
