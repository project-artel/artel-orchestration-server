package kr.artel.orchestration.testcase.entity

import org.springframework.data.annotation.CreatedDate
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * folder 에 담긴 케이스 하나(ARTEL-1023). 같은 케이스가 여러 folder 에 들 수 있다.
 *
 * `hidden_game_build_ids` 칸은 엔티티에 싣지 않는다. 배열은 [kr.artel.orchestration.testcase.repository.TestCaseFolderItemRepository]
 * 의 쿼리가 DB 안에서만 고친다 — 저장할 때는 기본값 `'{}'` 로 들어간다.
 */
@Table("test_case_folder_item")
data class TestCaseFolderItemEntity(
    @Id
    val id: Long? = null,

    @Column("folder_id")
    val folderId: Long,

    @Column("test_case_id")
    val testCaseId: Long,

    @Column("position")
    val position: Int,

    @CreatedDate
    @Column("created_at")
    val createdAt: Instant? = null,
)
