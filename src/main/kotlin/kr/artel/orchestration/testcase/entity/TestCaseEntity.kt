package kr.artel.orchestration.testcase.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * TestCase — 기능 1개 검증의 재사용 라이브러리 항목.
 *
 * 컬럼이 **들어오는 명세 구조를 그대로 따른다**(ARTEL-329). 명세는 JSON 배열로 오고 원소 하나가
 * 이 엔티티 하나다:
 *
 * ```
 * { "schema_version": "test-case.v1",
 *   "spec":     { scene, precondition, step, expected_value, status },   → 컬럼
 *   "metadata": { source: {...}, generation: {...} } }                   → JSONB 한 칸
 * ```
 *
 * 이름을 명세와 맞춘 이유는 어긋난 채로 두면 계속 번역해야 하기 때문이다 — CSV 시절의
 * `category`/`title`을 [kr.artel.orchestration.testscenario.service.ScenarioCompositionService]가
 * Agent로 넘길 때마다 scene/testStep으로 바꾸고 있었다.
 *
 * 값은 전부 자연어(TEXT/VARCHAR)로 저장한다. 구조화 술어·entryState 등은 이 단계에서 넣지 않는다
 * (후속) — 이번 변경은 **명세가 주는 것을 버리지 않고 받는 것**까지다.
 *
 * FK 없음(프로젝트는 논리 참조). id가 null이면 INSERT.
 */
@Table("test_case")
data class TestCaseEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    /** 이 케이스가 검증되는 화면. 명세 `spec.scene`. */
    @Column("scene")
    val scene: String,

    /** 무엇을 하는가. 명세 `spec.step`. */
    @Column("step")
    val step: String,

    /** 명세 `spec.precondition`. 없을 수 있다. */
    @Column("precondition")
    val precondition: String? = null,

    /** 기대 결과. 명세 `spec.expected_value`. */
    @Column("expected_value")
    val expectedValue: String,

    /**
     * 명세를 만든 쪽이 매긴 상태("ready" 등). **[verificationStatus]와 다른 축이다** —
     * 이쪽은 입력값이고 저쪽은 우리 QA 런의 결과다. 이 컬럼이 생기기 전 행에는 실제로 없어 nullable.
     */
    @Column("status")
    val status: String? = null,

    /** 명세 계약의 버전(`schema_version`). 계약이 바뀌었을 때 대상 행을 고르는 축. */
    @Column("schema_version")
    val schemaVersion: String? = null,

    /**
     * 명세 `metadata`(source + generation) 통째. 컬럼으로 쪼개지 않는 것은 이 안의 모양이 생성기
     * 쪽 사정으로 바뀌고, 우리가 이 값으로 질의하지 않기 때문이다(출처를 되짚을 때 읽는다).
     */
    @Column("metadata")
    val metadata: Json = Json.of("{}"),

    /** 명세 쪽 안정 식별자(`metadata.source.spec_id`). 적재 멱등 키. */
    @Column("spec_id")
    val specId: String? = null,

    /**
     * 이 행이 온 명세 봉투의 `revision`. 같은 revision의 재전송을 건너뛰고, 적재 후
     * `spec_revision < 이번 revision`인 행으로 "명세에서 빠진 케이스"를 찾는 근거가 된다.
     */
    @Column("spec_revision")
    val specRevision: Int? = null,

    /** Agent가 명세를 보낸 시각(봉투 `created_at`). 우리가 저장한 시각인 [createdAt]과 구분된다. */
    @Column("source_sent_at")
    val sourceSentAt: Instant? = null,

    /**
     * 우리 QA 런이 만든 검증 상태(DRAFT/VERIFIED/BROKEN). **명세 재적재가 덮지 않는다** —
     * 덮으면 검증 이력이 재전송마다 사라진다.
     */
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
