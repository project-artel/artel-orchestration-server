package kr.artel.orchestration.projectagent.repository

import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitOneOrNull
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.stereotype.Repository
import java.time.Instant

/** `app_user.project_agent_model` 과 `project_agent_key_source`. model 이 null 이면 key source 도 null 이다. */
data class UserProjectAgentModelRow(val model: String?, val keySource: String?)

/**
 * project agent model 의 사용자 선택(`app_user`)과 프로젝트 설정(`project`)을 읽고 쓴다.
 *
 * `AppUserEntity` 와 `ProjectEntity` 에 칸을 더하지 않고 여기서 따로 다룬다. 두 entity 는 여러 곳에서
 * `copy` 로 다시 저장되는데, 칸을 더하면 그 저장들이 모두 이 값도 함께 쓰게 된다. 이 값만 바꾸는 UPDATE
 * 를 두면 그 저장들과 엇갈려도 서로의 값을 덮지 않는다.
 */
@Repository
class ProjectAgentModelRepository(private val databaseClient: DatabaseClient) {

    /** 사용자가 없으면 null 이다. */
    suspend fun findUserChoice(appUserId: Long): UserProjectAgentModelRow? =
        databaseClient.sql("SELECT project_agent_model, project_agent_key_source FROM app_user WHERE id = :id")
            .bind("id", appUserId)
            .map { row, _ ->
                UserProjectAgentModelRow(
                    model = row.get("project_agent_model", String::class.java),
                    keySource = row.get("project_agent_key_source", String::class.java)
                )
            }
            .awaitOneOrNull()

    /** 바꾼 행 수. 사용자가 없으면 0 이다. */
    suspend fun updateUserChoice(appUserId: Long, model: String?, keySource: String?, updatedAt: Instant): Long =
        databaseClient.sql(
            """
            UPDATE app_user
               SET project_agent_model = :model, project_agent_key_source = :keySource, updated_at = :updatedAt
             WHERE id = :id
            """
        )
            .bindNullable("model", model)
            .bindNullable("keySource", keySource)
            .bind("updatedAt", updatedAt)
            .bind("id", appUserId)
            .fetch()
            .awaitRowsUpdated()

    /** 지워지지 않은 프로젝트의 설정. 설정이 없거나 프로젝트가 없으면 null 이다. */
    suspend fun findProjectModel(projectId: Long): String? =
        databaseClient.sql("SELECT project_agent_model FROM project WHERE id = :id AND deleted_at IS NULL")
            .bind("id", projectId)
            .map { row, _ -> ProjectModelRow(row.get("project_agent_model", String::class.java)) }
            .awaitOneOrNull()
            ?.model

    /**
     * 바꾼 행 수. `updated_at` 은 건드리지 않는다 — 그 칸은 프로젝트 목록의 정렬 기준이라, model 을 바꿨다고
     * 프로젝트가 목록 맨 위로 올라가면 안 된다.
     */
    suspend fun updateProjectModel(projectId: Long, model: String?): Long =
        databaseClient.sql("UPDATE project SET project_agent_model = :model WHERE id = :id AND deleted_at IS NULL")
            .bindNullable("model", model)
            .bind("id", projectId)
            .fetch()
            .awaitRowsUpdated()

    /** `map` 은 null 을 돌려줄 수 없어, 칸이 비어 있어도 행이 있다는 것을 감싸서 넘긴다. */
    private data class ProjectModelRow(val model: String?)

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: String?) =
        if (value == null) bindNull(name, String::class.java) else bind(name, value)
}
