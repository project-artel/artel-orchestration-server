package kr.artel.orchestration.testscenario.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestScenario 시나리오 저장용 R2DBC 엔티티.
 *
 * `payload`는 Agent가 정의하는 opaque JSON을 문자열 그대로 담는다(Orchestration은 내부를 조회하지 않음).
 * `id`가 null이면 INSERT, 값이 있으면 UPDATE로 동작한다.
 * `createdAt`/`updatedAt`은 R2DBC Auditing이 저장 시점에 자동으로 채운다.
 */
@Table("test_scenario")
data class TestScenarioEntity(
    @Id
    val id: Long? = null,

    @Column("client_id")
    val clientId: String,

    @Column("agent_session_id")
    val agentSessionId: String?,

    @Column("payload")
    val payload: String,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
