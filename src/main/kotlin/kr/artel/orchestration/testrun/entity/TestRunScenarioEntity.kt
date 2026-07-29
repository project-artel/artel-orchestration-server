package kr.artel.orchestration.testrun.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 런 ↔ 시나리오 조합(junction). "런이 시나리오들을 어떤 순서로 묶는가"를 링크 행으로 표현한다.
 * (test_run_id, position)은 유일. 참조 방식(시나리오 복사 안 함). FK 없음(논리 참조).
 */
@Table("test_run_scenario")
data class TestRunScenarioEntity(
    @Id
    val id: Long? = null,

    @Column("test_run_id")
    val testRunId: Long,

    @Column("test_scenario_id")
    val testScenarioId: Long,

    @Column("position")
    val position: Int,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
