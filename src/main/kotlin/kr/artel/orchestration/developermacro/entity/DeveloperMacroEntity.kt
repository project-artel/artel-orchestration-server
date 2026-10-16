package kr.artel.orchestration.developermacro.entity

import io.r2dbc.postgresql.codec.Json
import org.springframework.data.annotation.Id
import org.springframework.data.relational.core.mapping.Column
import org.springframework.data.relational.core.mapping.Table
import java.time.Instant

/**
 * developer_macro — 게임 개발자가 MCP 로 등록한 macro 한 건(V116, ARTEL-1088).
 *
 * 기존 `macro`(QA agent 가 돌려 보고 등록한 것, build 의 `content_map` 소속)와 다른 표다. 개발자 macro 는
 * 돌려 보지 않은 정의이고 `content_map` 이 없어도 있어야 한다. 읽는 쪽은 planner–executer 엔진뿐이다.
 *
 * 쓰기는 `DeveloperMacroRepository.upsertByName` 하나로만 한다. `save()` 로 쓰면 버전 비교가 SQL 밖으로
 * 나가 같은 이름이 동시에 둘 올 때 그 사이로 빠져나간다. 이 타입은 repository 의 타입 인자로만 쓴다.
 */
@Table("developer_macro")
data class DeveloperMacroEntity(
    @Id
    val id: Long? = null,

    @Column("project_id")
    val projectId: Long,

    /** 진입점 `def` 의 이름. 유일 범위는 프로젝트다(`uk_developer_macro_project_name`). */
    @Column("name")
    val name: String,

    /** **원본.** 사람이 읽고 diff 하는 칸이다. */
    @Column("source")
    val source: String,

    /** **파생.** agent-server 가 검사를 통과시키며 돌려준 정의. 질의 대상이 아니다. */
    @Column("definition_json")
    val definitionJson: Json,

    /** 진입점 `def` 의 parameter 이름. 순서가 뜻을 가진다. */
    @Column("parameter_names")
    val parameterNames: Json,

    @Column("docstring")
    val docstring: String,

    /** 이 macro 를 쓸 때 기준으로 삼은 build. 마지막 등록의 값이다. */
    @Column("game_build_id")
    val gameBuildId: Long,

    /** 정의가 실제로 바뀔 때만 오른다. */
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
