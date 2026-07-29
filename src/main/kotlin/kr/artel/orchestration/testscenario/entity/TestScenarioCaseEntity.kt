package kr.artel.orchestration.testscenario.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 시나리오 ↔ 케이스 조합(junction). "시나리오가 케이스들을 어떤 순서로 담는가"를 링크 행으로 표현한다.
 *
 * [position]은 시나리오 내 **의미적 순서**(0-based 등 서비스가 정한 규칙). (testScenarioId, position)은 유일.
 * 참조 방식이라 케이스 원본은 [testCaseId]로 가리킬 뿐 복사하지 않는다. FK 없음(논리 참조).
 * 역방향("케이스 X를 쓰는 시나리오")은 test_case_id 인덱스로 조회한다.
 */
@Table("test_scenario_case")
data class TestScenarioCaseEntity(
    @Id
    val id: Long? = null,

    @Column("test_scenario_id")
    val testScenarioId: Long,

    @Column("test_case_id")
    val testCaseId: Long,

    @Column("position")
    val position: Int,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
