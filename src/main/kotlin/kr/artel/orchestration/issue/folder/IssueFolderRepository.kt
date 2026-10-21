package kr.artel.orchestration.issue.folder

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

/** 이슈 folder 와 그 항목(V119). 쓰기는 모두 [IssueFolderService] 가 project lock 을 잡은 트랜잭션 안에서 부른다. */
@Repository
class IssueFolderRepository(private val database: DatabaseClient) {

    data class Row(val id: Long, val parentId: Long?, val name: String, val issueCount: Int)

    private val select = """
        SELECT f.id, f.parent_id, f.name,
               (SELECT count(*) FROM issue_folder_item i WHERE i.folder_id = f.id)::int AS issue_count
        FROM issue_folder f
    """

    suspend fun list(projectId: Long): List<Row> =
        database.sql("$select WHERE f.project_id = :projectId ORDER BY f.name, f.id")
            .bind("projectId", projectId)
            .map { row -> row.toRow() }
            .all().collectList().awaitSingle()

    suspend fun find(projectId: Long, folderId: Long): Row? =
        database.sql("$select WHERE f.project_id = :projectId AND f.id = :folderId")
            .bind("projectId", projectId)
            .bind("folderId", folderId)
            .map { row -> row.toRow() }
            .one().awaitSingleOrNull()

    suspend fun children(folderId: Long): List<Row> =
        database.sql("$select WHERE f.parent_id = :folderId ORDER BY f.id")
            .bind("folderId", folderId)
            .map { row -> row.toRow() }
            .all().collectList().awaitSingle()

    suspend fun insert(projectId: Long, parentId: Long?, name: String): Long =
        database.sql("INSERT INTO issue_folder (project_id, parent_id, name) VALUES (:projectId, :parentId, :name) RETURNING id")
            .bind("projectId", projectId)
            .bindNullable("parentId", parentId)
            .bind("name", name)
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .one().awaitSingle()

    suspend fun update(folderId: Long, parentId: Long?, name: String) {
        database.sql("UPDATE issue_folder SET parent_id = :parentId, name = :name, updated_at = now() WHERE id = :folderId")
            .bind("folderId", folderId)
            .bindNullable("parentId", parentId)
            .bind("name", name)
            .fetch().rowsUpdated().awaitSingle()
    }

    suspend fun delete(folderId: Long) {
        database.sql("DELETE FROM issue_folder WHERE id = :folderId")
            .bind("folderId", folderId)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 같은 부모 아래 같은 이름이 있나. [excludeId] 는 자기 자신을 빼고 볼 때. */
    suspend fun siblingNameTaken(projectId: Long, parentId: Long?, name: String, excludeId: Long?): Boolean =
        database.sql(
            """
            SELECT EXISTS (SELECT 1 FROM issue_folder
                WHERE project_id = :projectId AND parent_id IS NOT DISTINCT FROM :parentId AND name = :name
                  AND (:excludeId::bigint IS NULL OR id <> :excludeId))
            """
        )
            .bind("projectId", projectId)
            .bindNullable("parentId", parentId)
            .bind("name", name)
            .bindNullable("excludeId", excludeId)
            .map { row -> row.get(0, Boolean::class.javaObjectType)!! }
            .one().awaitSingle()

    /** [candidateId] 가 [folderId] 자신이거나 그 아래에 있나. folder 를 자기 하위로 옮기는 순환을 막는다. */
    suspend fun isSelfOrDescendant(folderId: Long, candidateId: Long): Boolean =
        database.sql(
            """
            WITH RECURSIVE tree AS (
                SELECT id FROM issue_folder WHERE id = :folderId
                UNION ALL
                SELECT f.id FROM issue_folder f JOIN tree t ON f.parent_id = t.id
            )
            SELECT EXISTS (SELECT 1 FROM tree WHERE id = :candidateId)
            """
        )
            .bind("folderId", folderId)
            .bind("candidateId", candidateId)
            .map { row -> row.get(0, Boolean::class.javaObjectType)!! }
            .one().awaitSingle()

    suspend fun addItems(folderId: Long, issueIds: List<Long>) {
        if (issueIds.isEmpty()) return
        database.sql(
            "INSERT INTO issue_folder_item (folder_id, issue_id) SELECT :folderId, unnest(CAST(:ids AS BIGINT[])) ON CONFLICT DO NOTHING"
        )
            .bind("folderId", folderId)
            .bind("ids", issueIds.toTypedArray())
            .fetch().rowsUpdated().awaitSingle()
    }

    suspend fun removeItems(folderId: Long, issueIds: List<Long>) {
        if (issueIds.isEmpty()) return
        database.sql("DELETE FROM issue_folder_item WHERE folder_id = :folderId AND issue_id = ANY(CAST(:ids AS BIGINT[]))")
            .bind("folderId", folderId)
            .bind("ids", issueIds.toTypedArray())
            .fetch().rowsUpdated().awaitSingle()
    }

    suspend fun copyItems(sourceId: Long, targetId: Long) {
        database.sql(
            "INSERT INTO issue_folder_item (folder_id, issue_id) SELECT :targetId, issue_id FROM issue_folder_item WHERE folder_id = :sourceId"
        )
            .bind("sourceId", sourceId)
            .bind("targetId", targetId)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** [ids] 중 이 프로젝트의 이슈. 이슈는 프로젝트를 직접 들지 않아 실행 · 시나리오를 거친다. */
    suspend fun projectIssueIds(projectId: Long, ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return database.sql(
            """
            SELECT i.id FROM issue i
            JOIN qa_try q ON q.id = i.qa_try_id JOIN test_scenario t ON t.id = q.test_scenario_id
            WHERE t.project_id = :projectId AND i.id = ANY(CAST(:ids AS BIGINT[]))
            """
        )
            .bind("projectId", projectId)
            .bind("ids", ids.toTypedArray())
            .map { row -> row.get("id", Long::class.javaObjectType)!! }
            .all().collectList().awaitSingle().toSet()
    }

    /** 이 프로젝트의 folder 쓰기를 한 번에 하나씩. 트랜잭션 안에서 불러야 한다(`TestCaseFolderService` 와 같은 이유). */
    suspend fun lockProject(projectId: Long) {
        database.sql("SELECT pg_advisory_xact_lock(hashtextextended('issue_folder:' || :projectId, 0))")
            .bind("projectId", projectId)
            .fetch().rowsUpdated().awaitSingle()
    }

    private fun Readable.toRow() = Row(
        id = get("id", Long::class.javaObjectType)!!,
        parentId = get("parent_id", Long::class.javaObjectType),
        name = get("name", String::class.java)!!,
        issueCount = get("issue_count", Int::class.javaObjectType) ?: 0,
    )

    private fun DatabaseClient.GenericExecuteSpec.bindNullable(name: String, value: Long?) =
        if (value == null) bindNull(name, Long::class.javaObjectType) else bind(name, value)
}
