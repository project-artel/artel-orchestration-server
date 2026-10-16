package kr.artel.orchestration.issue.discussion

import io.r2dbc.spi.Readable
import java.time.Instant
import java.time.OffsetDateTime
import java.util.UUID
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository

@Repository
class DiscussionRepository(private val database: DatabaseClient) {
    // 보고서 본문은 목록에 실지 않는다. 집계는 각 FK index를 사용하며 페이지 크기로 제한된다.
    private val postSelect =
        """
        SELECT d.*, u.nickname AS author_name, u.user_tag,
          (SELECT count(*) FROM issue_discussion_issue l WHERE l.discussion_id=d.id) AS issue_count,
          (SELECT count(*) FROM issue_discussion_comment c WHERE c.discussion_id=d.id) AS comment_count,
          EXISTS(SELECT 1 FROM issue_discussion_subscription s WHERE s.discussion_id=d.id AND s.user_id=:userId) AS subscribed
        FROM issue_discussion d JOIN app_user u ON u.id=d.author_id
    """
    private val commentSelect =
        """
        SELECT c.*, u.nickname AS author_name, u.user_tag,
          (SELECT count(*) FROM issue_discussion_reaction r WHERE r.comment_id=c.id) AS reaction_count,
          EXISTS(SELECT 1 FROM issue_discussion_reaction r WHERE r.comment_id=c.id AND r.user_id=:userId) AS reacted
        FROM issue_discussion_comment c JOIN app_user u ON u.id=c.author_id
    """

