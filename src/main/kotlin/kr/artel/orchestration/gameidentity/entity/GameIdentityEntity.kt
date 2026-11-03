package kr.artel.orchestration.gameidentity.entity

import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * game_identity — 게임 개발자가 MCP 로 선언한 게임 값 하나의 뜻(V173).
 *
 * developer macro(`developer_macro`)는 실행하는 행동이고 이 행은 읽는 값이다. 읽는 쪽은 agent-server 의
 * planner–executer 엔진이고 런 시작 때 읽는다.
 *
 * V174 이후에는 기존 행을 migration source로 보존한다. 신규 쓰기는 별도 revision과 build binding query를 사용한다.
 * 이 타입은 repository의 타입 인자로만 쓴다.
 */
@Table("game_identity")
data class GameIdentityEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    /** 게임 state 가 부르는 이름 그대로의 `Type.member`. 유일 범위는 프로젝트다(`uk_game_identity_project_name`). */
    @Column("name")
    val name: String,

    /** `ordinal`·`enum`·`id`·`flag`·`text` 중 하나. */
    @Column("kind")
    val kind: String,

    /** `ordinal` 이 세기 시작하는 수(0 또는 1). 선언하지 않았으면 null 이다. */
    @Column("base")
    val base: Int? = null,

    @Column("meaning")
    val meaning: String,

    @Column("changes_when")
    val changesWhen: String,

    /** 값이 서로 가르는 대상의 짧은 소문자 명사. 예: `stage`, `map chapter`. */
    @Column("identifies")
    val identifies: String,

    /** `current`·`progress`·`other` 중 하나. */
    @Column("role")
    val role: String,

    /** 이 선언을 쓸 때 기준으로 삼은 build. 마지막 등록의 값이다. */
    @Column("game_build_id")
    val gameBuildId: Long,

    /** 선언한 칸이 실제로 바뀔 때만 오른다. */
    @Column("version")
    val version: Int = 1,

    @Column("created_by")
    val createdBy: Long? = null,

    @Column("updated_by")
    val updatedBy: Long? = null,

    @Column("created_at")
    val createdAt: Instant? = null,

    @Column("updated_at")
    val updatedAt: Instant? = null,
)
