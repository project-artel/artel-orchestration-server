package kr.artel.orchestration.contentmap.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table

/**
 * capability_inference — inferred 출신 기능만 갖는 것.
 *
 * 관측된 것과 추론된 것을 섞지 않는다. 추론은 **딛고 선 관측을 반드시 밝힌다** — [basedOn] 이
 * 비어 있는 추론은 근거 없이 지어낸 것과 구분되지 않는다.
 */
@Table("capability_inference")
data class CapabilityInferenceEntity(
    @Id
    @Column("capability_id")
    val capabilityId: Long,

    @Column("model")
    val model: String,

    @Column("prompt_version")
    val promptVersion: String? = null,

    /** 왜 이렇게 추론했는가. 사람이 뒤집어 볼 수 있어야 한다. */
    @Column("rationale")
    val rationale: String,

    /** `[capability_observation.id, ...]`. 이 추론이 딛고 선 관측. */
    @Column("based_on")
    val basedOn: Json = Json.of("[]"),
)
