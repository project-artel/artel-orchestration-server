package kr.artel.orchestration.project.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 게임/제품 단위의 상위 묶음. QA 대상 빌드와 세션이 모두 이 아래에 붙는다.
 *
 * 참여자는 project_member로 분리했다([ProjectMemberEntity]). 사용자와 M:N이라 소유자
 * 컬럼을 여기에 둘 수 없고, 접근 권한의 답이 한 곳에서만 나오도록 하기 위해서이기도 하다.
 *
 * [deletedAt]이 채워진 행은 삭제된 것으로 보고 어떤 조회에도 나타나지 않는다.
 */
@Table("project")
data class ProjectEntity(
    @Id
    val id: Long? = null,

    @Column("name")
    val name: String,

    @Column("description")
    val description: String? = null,

    @Column("genre")
    val genre: String,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant,

    @Column("deleted_at")
    val deletedAt: Instant? = null
)
