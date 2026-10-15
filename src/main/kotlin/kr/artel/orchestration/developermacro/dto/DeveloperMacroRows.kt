package kr.artel.orchestration.developermacro.dto

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

/**
 * 목록이 읽는 developer macro 한 줄. **`source` 와 `definition_json` 을 싣지 않는다.**
 *
 * `source` 는 한 건이 최대 20,000자라 목록에 실으면 macro 수십 개짜리 프로젝트에서 응답 하나가 수백 KB 가
 * 된다. 원문은 상세의 몫이다.
 *
 * build 의 `version`·`label` 과 마지막으로 등록한 사람의 `nickname` 을 함께 읽는다. MCP 서버가 결과
 * 문장에 "빌드 Y 기준, X 가 등록" 을 적는 데 쓴다.
 */
data class DeveloperMacroSummaryRow(
    @Column("name") val name: String,
    @Column("parameter_names") val parameterNames: Json,
    @Column("docstring") val docstring: String,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("build_version") val buildVersion: String,
    @Column("build_label") val buildLabel: String?,
    @Column("version") val version: Int,
    @Column("updated_at") val updatedAt: Instant,
    @Column("updated_by") val updatedBy: Long?,
    @Column("updated_by_nickname") val updatedByNickname: String?,
)

/** 상세와 내부 목록이 읽는 한 줄. [DeveloperMacroSummaryRow] 에 원문을 더한 것이다. */
data class DeveloperMacroDetailRow(
    @Column("name") val name: String,
    @Column("source") val source: String,
    @Column("parameter_names") val parameterNames: Json,
    @Column("docstring") val docstring: String,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("build_version") val buildVersion: String,
    @Column("build_label") val buildLabel: String?,
    @Column("version") val version: Int,
    @Column("updated_at") val updatedAt: Instant,
    @Column("updated_by") val updatedBy: Long?,
    @Column("updated_by_nickname") val updatedByNickname: String?,
)

/** 검사가 agent-server 에 `imports` 로 넘기는 한 쌍. */
data class DeveloperMacroSourceRow(
    @Column("name") val name: String,
    @Column("source") val source: String,
)

/**
 * upsert 한 번의 결과. `macro` 의 `MacroUpsertRow` 와 같은 모양이고, [inserted] 를 읽는 법도 같다
 * (`(xmax = 0)`, `ScreenRepository.observe` 의 KDoc).
 */
data class DeveloperMacroUpsertRow(
    @Column("id") val id: Long,
    @Column("inserted") val inserted: Boolean,
    /** upsert 뒤의 `version`. 정의가 안 바뀐 등록이면 앞 버전 그대로다. */
    @Column("version") val version: Int,
)
