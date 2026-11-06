package kr.artel.orchestration.issue.category

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.dao.DuplicateKeyException
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/*
 * 이슈 · 공유 글 카테고리(ARTEL-1114). 사용자가 프로젝트마다 이름과 색을 정하고, 이슈와 공유 글에 여러 개 붙인다.
 * 색은 화면 팔레트의 이름만 받는다 — 다크 · 라이트 대비를 화면이 책임지므로 자유 hex 를 받지 않는다.
 */

val CATEGORY_COLORS = setOf("gray", "red", "orange", "yellow", "green", "teal", "blue", "violet", "pink")
private const val NAME_MAX = 60
private const val PER_TARGET_MAX = 20

data class IssueCategoryRequest(val name: String? = null, val color: String? = null)

data class IssueCategoryResponse(val id: String, val name: String, val color: String, val issueCount: Long, val discussionCount: Long)

data class IssueCategoryListResponse(val items: List<IssueCategoryResponse>)

/** 이슈 · 공유 글 한 줄에 실리는 카테고리. */
data class CategoryTag(val id: String, val name: String, val color: String)

data class CategoryAssignment(val categoryIds: List<String?>? = null)

@Repository
class IssueCategoryRepository(private val database: DatabaseClient) {
    private val select = """
        SELECT c.id, c.name, c.color,
          (SELECT count(*) FROM issue_category_issue x JOIN issue i ON i.id = x.issue_id
            WHERE x.category_id = c.id AND i.archived_at IS NULL) AS issue_count,
          (SELECT count(*) FROM issue_discussion_category d WHERE d.category_id = c.id) AS discussion_count
        FROM issue_category c
    """

    suspend fun list(projectId: Long): List<IssueCategoryResponse> =
        database.sql("$select WHERE c.project_id = :projectId ORDER BY c.name, c.id")
            .bind("projectId", projectId).map { row -> row.toResponse() }.all().collectList().awaitSingle()

    suspend fun find(projectId: Long, categoryId: Long): IssueCategoryResponse? =
        database.sql("$select WHERE c.project_id = :projectId AND c.id = :categoryId")
            .bind("projectId", projectId).bind("categoryId", categoryId)
            .map { row -> row.toResponse() }.one().awaitSingleOrNull()

    suspend fun insert(projectId: Long, name: String, color: String): Long =
        database.sql("INSERT INTO issue_category (project_id, name, color) VALUES (:projectId, :name, :color) RETURNING id")
            .bind("projectId", projectId).bind("name", name).bind("color", color)
            .map { row -> row.get("id", Long::class.javaObjectType)!! }.one().awaitSingle()

    suspend fun update(categoryId: Long, name: String, color: String) {
        database.sql("UPDATE issue_category SET name = :name, color = :color WHERE id = :categoryId")
            .bind("categoryId", categoryId).bind("name", name).bind("color", color)
            .fetch().rowsUpdated().awaitSingle()
    }

