package kr.artel.orchestration.gamefact.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * game_fact — 게임 개발자가 MCP 로 선언한 fact 하나(V174). 게임 값 하나에 플레이어 말의 이름과 뜻을 붙인 것이다.
 *
 * 쓰기는 `GameFactRepository.upsertByName` 하나로만 한다(`GameIdentityEntity` 와 같은 이유). 이 타입은 repository 의
 * 타입 인자로만 쓴다.
 */
@Table("game_fact")
data class GameFactEntity(
    @Id
    val id: Long? = null,
    @Column("project_id") val projectId: Long,
    /** snake_case 이름. 유일 범위는 프로젝트다(`uk_game_fact_project_name`). */
    @Column("name") val name: String,
    /** `objects`·`statics` 중 하나. */
    @Column("scope") val scope: String,
    /** 엔진이 읽는 이름 그대로. 예: `CardManager.myCards.Count`. */
    @Column("member") val member: String,
    /** `integer`·`number`·`boolean`·`string` 중 하나. */
    @Column("type") val type: String,
    @Column("unit") val unit: String? = null,
    @Column("means") val means: String,
    @Column("game_build_id") val gameBuildId: Long,
    @Column("version") val version: Int = 1,
    @Column("created_by") val createdBy: Long? = null,
    @Column("updated_by") val updatedBy: Long? = null,
    @Column("created_at") val createdAt: Instant? = null,
    @Column("updated_at") val updatedAt: Instant? = null,
)
