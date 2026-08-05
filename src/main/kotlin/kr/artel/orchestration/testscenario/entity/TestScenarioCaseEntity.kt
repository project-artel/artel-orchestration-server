package kr.artel.orchestration.testscenario.entity

import io.r2dbc.postgresql.codec.Json
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
 *
 * [steps]는 이 자리(position)의 저작 Step 배열(JSONB, ARTEL-254). 이 TC에 도착하는 경로(setup)와
 * 실행 단계(guide)를 담는 advisory 가이드다. `[{id, kind:setup|guide|verify, assert, intent, hint?, ...}]`.
 * 재사용되지 않는 시퀀스 전용이라 TC가 아니라 이 조합 행에 산다. 기본값은 빈 배열.
 * ⚠️ reconcile/composition이 이 행을 delete+recreate 하므로, 그 경로는 (testCaseId, position) 매칭으로
 * steps를 캐리포워드해야 유실되지 않는다.
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

    @Column("steps")
    val steps: Json = Json.of("[]"),

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
