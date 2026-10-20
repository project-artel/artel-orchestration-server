package kr.artel.orchestration.testcase.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * test case 를 묶는 folder(ARTEL-969). [parentId] 가 null 이면 최상위다.
 *
 * 같은 부모 아래 [name] 은 겹치지 않는다(V99 의 partial unique index 두 개).
 */
@Table("test_case_folder")
data class TestCaseFolderEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("parent_id")
    val parentId: Long? = null,

    @Column("name")
    val name: String,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,

    @LastModifiedDate
    @Column("updated_at")
    val updatedAt: Instant? = null,
)
