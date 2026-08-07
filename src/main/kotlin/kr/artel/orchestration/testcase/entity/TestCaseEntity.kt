package kr.artel.orchestration.testcase.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestCase — 기능 1개 검증의 재사용 라이브러리 항목. Agent가 빌드타임 메타데이터+문서로 작성한다.
 *
 * 값(category/title/precondition/expected)은 전부 자연어(TEXT/VARCHAR)로 저장한다. 최적화용
 * 구조화 술어·entryState 등은 이 단계에서 넣지 않는다(후속). 시나리오와의 연결은 시나리오
 * payload.steps의 각 스텝이 caseId로 옵션 참조한다(재설계) — 이 테이블에는 링크를 두지 않는다
 * (재사용이라 다대다). FK 없음(프로젝트는 논리 참조). id가 null이면 INSERT.
 */
@Table("test_case")
data class TestCaseEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("category")
    val category: String,

    @Column("title")
    val title: String,

    @Column("precondition")
    val precondition: String? = null,

    @Column("expected")
    val expected: String,

    @Column("verification_status")
    val verificationStatus: String = "DRAFT",

    @Column("last_verified_build_id")
    val lastVerifiedBuildId: Long? = null,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
