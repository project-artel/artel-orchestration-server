package kr.artel.orchestration.gamenote.entity

import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * 게임 노트 한 버전. PK 는 (`project_id`, `version`) 이고, 저장마다 행이 하나 늘 뿐 고치지 않는다.
 */
@Table("project_game_note")
data class ProjectGameNoteEntity(
    @Column("project_id")
    val projectId: Long,

    @Column("version")
    val version: Int,

    @Column("body")
    val body: String,

    /** [GenreModule.wire] 값. 지금 enum 에 없는 값이 남아 있을 수 있어 문자열로 읽는다. */
    @Column("genres")
    val genres: List<String>,

    @Column("created_by")
    val createdBy: Long,

    @Column("created_at")
    val createdAt: Instant
) {
    /** agent-server 에서 module 이 빠진 뒤 남은 이름은 버린다. 그 이름 때문에 노트 전체를 못 읽는 편이 더 나쁘다. */
    fun genreModules(): List<GenreModule> = genres.mapNotNull { GenreModule.fromWire(it) }
}
