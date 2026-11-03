package kr.artel.orchestration.gameidentity.dto

import org.springframework.data.relational.core.mapping.Column
import java.time.Instant

/** 목록과 내부 목록이 읽는 한 줄. 공개 응답과 내부 응답이 이 한 모양에서 칸을 골라 낸다. */
data class GameIdentityRow(
    @Column("id") val id: Long,
    @Column("name") val name: String,
    @Column("kind") val kind: String,
    @Column("base") val base: Int?,
    @Column("meaning") val meaning: String,
    @Column("changes_when") val changesWhen: String,
    @Column("identifies") val identifies: String,
    @Column("role") val role: String,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("version") val version: Int,
    @Column("updated_at") val updatedAt: Instant,
)
