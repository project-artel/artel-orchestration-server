package kr.artel.orchestration.testscenario.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestScenario 시나리오 저장용 R2DBC 엔티티.
 *
 * 한 프로젝트에 여러 시나리오가 존재할 수 있다(Project 1:N). `projectId`는 Project 도메인을 논리적으로
 * 참조하지만 FK 제약은 없다. `payload`는 Agent가 정의하는 시나리오 JSON을 JSONB로 담는다.
 * `id`가 null이면 INSERT, 값이 있으면 UPDATE로 동작하며, created/updated는 Auditing이 채운다.
 */
@Table("test_scenario")
data class TestScenarioEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("payload")
    val payload: Json,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
