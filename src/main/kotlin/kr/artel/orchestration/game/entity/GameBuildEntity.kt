package kr.artel.orchestration.game.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * SDK가 보고한 게임 버전.
 *
 * [version]은 Unity Player Settings에서 관찰한 값이라 사람이 고칠 수 없다. 고치는 순간
 * SDK가 다음에 보고할 값과 어긋나 같은 빌드가 두 행이 된다. 사람이 붙이는 설명은
 * [label]과 [notes]로 따로 받는다.
 *
 * 인스턴스가 아니라 프로젝트에 매단다. 같은 빌드를 여러 인스턴스가 돌려도 빌드는 하나여야
 * 하고, 인스턴스를 지웠다고 그 빌드 기록이 사라져서도 안 된다.
 */
@Table("game_build")
data class GameBuildEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    @Column("version")
    val version: String,

    @Column("label")
    val label: String? = null,

    @Column("notes")
    val notes: String? = null,

    @Column("created_at")
    val createdAt: Instant,

    @Column("updated_at")
    val updatedAt: Instant
)
