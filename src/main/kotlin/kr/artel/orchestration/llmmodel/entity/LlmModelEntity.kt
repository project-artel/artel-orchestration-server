package kr.artel.orchestration.llmmodel.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 모델 카탈로그의 한 줄. 등록된 모델은 관리 화면에서 사용 여부를 바꿀 수 있다. */
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

    /** 요청이 추론을 고르지 않았을 때 켤 token 예산. provider 기본값이 없는 model 에만 있다. */
    @Column("reasoning_default_tokens")
    val reasoningDefaultTokens: Int?,

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