    suspend fun delete(categoryId: Long) {
        database.sql("DELETE FROM issue_category WHERE id = :categoryId").bind("categoryId", categoryId)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** [ids] 중 이 프로젝트의 카테고리. */
    suspend fun ownedIds(projectId: Long, ids: List<Long>): Set<Long> {
        if (ids.isEmpty()) return emptySet()
        return database.sql("SELECT id FROM issue_category WHERE project_id = :projectId AND id = ANY(CAST(:ids AS BIGINT[]))")
            .bind("projectId", projectId).bind("ids", ids.toTypedArray())
            .map { row -> row.get("id", Long::class.javaObjectType)!! }.all().collectList().awaitSingle().toSet()
    }

    /** [table] 의 [column] 행이 가진 카테고리를 [categoryIds] 로 바꾼다. [table] 은 이 파일의 두 표 중 하나다. */
    suspend fun replace(table: Target, targetId: Long, categoryIds: List<Long>) {
        database.sql("DELETE FROM ${table.table} WHERE ${table.column} = :targetId").bind("targetId", targetId)
            .fetch().rowsUpdated().awaitSingle()
        if (categoryIds.isEmpty()) return
        database.sql("INSERT INTO ${table.table} (category_id, ${table.column}) SELECT unnest(CAST(:ids AS BIGINT[])), :targetId")
            .bind("ids", categoryIds.toTypedArray()).bind("targetId", targetId)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 여러 이슈 · 글의 카테고리를 한 문장으로. 줄마다 물으면 N+1 이다. */
    suspend fun tagsOf(table: Target, targetIds: Collection<Long>): Map<Long, List<CategoryTag>> {
        if (targetIds.isEmpty()) return emptyMap()
        return database.sql(
            """
            SELECT x.${table.column} AS target_id, c.id, c.name, c.color FROM ${table.table} x
            JOIN issue_category c ON c.id = x.category_id
            WHERE x.${table.column} = ANY(CAST(:ids AS BIGINT[])) ORDER BY c.name
            """
        )
            .bind("ids", targetIds.distinct().toTypedArray())
            .map { row ->
                row.get("target_id", Long::class.javaObjectType)!! to CategoryTag(
                    row.get("id", Long::class.javaObjectType)!!.toString(),
                    row.get("name", String::class.java)!!,
                    row.get("color", String::class.java)!!,
                )
            }
            .all().collectList().awaitSingle()
            .groupBy({ it.first }, { it.second })
    }

    enum class Target(val table: String, val column: String) {
        ISSUE("issue_category_issue", "issue_id"),
        DISCUSSION("issue_discussion_category", "discussion_id"),
    }

    private fun Readable.toResponse() = IssueCategoryResponse(
        get("id", Long::class.javaObjectType)!!.toString(),
        get("name", String::class.java)!!,
        get("color", String::class.java)!!,
        get("issue_count", Long::class.javaObjectType) ?: 0,
        get("discussion_count", Long::class.javaObjectType) ?: 0,
    )
}

@Service
class IssueCategoryService(
    private val categories: IssueCategoryRepository,
    private val projectAccess: ProjectAccessService,
    private val transactions: TransactionalOperator,
    private val database: DatabaseClient,
) {
    suspend fun list(projectId: Long, userId: Long): IssueCategoryListResponse {
        requireMember(projectId, userId)
        return IssueCategoryListResponse(categories.list(projectId))
    }

    suspend fun create(projectId: Long, userId: Long, request: IssueCategoryRequest): IssueCategoryResponse {
        requireMember(projectId, userId)
        val id = unique { categories.insert(projectId, nameOf(request.name), colorOf(request.color)) }
        return categories.find(projectId, id)!!
    }

    suspend fun update(projectId: Long, userId: Long, categoryId: Long, request: IssueCategoryRequest): IssueCategoryResponse {
        requireMember(projectId, userId)
        val current = categories.find(projectId, categoryId) ?: throw NotFoundException("카테고리를 찾을 수 없습니다.")
        unique { categories.update(categoryId, request.name?.let(::nameOf) ?: current.name, request.color?.let(::colorOf) ?: current.color) }
        return categories.find(projectId, categoryId)!!
    }

    suspend fun delete(projectId: Long, userId: Long, categoryId: Long) {
        requireMember(projectId, userId)
        categories.find(projectId, categoryId) ?: throw NotFoundException("카테고리를 찾을 수 없습니다.")
        categories.delete(categoryId)
    }

    /** 이슈 하나의 카테고리를 [request] 로 바꾼다. 다른 프로젝트 이슈 · 카테고리는 404 다. */
    suspend fun assignToIssue(projectId: Long, userId: Long, issueId: Long, request: CategoryAssignment): List<CategoryTag> {
        requireMember(projectId, userId)
        val ids = validIds(projectId, request.categoryIds)
        transactions.executeAndAwait {
            val owned = database.sql("SELECT 1 FROM issue WHERE id = :issueId AND project_id = :projectId")
                .bind("issueId", issueId).bind("projectId", projectId).map { _ -> 1 }.one().awaitSingleOrNull()
            if (owned == null) throw NotFoundException("이슈를 찾을 수 없습니다.")
            categories.replace(IssueCategoryRepository.Target.ISSUE, issueId, ids)
        }
        return categories.tagsOf(IssueCategoryRepository.Target.ISSUE, listOf(issueId))[issueId].orEmpty()
    }

    /** 공유 글이 가진 카테고리를 바꾼다. 부르는 쪽(글 서비스)이 글 접근 · 편집 권한을 이미 확인했다. */
    suspend fun assignToDiscussion(projectId: Long, discussionId: Long, raw: List<String?>?) {
        categories.replace(IssueCategoryRepository.Target.DISCUSSION, discussionId, validIds(projectId, raw))
    }

    suspend fun validIds(projectId: Long, raw: List<String?>?): List<Long> {
        val values = raw.orEmpty()
        if (values.size > PER_TARGET_MAX) throw BadRequestException("카테고리는 ${PER_TARGET_MAX}개까지입니다.")
        val ids = values.map { it?.toLongOrNull()?.takeIf { id -> id > 0 } ?: throw BadRequestException("유효하지 않은 값: categoryIds") }.distinct()
        if (categories.ownedIds(projectId, ids) != ids.toSet()) throw NotFoundException("카테고리를 찾을 수 없습니다.")
        return ids
    }

    private suspend fun requireMember(projectId: Long, userId: Long) {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
    }

    private suspend fun <T> unique(write: suspend () -> T): T =
        try {
            write()
        } catch (_: DuplicateKeyException) {
            throw ConflictException("같은 이름의 카테고리가 있습니다.")
        }

    private fun nameOf(raw: String?): String {
        val name = raw?.trim().orEmpty()
        if (name.isEmpty() || name.length > NAME_MAX) throw BadRequestException("카테고리 이름은 1~${NAME_MAX}자입니다.")
        return name
    }

    private fun colorOf(raw: String?): String {
        val color = raw ?: "gray"
        if (color !in CATEGORY_COLORS) throw BadRequestException("유효하지 않은 값: color")
        return color
    }
}

@RestController
@RequestMapping("/api/projects/{projectId}")
class IssueCategoryController(private val service: IssueCategoryService) {
    @GetMapping("/issue-categories")
    suspend fun listIssueCategories(@PathVariable projectId: Long, @CurrentUserId userId: Long) = service.list(projectId, userId)

    @PostMapping("/issue-categories")
    @ResponseStatus(HttpStatus.CREATED)
    suspend fun createIssueCategory(@PathVariable projectId: Long, @CurrentUserId userId: Long, @RequestBody request: IssueCategoryRequest) =
        service.create(projectId, userId, request)

    @PutMapping("/issue-categories/{categoryId}")
    suspend fun updateIssueCategory(
        @PathVariable projectId: Long,
        @PathVariable categoryId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: IssueCategoryRequest,
    ) = service.update(projectId, userId, categoryId, request)

    @DeleteMapping("/issue-categories/{categoryId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun deleteIssueCategory(@PathVariable projectId: Long, @PathVariable categoryId: Long, @CurrentUserId userId: Long) =
        service.delete(projectId, userId, categoryId)

    @PutMapping("/issues/{issueId}/categories")
    suspend fun setIssueCategories(
        @PathVariable projectId: Long,
        @PathVariable issueId: Long,
        @CurrentUserId userId: Long,
        @RequestBody request: CategoryAssignment,
    ) = service.assignToIssue(projectId, userId, issueId, request)
}
