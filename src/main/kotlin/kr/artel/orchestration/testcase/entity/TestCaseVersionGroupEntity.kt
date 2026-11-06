package kr.artel.orchestration.testcase.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/** 라이브러리 버전 목록의 묶음(ARTEL-1023). 버전과 같은 층에 하나뿐이다 — 묶음 안에 묶음은 없다. */
@Table("test_case_version_group")
data class TestCaseVersionGroupEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("name")
    val name: String,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
