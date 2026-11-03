package kr.artel.orchestration.gamefact.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * game_rule — 게임 개발자가 MCP 로 선언한 rule 하나(V174). 값과 행동의 관계를 말하는 게임 지식 한 문장이고, 자기가 말하는
 * fact 의 이름을 `facts` 에 싣는다.
 *
 * 쓰기는 `GameRuleRepository.upsertByName` 하나로만 한다. 이 타입은 repository 의 타입 인자로만 쓴다.
 */
@Table("game_rule")
data class GameRuleEntity(
    @Id
    val id: Long? = null,
    @Column("project_id") val projectId: Long,
    @Column("name") val name: String,
    @Column("says") val says: String,
    @Column("facts") val facts: Array<String>,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("version") val version: Int = 1,
    @Column("created_by") val createdBy: Long? = null,
    @Column("updated_by") val updatedBy: Long? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null,
)
