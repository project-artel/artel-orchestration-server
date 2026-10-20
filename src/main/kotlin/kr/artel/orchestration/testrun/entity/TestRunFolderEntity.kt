package kr.artel.orchestration.testrun.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.annotation.LastModifiedDate
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 테스트 런을 묶는 폴더(ARTEL-1049). 한 빌드 버전([gameBuildId]) 안에만 있고, null 이면 "버전 없음" 안이다.
 * [parentId] 가 null 이면 그 버전 바로 아래다.
 */
@Table("test_run_folder")
data class TestRunFolderEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("game_build_id")
    val gameBuildId: Long? = null,

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
