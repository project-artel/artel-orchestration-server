package kr.artel.orchestration.qa.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

@Table("qa_try")
data class QaTryEntity(
    @Id val id: Long? = null,
    @Column("test_scenario_id") val testScenarioId: Long,
    @Column("game_instance_id") val gameInstanceId: Long,
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
    // 이 런이 읽고 쓰는 지식 스코프(ARTEL-256). null이면 운영 런이라 운영 지식창고를 그대로
    // 읽고 쓴다 — 이 컬럼이 생기기 전과 동작이 같다. 세션 개설 시점에 정해지고 런 도중 안 바뀐다:
    // 중간에 바뀌면 그 런이 무엇을 봤는지 사후에 재구성할 수 없다.
    //
    // 읽기/쓰기를 아예 끄는 knowledge_mode는 여기가 아니라 runConfig 안에 있다. 그쪽은 비교 축이라
    // 집계가 run_config를 함께 읽고(ARTEL-243), 이쪽은 격리 경계라 질의 술어로 직접 들어간다.
    @Column("knowledge_scope_id") val knowledgeScopeId: Long? = null,
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
