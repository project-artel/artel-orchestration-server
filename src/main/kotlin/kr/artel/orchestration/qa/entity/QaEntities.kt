package kr.artel.orchestration.qa.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * QA 실행 런(TR) 단위 (ARTEL-259). 한 qa_run = 한 세션이 런의 시나리오들을 순차 실행하는 단위이며,
 * 그 아래 [QaTryEntity]가 시나리오당 하나씩 달린다. [runConfig]는 세션 공통 설정 스냅샷.
 */
@Table("qa_run")
data class QaRunEntity(
    @Id val id: Long? = null,
    @Column("test_run_id") val testRunId: Long,
    @Column("game_instance_id") val gameInstanceId: Long,
    @Column("started_by") val startedBy: Long,
    @Column("agent_session_id") val agentSessionId: String? = null,
    val status: String,
    @Column("run_config") val runConfig: Json = Json.of("{}"),
    @Column("started_at") val startedAt: Instant,
    @Column("completed_at") val completedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
)

@Table("qa_try")
data class QaTryEntity(
    @Id val id: Long? = null,
    @Column("test_scenario_id") val testScenarioId: Long,
    @Column("game_instance_id") val gameInstanceId: Long,
    // 부모 런(qa_run). 단독 실행(하위호환)은 null.
    @Column("qa_run_id") val qaRunId: Long? = null,
    @Column("started_by") val startedBy: Long,
    @Column("agent_session_id") val agentSessionId: String? = null,
    val status: String,
    // 런을 무엇으로 돌렸는지. Agent가 세션 개설 응답으로 확정해 준 값만 들어오며,
    // 요청값은 들어오지 않는다 — prompt_version=null은 "최신"이라는 별칭이고
    // arch.vision="auto"는 모델에 대한 질문이라, 요청 그대로 남기면 나중에
    // 어느 프롬프트·어느 구조였는지 되짚을 수 없다.
    //
    // 아래 다섯은 비교 축이라 컬럼으로 승격해 GROUP BY와 인덱스를 태우고,
    // 해석값 전체는 runConfig 스냅샷에 있다. 진실은 runConfig 쪽이다.
    val model: String? = null,
    @Column("reasoning_effort") val reasoningEffort: String? = null,
    @Column("prompt_version") val promptVersion: String? = null,
    @Column("agent_arch") val agentArch: String? = null,
    @Column("agent_fingerprint") val agentFingerprint: String? = null,
    @Column("run_config") val runConfig: Json = Json.of("{}"),
    @Column("started_at") val startedAt: Instant,
    @Column("completed_at") val completedAt: Instant? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null
)

@Table("qa_log")
data class QaLogEntity(
    @Id val id: Long? = null,
    @Column("qa_try_id") val qaTryId: Long,
    @Column("message_id") val messageId: String? = null,
    @Column("correlation_id") val correlationId: String? = null,
    val direction: String,
    val type: String,
    val message: String? = null,
    val payload: Json = Json.of("{}"),
    @Column("created_at") val createdAt: Instant? = null
)
