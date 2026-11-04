package kr.artel.orchestration.gamefact.dto

import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

/** fact 목록과 upsert 가 읽는 한 줄. */
data class GameFactRow(
    @Column("id") val id: Long,
    @Column("name") val name: String,
    @Column("scope") val scope: String,
    @Column("member") val member: String,
    @Column("type") val type: String,
    @Column("unit") val unit: String?,
    @Column("means") val means: String,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("version") val version: Int,
    @Column("updated_at") val updatedAt: Instant,
)

/** rule 목록과 upsert 가 읽는 한 줄. */
data class GameRuleRow(
    @Column("id") val id: Long,
    @Column("name") val name: String,
    @Column("says") val says: String,
    @Column("facts") val facts: Array<String>,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("version") val version: Int,
    @Column("updated_at") val updatedAt: Instant,
)