    internal suspend fun list(
        projectId: Long,
        userId: Long,
        filter: DiscussionFilter,
        cursor: Pair<Boolean, Long>?,
        size: Int,
    ): List<DiscussionRow> {
        val conditions = mutableListOf("d.project_id=:projectId")
        if (filter.state != null) conditions += "d.state=:state"
        if (filter.mine) conditions += "d.author_id=:userId"
        if (filter.subscribed)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_subscription s WHERE s.discussion_id=d.id AND s.user_id=:userId)"
        if (filter.issueId != null)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_issue l WHERE l.discussion_id=d.id AND l.issue_id=:issueId)"
        if (filter.query.isNotEmpty()) conditions += "(d.title ILIKE :query OR d.body ILIKE :query)"
        if (cursor != null) conditions += "(d.pinned,d.id)<(:pinned,:beforeId)"
        var statement =
            database
                .sql(
                    postSelect +
                        " WHERE " +
                        conditions.joinToString(" AND ") +
                        " ORDER BY d.pinned DESC,d.id DESC LIMIT :size"
                )
                .bind("projectId", projectId)
                .bind("userId", userId)
                .bind("size", size)
        filter.state?.let { statement = statement.bind("state", it) }
        filter.issueId?.let { statement = statement.bind("issueId", it) }
        if (filter.query.isNotEmpty())
            statement =
                statement.bind(
                    "query",
                    "%" +
                        filter.query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") +
                        "%",
                )
        if (cursor != null)
            statement = statement.bind("pinned", cursor.first).bind("beforeId", cursor.second)
        return statement.map { row -> post(row) }.all().collectList().awaitSingle()
    }

    internal suspend fun find(projectId: Long, id: Long, userId: Long): DiscussionRow? =
        database
            .sql(postSelect + " WHERE d.project_id=:projectId AND d.id=:id")
            .bind("projectId", projectId)
            .bind("id", id)
            .bind("userId", userId)
            .map { row -> post(row) }
            .one()
            .awaitSingleOrNull()

    internal suspend fun create(
        projectId: Long,
        userId: Long,
        content: DiscussionContent,
        requestId: UUID,
        hash: String,
    ): Triple<Long, String, Boolean> {
        val inserted =
            database
                .sql(
                    """INSERT INTO issue_discussion(project_id,author_id,title,body,purpose,mention_ids,client_request_id,request_hash)
            VALUES(:projectId,:userId,:title,:body,:purpose,:mentions,:requestId,:hash)
            ON CONFLICT(project_id,author_id,client_request_id) DO NOTHING"""
                )
                .bind("projectId", projectId)
                .bind("userId", userId)
                .bind("title", content.title)
                .bind("body", content.body)
                .bind("purpose", content.purpose)
                .bind("mentions", content.mentionIds.toTypedArray())
                .bind("requestId", requestId)
                .bind("hash", hash)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        return database
            .sql(
                "SELECT id,request_hash FROM issue_discussion WHERE project_id=:projectId AND author_id=:userId AND client_request_id=:requestId"
            )
            .bind("projectId", projectId)
            .bind("userId", userId)
            .bind("requestId", requestId)
            .map { row -> Triple(row.long("id"), row.string("request_hash"), inserted == 1L) }
            .one()
            .awaitSingle()
    }

    internal suspend fun edit(id: Long, content: DiscussionContent, version: Int): Boolean =
        database
            .sql(
                """
        UPDATE issue_discussion SET title=:title,body=:body,purpose=:purpose,mention_ids=:mentions,version=version+1,updated_at=now()
        WHERE id=:id AND version=:version
    """
            )
            .bind("id", id)
            .bind("version", version)
            .bind("title", content.title)
            .bind("body", content.body)
            .bind("purpose", content.purpose)
            .bind("mentions", content.mentionIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    internal suspend fun replaceLinks(id: Long, issueIds: List<Long>) {
        database
            .sql("DELETE FROM issue_discussion_issue WHERE discussion_id=:id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (issueIds.isNotEmpty())
            database
                .sql(
                    "INSERT INTO issue_discussion_issue(discussion_id,issue_id) SELECT :id,unnest(CAST(:ids AS BIGINT[]))"
                )
                .bind("id", id)
                .bind("ids", issueIds.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
    }

    internal suspend fun issueOptions(
        projectId: Long,
        query: String,
        beforeId: Long,
        size: Int,
    ): List<DiscussionIssue> =
        database
            .sql(
                """
        SELECT i.id,i.title,i.severity,i.status,i.qa_try_id,q.qa_run_id FROM issue i
        JOIN qa_try q ON q.id=i.qa_try_id JOIN test_scenario t ON t.id=q.test_scenario_id
        WHERE t.project_id=:projectId AND i.id<:beforeId AND (:query='' OR position(lower(:query) in lower(i.title))>0)
        ORDER BY i.id DESC LIMIT :size
    """
            )
            .bind("projectId", projectId)
            .bind("query", query)
            .bind("beforeId", beforeId)
            .bind("size", size)
            .map { row ->
                DiscussionIssue(
                    row.long("id").toString(),
                    row.string("title"),
                    row.string("severity"),
                    row.string("status"),
                    row.long("qa_try_id").toString(),
                    row.get("qa_run_id", java.lang.Long::class.java)?.toString(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

    internal suspend fun validIssueIds(projectId: Long, ids: List<Long>): List<Long> {
        if (ids.isEmpty()) return emptyList()
        return database
            .sql(
                """SELECT i.id FROM issue i JOIN qa_try q ON q.id=i.qa_try_id
            JOIN test_scenario t ON t.id=q.test_scenario_id WHERE t.project_id=:projectId AND i.id IN (:ids)"""
            )
            .bind("projectId", projectId)
            .bind("ids", ids)
            .map { row -> row.long("id") }
            .all()
            .collectList()
            .awaitSingle()
    }

    internal suspend fun issues(id: Long): List<DiscussionIssue> =
        database
            .sql(
                """
        SELECT i.id,i.title,i.severity,i.status,i.qa_try_id,q.qa_run_id FROM issue_discussion_issue l
        JOIN issue i ON i.id=l.issue_id JOIN qa_try q ON q.id=i.qa_try_id WHERE l.discussion_id=:id ORDER BY i.id
    """
            )
            .bind("id", id)
            .map { row ->
                DiscussionIssue(
                    row.long("id").toString(),
                    row.string("title"),
                    row.string("severity"),
                    row.string("status"),
                    row.long("qa_try_id").toString(),
                    row.get("qa_run_id", java.lang.Long::class.java)?.toString(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

    internal suspend fun setState(id: Long, state: String) {
        database
            .sql("UPDATE issue_discussion SET state=:state,updated_at=now() WHERE id=:id")
            .bind("id", id)
            .bind("state", state)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    internal suspend fun setPin(id: Long, enabled: Boolean) {
        database
            .sql("UPDATE issue_discussion SET pinned=:enabled WHERE id=:id")
            .bind("id", id)
            .bind("enabled", enabled)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    internal suspend fun setDecision(id: Long, commentId: Long?) {
        var statement =
            database
                .sql(
                    "UPDATE issue_discussion SET decision_comment_id=:commentId,updated_at=now() WHERE id=:id"
                )
                .bind("id", id)
        statement =
            if (commentId == null) statement.bindNull("commentId", java.lang.Long::class.java)
            else statement.bind("commentId", commentId)
        statement.fetch().rowsUpdated().awaitSingle()
    }

    internal suspend fun subscribe(id: Long, userId: Long, enabled: Boolean) {
        val sql =
            if (enabled)
                "INSERT INTO issue_discussion_subscription(discussion_id,user_id) VALUES(:id,:userId) ON CONFLICT DO NOTHING"
            else
                "DELETE FROM issue_discussion_subscription WHERE discussion_id=:id AND user_id=:userId"
        database.sql(sql).bind("id", id).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
    }

    internal suspend fun comments(
        id: Long,
        userId: Long,
        afterId: Long,
        size: Int,
    ): List<CommentRow> =
        database
            .sql(
                commentSelect +
                    " WHERE c.discussion_id=:id AND c.id>:afterId ORDER BY c.id LIMIT :size"
            )
            .bind("id", id)
            .bind("userId", userId)
            .bind("afterId", afterId)
            .bind("size", size)
            .map { row -> comment(row) }
            .all()
            .collectList()
            .awaitSingle()

    internal suspend fun comment(id: Long, commentId: Long, userId: Long): CommentRow? =
        database
            .sql(commentSelect + " WHERE c.discussion_id=:id AND c.id=:commentId")
            .bind("id", id)
            .bind("commentId", commentId)
            .bind("userId", userId)
            .map { row -> comment(row) }
            .one()
            .awaitSingleOrNull()

    internal suspend fun createComment(
        id: Long,
        userId: Long,
        content: CommentContent,
        requestId: UUID,
        hash: String,
    ): Pair<Long, String> {
        val inserted =
            database
                .sql(
                    """INSERT INTO issue_discussion_comment(discussion_id,author_id,body,mention_ids,client_request_id,request_hash)
            VALUES(:id,:userId,:body,:mentions,:requestId,:hash) ON CONFLICT(discussion_id,author_id,client_request_id) DO NOTHING"""
                )
                .bind("id", id)
                .bind("userId", userId)
                .bind("body", content.body)
                .bind("mentions", content.mentionIds.toTypedArray())
                .bind("requestId", requestId)
                .bind("hash", hash)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        if (inserted == 1L)
            database
                .sql("UPDATE issue_discussion SET updated_at=now() WHERE id=:id")
                .bind("id", id)
                .fetch()
                .rowsUpdated()
                .awaitSingle()
        return database
            .sql(
                "SELECT id,request_hash FROM issue_discussion_comment WHERE discussion_id=:id AND author_id=:userId AND client_request_id=:requestId"
            )
            .bind("id", id)
            .bind("userId", userId)
            .bind("requestId", requestId)
            .map { row -> row.long("id") to row.string("request_hash") }
            .one()
            .awaitSingle()
    }

    internal suspend fun editComment(id: Long, content: CommentContent, version: Int): Boolean =
        database
            .sql(
                """
        UPDATE issue_discussion_comment SET body=:body,mention_ids=:mentions,version=version+1,updated_at=now() WHERE id=:id AND version=:version
    """
            )
            .bind("id", id)
            .bind("body", content.body)
            .bind("mentions", content.mentionIds.toTypedArray())
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .awaitSingle() == 1L

    internal suspend fun touch(id: Long) {
        database
            .sql("UPDATE issue_discussion SET updated_at=now() WHERE id=:id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    internal suspend fun react(id: Long, userId: Long, enabled: Boolean) {
        val sql =
            if (enabled)
                "INSERT INTO issue_discussion_reaction(comment_id,user_id) VALUES(:id,:userId) ON CONFLICT DO NOTHING"
            else "DELETE FROM issue_discussion_reaction WHERE comment_id=:id AND user_id=:userId"
        database.sql(sql).bind("id", id).bind("userId", userId).fetch().rowsUpdated().awaitSingle()
    }

    private fun post(row: Readable) =
        DiscussionRow(
            DiscussionSummary(
                row.long("id").toString(),
                row.string("title"),
                row.string("body").take(180),
                row.string("purpose"),
                row.string("state"),
                row.bool("pinned"),
                person(row),
                row.long("issue_count"),
                row.long("comment_count"),
                row.instant("created_at"),
                row.instant("updated_at"),
                row.bool("subscribed"),
                row.int("version"),
            ),
            row.string("body"),
            row.ids(),
            row.get("decision_comment_id", java.lang.Long::class.java)?.toLong(),
            row.string("request_hash"),
        )

    private fun comment(row: Readable) =
        CommentRow(
            row.long("id"),
            row.string("body"),
            person(row),
            row.ids(),
            row.instant("created_at"),
            row.instant("updated_at"),
            row.int("version"),
            row.long("reaction_count"),
            row.bool("reacted"),
            row.string("request_hash"),
        )

    private fun person(row: Readable) =
        DiscussionPerson(
            row.long("author_id").toString(),
            row.string("author_name"),
            row.string("user_tag"),
        )

    private fun Readable.string(name: String) = get(name, String::class.java)!!

    private fun Readable.long(name: String) = get(name, java.lang.Long::class.java)!!.toLong()

    private fun Readable.int(name: String) = get(name, java.lang.Integer::class.java)!!.toInt()

    private fun Readable.bool(name: String) =
        get(name, java.lang.Boolean::class.java)!!.booleanValue()

    private fun Readable.instant(name: String): Instant =
        get(name, OffsetDateTime::class.java)!!.toInstant()

    private fun Readable.ids(): List<Long> = get("mention_ids", Array<Long>::class.java)!!.toList()
}
