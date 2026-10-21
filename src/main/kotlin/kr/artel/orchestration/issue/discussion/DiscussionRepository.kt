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
    private val postSelect = selectPosts("d.body")
    // 목록은 excerpt 만 쓴다. 본문(최대 20,000자)을 행마다 DB 에서 끌어오지 않도록 잘라서 읽는다.
    private val postListSelect = selectPosts("left(d.body, 180) AS body")

    private fun selectPosts(body: String) =
        """
        SELECT d.id, d.project_id, d.author_id, d.title, $body, d.purpose, d.state, d.pinned,
          d.mention_ids, d.decision_comment_id, d.version, d.request_hash, d.created_at, d.updated_at, d.project_no,
          d.game_build_id, (SELECT gb.version FROM game_build gb WHERE gb.id = d.game_build_id) AS build_version,
          u.nickname AS author_name, u.user_tag,
          (SELECT count(*) FROM issue_discussion_issue l WHERE l.discussion_id=d.id) AS issue_count,
          (SELECT count(*) FROM issue_discussion_comment c WHERE c.discussion_id=d.id) AS comment_count,
          EXISTS(SELECT 1 FROM issue_discussion_subscription s WHERE s.discussion_id=d.id AND s.user_id=:userId) AS subscribed,
          (SELECT count(*) FROM issue_discussion_reviewer v WHERE v.discussion_id=d.id) AS reviewer_count,
          (SELECT count(*) FROM issue_discussion_reviewer v WHERE v.discussion_id=d.id AND v.approved_at IS NOT NULL) AS approval_count
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
        if (filter.state != null)
            conditions += if (filter.includeDrafts) "d.state IN (:state, 'DRAFT')" else "d.state=:state"
        if (filter.mine) conditions += "d.author_id=:userId"
        if (filter.subscribed)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_subscription s WHERE s.discussion_id=d.id AND s.user_id=:userId)"
        if (filter.issueId != null)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_issue l WHERE l.discussion_id=d.id AND l.issue_id=:issueId)"
        if (filter.query.isNotEmpty()) conditions += "(d.title ILIKE :query OR d.body ILIKE :query)"
        if (filter.categoryId != null)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_category k WHERE k.discussion_id=d.id AND k.category_id=:categoryId)"
        if (cursor != null) conditions += "(d.pinned,d.id)<(:pinned,:beforeId)"
        var statement =
            database
                .sql(
                    postListSelect +
                        " WHERE " +
                        conditions.joinToString(" AND ") +
                        " ORDER BY d.pinned DESC,d.id DESC LIMIT :size"
                )
                .bind("projectId", projectId)
                .bind("userId", userId)
                .bind("size", size)
        filter.state?.let { statement = statement.bind("state", it) }
        filter.issueId?.let { statement = statement.bind("issueId", it) }
        filter.categoryId?.let { statement = statement.bind("categoryId", it) }
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

    /** 쪽 번호 목록. 고정 글 먼저, 그다음 최신 글. [offset] 부터 [size] 개와 전체 수. */
    internal suspend fun page(
        projectId: Long,
        userId: Long,
        filter: DiscussionFilter,
        offset: Int,
        size: Int,
    ): Pair<List<DiscussionRow>, Long> {
        val conditions = mutableListOf("d.project_id=:projectId")
        if (filter.state != null)
            conditions += if (filter.includeDrafts) "d.state IN (:state, 'DRAFT')" else "d.state=:state"
        if (filter.mine) conditions += "d.author_id=:userId"
        if (filter.subscribed)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_subscription s WHERE s.discussion_id=d.id AND s.user_id=:userId)"
        if (filter.issueId != null)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_issue l WHERE l.discussion_id=d.id AND l.issue_id=:issueId)"
        if (filter.query.isNotEmpty()) conditions += "(d.title ILIKE :query OR d.body ILIKE :query)"
        if (filter.categoryId != null)
            conditions +=
                "EXISTS(SELECT 1 FROM issue_discussion_category k WHERE k.discussion_id=d.id AND k.category_id=:categoryId)"
        if (filter.gameBuildId != null) conditions += "d.game_build_id=:gameBuildId"
        val where = " WHERE " + conditions.joinToString(" AND ")
        fun DatabaseClient.GenericExecuteSpec.bindFilter(): DatabaseClient.GenericExecuteSpec {
            var statement = bind("projectId", projectId)
            if (filter.mine || filter.subscribed) statement = statement.bind("userId", userId)
            filter.state?.let { statement = statement.bind("state", it) }
            filter.issueId?.let { statement = statement.bind("issueId", it) }
            filter.categoryId?.let { statement = statement.bind("categoryId", it) }
            filter.gameBuildId?.let { statement = statement.bind("gameBuildId", it) }
            if (filter.query.isNotEmpty())
                statement = statement.bind(
                    "query",
                    "%" + filter.query.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%",
                )
            return statement
        }
        val rows =
            database
                .sql(postListSelect + where + " ORDER BY d.pinned DESC,d.id DESC LIMIT :size OFFSET :offset")
                .bindFilter()
                .bind("userId", userId)
                .bind("size", size)
                .bind("offset", offset)
                .map { row -> post(row) }
                .all()
                .collectList()
                .awaitSingle()
        val total =
            database
                .sql("SELECT count(*) AS n FROM issue_discussion d$where")
                .bindFilter()
                .map { row -> row.long("n") }
                .one()
                .awaitSingle()
        return rows to total
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
        state: String = "OPEN",
    ): Triple<Long, String, Boolean> {
        val inserted =
            database
                .sql(
                    """INSERT INTO issue_discussion(project_id,author_id,title,body,purpose,mention_ids,client_request_id,request_hash,state)
            VALUES(:projectId,:userId,:title,:body,:purpose,:mentions,:requestId,:hash,:state)
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
                .bind("state", state)
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
        SELECT i.id,i.title,i.severity,i.status,i.qa_try_id,q.qa_run_id,i.project_no FROM issue i
        JOIN qa_try q ON q.id=i.qa_try_id JOIN test_scenario t ON t.id=q.test_scenario_id
        WHERE t.project_id=:projectId AND i.id<:beforeId AND i.archived_at IS NULL AND (:query='' OR position(lower(:query) in lower(i.title))>0)
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
                    row.get("qa_run_id", Long::class.javaObjectType)?.toString(),
                    row.get("project_no", Long::class.javaObjectType)?.toString(),
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
        SELECT i.id,i.title,i.severity,i.status,i.qa_try_id,q.qa_run_id,i.project_no FROM issue_discussion_issue l
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
                    row.get("qa_run_id", Long::class.javaObjectType)?.toString(),
                    row.get("project_no", Long::class.javaObjectType)?.toString(),
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
            if (commentId == null) statement.bindNull("commentId", Long::class.javaObjectType)
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
                    """INSERT INTO issue_discussion_comment(discussion_id,author_id,body,mention_ids,client_request_id,request_hash,parent_id)
            VALUES(:id,:userId,:body,:mentions,:requestId,:hash,:parentId) ON CONFLICT(discussion_id,author_id,client_request_id) DO NOTHING"""
                )
                .let { statement ->
                    content.parentId?.let { statement.bind("parentId", it) }
                        ?: statement.bindNull("parentId", Long::class.javaObjectType)
                }
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

    /**
     * 댓글을 지운다. 답글은 `parent_id` FK cascade 로 함께 지워진다(V120). 결정으로 지정된 댓글(또는 그 답글)이면
     * 결정을 먼저 푼다 — 결정 FK 는 지운 댓글을 가리킬 수 없다.
     */
    internal suspend fun deleteComment(id: Long, commentId: Long) {
        database
            .sql(
                """UPDATE issue_discussion SET decision_comment_id = NULL, updated_at = now()
            WHERE id = :id AND decision_comment_id IN (
              SELECT c.id FROM issue_discussion_comment c WHERE c.discussion_id = :id AND (c.id = :commentId OR c.parent_id = :commentId))"""
            )
            .bind("id", id)
            .bind("commentId", commentId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        database
            .sql("DELETE FROM issue_discussion_comment WHERE discussion_id = :id AND id = :commentId")
            .bind("id", id)
            .bind("commentId", commentId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    /** 글의 빌드 버전을 정한다. null 이면 정하지 않은 글이다. */
    internal suspend fun setBuild(id: Long, gameBuildId: Long?) {
        database
            .sql("UPDATE issue_discussion SET game_build_id = :gameBuildId WHERE id = :id")
            .bind("id", id)
            .let { if (gameBuildId == null) it.bindNull("gameBuildId", Long::class.javaObjectType) else it.bind("gameBuildId", gameBuildId) }
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    internal suspend fun buildInProject(projectId: Long, gameBuildId: Long): Boolean =
        database
            .sql("SELECT EXISTS(SELECT 1 FROM game_build WHERE id = :gameBuildId AND project_id = :projectId) AS ok")
            .bind("gameBuildId", gameBuildId)
            .bind("projectId", projectId)
            .map { row -> row.bool("ok") }
            .one()
            .awaitSingle()

    internal suspend fun projectOfComment(commentId: Long): Long? =
        database
            .sql("SELECT d.project_id FROM issue_discussion_comment c JOIN issue_discussion d ON d.id = c.discussion_id WHERE c.id = :commentId")
            .bind("commentId", commentId)
            .map { row -> row.long("project_id") }
            .one()
            .awaitSingleOrNull()

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

    /** 검토자를 [userIds] 로 바꾼다. 남는 검토자의 승인은 그대로 둔다. */
    internal suspend fun replaceReviewers(id: Long, userIds: List<Long>) {
        database
            .sql("DELETE FROM issue_discussion_reviewer WHERE discussion_id=:id AND NOT (user_id = ANY(CAST(:ids AS BIGINT[])))")
            .bind("id", id)
            .bind("ids", userIds.toTypedArray())
            .fetch()
            .rowsUpdated()
            .awaitSingle()
        if (userIds.isNotEmpty())
            database
                .sql(
                    "INSERT INTO issue_discussion_reviewer(discussion_id,user_id) SELECT :id,unnest(CAST(:ids AS BIGINT[])) ON CONFLICT DO NOTHING"
                )
                .bind("id", id)
                .bind("ids", userIds.toTypedArray())
                .fetch()
                .rowsUpdated()
                .awaitSingle()
    }

    internal suspend fun reviewers(id: Long): List<DiscussionReviewer> =
        database
            .sql(
                """SELECT v.user_id AS author_id, v.approved_at, u.nickname AS author_name, u.user_tag
            FROM issue_discussion_reviewer v JOIN app_user u ON u.id=v.user_id WHERE v.discussion_id=:id ORDER BY u.nickname"""
            )
            .bind("id", id)
            .map { row -> DiscussionReviewer(person(row), row.get("approved_at", OffsetDateTime::class.java)?.toInstant()) }
            .all()
            .collectList()
            .awaitSingle()

    /** 검토자의 승인을 켜거나 끈다. 검토자가 아니면 0 이다. */
    /**
     * 승인을 남기거나 거둔다. 승인한 사람이 곧 검토자다 — 미리 정한 검토자 명단이 없어도 멤버 누구나 승인할 수 있다.
     * 명단에 있던 사람이 거두면 명단에는 남고 승인만 지운다.
     */
    internal suspend fun setApproval(id: Long, userId: Long, approved: Boolean): Long =
        database
            .sql(
                if (approved)
                    """INSERT INTO issue_discussion_reviewer(discussion_id,user_id,approved_at) VALUES(:id,:userId,now())
                ON CONFLICT (discussion_id,user_id) DO UPDATE SET approved_at = now()"""
                else "UPDATE issue_discussion_reviewer SET approved_at = NULL WHERE discussion_id=:id AND user_id=:userId"
            )
            .bind("id", id)
            .bind("userId", userId)
            .fetch()
            .rowsUpdated()
            .awaitSingle()

    /** 다시 연 확인 요청은 처음부터 다시 검토받는다. */
    internal suspend fun clearApprovals(id: Long) {
        database
            .sql("UPDATE issue_discussion_reviewer SET approved_at = NULL WHERE discussion_id=:id")
            .bind("id", id)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
    }

    internal suspend fun approvalCount(id: Long): Long =
        database
            .sql("SELECT count(*) AS n FROM issue_discussion_reviewer WHERE discussion_id=:id AND approved_at IS NOT NULL")
            .bind("id", id)
            .map { row -> row.long("n") }
            .one()
            .awaitSingle()

    internal suspend fun approvalsRequired(projectId: Long): Int =
        database
            .sql("SELECT discussion_approvals_required AS n FROM project WHERE id=:projectId")
            .bind("projectId", projectId)
            .map { row -> row.int("n") }
            .one()
            .awaitSingle()

    internal suspend fun setApprovalsRequired(projectId: Long, value: Int) {
        database
            .sql("UPDATE project SET discussion_approvals_required=:value WHERE id=:projectId")
            .bind("projectId", projectId)
            .bind("value", value)
            .fetch()
            .rowsUpdated()
            .awaitSingle()
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
                reviewerCount = row.long("reviewer_count"),
                approvalCount = row.long("approval_count"),
                number = row.get("project_no", Long::class.javaObjectType)?.toString(),
                gameBuildId = row.get("game_build_id", Long::class.javaObjectType)?.toString(),
                buildVersion = row.get("build_version", String::class.java),
            ),
            row.string("body"),
            row.ids(),
            row.get("decision_comment_id", Long::class.javaObjectType),
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
            row.get("parent_id", Long::class.javaObjectType),
        )

    private fun person(row: Readable) =
        DiscussionPerson(
            row.long("author_id").toString(),
            row.string("author_name"),
            row.string("user_tag"),
        )

    private fun Readable.string(name: String) = get(name, String::class.java)!!

    private fun Readable.long(name: String) = get(name, Long::class.javaObjectType)!!

    private fun Readable.int(name: String) = get(name, Int::class.javaObjectType)!!

    private fun Readable.bool(name: String) =
        get(name, Boolean::class.javaObjectType)!!

    private fun Readable.instant(name: String): Instant =
        get(name, OffsetDateTime::class.java)!!.toInstant()

    private fun Readable.ids(): List<Long> = get("mention_ids", Array<Long>::class.java)!!.toList()
}
