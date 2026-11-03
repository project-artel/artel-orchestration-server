package kr.artel.orchestration.testrun.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 테스트 런 맵(ARTEL-1244). 런마다 한 행. 쓰기는 [kr.artel.orchestration.testrun.repository.TestRunMapRepository] 의
 * 버전 조건 쿼리로만 한다. 엔티티 저장(save)은 쓰지 않는다.
 */
@Table("test_run_map")
data class TestRunMapEntity(
    @Id
    @Column("test_run_id")
    val testRunId: Long,

    /** TS id(문자열) → 프레임 자리 · 접힘. */
    @Column("layout")
    val layout: Json,

    /** 메모 · 글 · 도형 · 그림 목록. */
    @Column("annotations")
    val annotations: Json,

    @Column("version")
    val version: Int,

    @Column("updated_by")
    val updatedBy: Long?,

    @Column("updated_at")
    val updatedAt: Instant,
)
