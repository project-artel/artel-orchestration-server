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
 * 참조하지만 FK 제약은 없다. `id`가 null이면 INSERT, 값이 있으면 UPDATE로 동작하며, created/updated는
 * Auditing이 채운다.
 *
 * 본문은 세 컬럼으로 나뉜다(V32 이전엔 payload JSONB 한 덩어리였다 — ARTEL-291). [steps]만 JSONB로
 * 남는데, 내부 구조가 Agent 계약([kr.artel.orchestration.testscenario.dto.ScenarioStep])의 거울이라
 * 컬럼으로 쪼개면 계약이 바뀔 때마다 마이그레이션이 따라붙기 때문이다.
 * DTO([kr.artel.orchestration.testscenario.dto.ScenarioDraft])와의 변환은 [toDraft]/[withDraft]가 맡는다.
 */
@Table("test_scenario")
data class TestScenarioEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("title")
    val title: String = "",

    @Column("description")
    val description: String = "",

    @Column("steps")
    val steps: Json = Json.of("[]"),

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
