package kr.artel.orchestration.issue

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import java.time.Instant
import java.util.UUID
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.issue.discussion.DiscussionDraft
import kr.artel.orchestration.issue.discussion.DiscussionService
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.tracker.repository.IssueTrackerLinkRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.test.annotation.DirtiesContext
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DiscussionHttpIntegrationTest {

    @LocalServerPort private val port: Int = 0

    @Autowired private lateinit var discussions: DiscussionService
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var trackerLinkRepository: IssueTrackerLinkRepository
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var testScenarioRepository: TestScenarioRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /** 리액티브 트랜잭션은 롤백되지 않고 DB를 공유하므로 FK 순서대로 직접 비운다. */
    @BeforeEach
    @AfterEach
    fun clean(): Unit = runBlocking {
        trackerLinkRepository.deleteAll()
        issueRepository.deleteAll()
        qaTryRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testScenarioRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `게시글과 댓글을 저장하고 논의 상태를 QA 상태와 분리한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        val issue = seedIssue(seed.qaTryId, "MAJOR", "보상이 없다")
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        val created = request(owner.token, "POST", path, draft(issue))
        val id = created["id"].asText()
        assertThat(created["id"].isTextual).isTrue()
        assertThat(created["issues"][0]["id"].asText()).isEqualTo(issue.toString())
        assertThat(created["canEdit"].asBoolean()).isTrue()
        assertThat(created["subscribed"].asBoolean()).isTrue()
        val comment =
            request(
                owner.token,
                "POST",
                "$path/$id/comments",
                mapOf(
                    "body" to "재현 확인",
                    "clientRequestId" to UUID.randomUUID().toString(),
                    "mentionedUserIds" to listOf(owner.userId.toString()),
                ),
            )
        assertThat(comment["mentions"][0]["id"].asText()).isEqualTo(owner.userId.toString())
        val decided =
            request(
                owner.token,
                "PUT",
                "$path/$id/decision",
                mapOf("commentId" to comment["id"].asText()),
            )
        assertThat(decided["decision"]["body"].asText()).isEqualTo("재현 확인")
        request(owner.token, "PUT", "$path/$id/state", mapOf("state" to "CLOSED"))
        assertThat(issueRepository.findById(issue)!!.status).isEqualTo("OPEN")
        val page = request(owner.token, "GET", "$path?state=CLOSED&subscribed=true")
        assertThat(page["items"]).hasSize(1)
        assertThat(page["items"][0]["commentCount"].asInt()).isEqualTo(1)
        assertThat(page["items"][0].has("body")).isFalse()
        val original = request(owner.token, "GET", "/api/projects/${seed.projectId}/issues/$issue")
        assertThat(original["detail"]["actual"].asText()).isEqualTo("안 보인다")
        request(owner.token, "PUT", "$path/$id/subscription", mapOf("enabled" to false))
        assertThat(request(owner.token, "GET", "$path?subscribed=true")["items"]).isEmpty()
    }

    @Test
    fun `재전송은 중복 저장하지 않고 다른 payload는 충돌로 처리한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        val payload = draft()
        val first = request(owner.token, "POST", path, payload)
        val second = request(owner.token, "POST", path, payload)
        assertThat(first["id"]).isEqualTo(second["id"])
        assertThat(
                statusOf { request(owner.token, "POST", path, payload + mapOf("title" to "다른 내용")) }
            )
            .isEqualTo(409)
        val id = first["id"].asText()
        val comment = mapOf("body" to "댓글", "clientRequestId" to UUID.randomUUID().toString())
        val commentId = request(owner.token, "POST", "$path/$id/comments", comment)["id"]
        assertThat(request(owner.token, "POST", "$path/$id/comments", comment)["id"])
            .isEqualTo(commentId)
        assertThat(
                statusOf {
                    request(
                        owner.token,
                        "POST",
                        "$path/$id/comments",
                        comment + mapOf("body" to "다름"),
                    )
                }
            )
            .isEqualTo(409)
        assertThat(request(owner.token, "GET", "$path/$id/comments")["items"]).hasSize(1)
        repeat(2) {
            request(
                owner.token,
                "PUT",
                "$path/$id/comments/${commentId.asText()}/reaction",
                mapOf("enabled" to true),
            )
        }
        assertThat(
                request(owner.token, "GET", "$path/$id/comments")["items"][0]["reactionCount"]
                    .asInt()
            )
            .isEqualTo(1)
    }

    @Test
    fun `동시 생성은 한 게시글만 저장한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        val draft =
            DiscussionDraft(
                "동시 요청",
                "같은 본문",
                "SHARE",
                clientRequestId = UUID.randomUUID().toString(),
            )
        val ids = coroutineScope {
            (1..3)
                .map { async { discussions.create(seed.projectId, owner.userId, draft).id } }
                .map { it.await() }
        }
        assertThat(ids.toSet()).hasSize(1)
        assertThat(
                discussions
                    .list(seed.projectId, owner.userId, null, false, false, null, null, null, 30)
                    .items
            )
            .hasSize(1)
    }

    @Test
    fun `프로젝트 경계와 작성자 권한 및 삭제된 프로젝트를 검증한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val member = signIn("43", "member")
        val stranger = signIn("44", "stranger")
        val seed = seed(owner.userId)
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = seed.projectId,
                appUserId = member.userId,
                role = "MEMBER",
                createdAt = Instant.now(),
            )
        )
        val foreign = seed(stranger.userId)
        val foreignIssue = seedIssue(foreign.qaTryId, "MAJOR", "다른 프로젝트")
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        assertThat(statusOf { request(owner.token, "POST", path, draft(foreignIssue)) })
            .isEqualTo(404)
        assertThat(
                statusOf {
                    request(
                        owner.token,
                        "POST",
                        path,
                        draft() + mapOf("mentionedUserIds" to listOf(stranger.userId.toString())),
                    )
                }
            )
            .isEqualTo(404)
        assertThat(request(owner.token, "GET", path)["items"]).isEmpty()
        val id = request(owner.token, "POST", path, draft())["id"].asText()
        assertThat(statusOf { request(stranger.token, "GET", "$path/$id") }).isEqualTo(404)
        assertThat(
                statusOf {
                    request(member.token, "PUT", "$path/$id/state", mapOf("state" to "CLOSED"))
                }
            )
            .isEqualTo(403)
        assertThat(
                statusOf { request(member.token, "PUT", "$path/$id/pin", mapOf("enabled" to true)) }
            )
            .isEqualTo(403)
        assertThat(
                statusOf {
                    request(
                        owner.token,
                        "GET",
                        "/api/projects/${seed.projectId}/issues/$foreignIssue",
                    )
                }
            )
            .isEqualTo(404)
        val membership =
            projectMemberRepository.findByProjectIdAndAppUserId(seed.projectId, member.userId)!!
        projectMemberRepository.delete(membership)
        assertThat(
                statusOf {
                    request(
                        member.token,
                        "POST",
                        "$path/$id/comments",
                        mapOf(
                            "body" to "권한 회수 후",
                            "clientRequestId" to UUID.randomUUID().toString(),
                        ),
                    )
                }
            )
            .isEqualTo(404)
        val project = projectRepository.findById(seed.projectId)!!
        projectRepository.save(project.copy(deletedAt = Instant.now()))
        assertThat(statusOf { request(owner.token, "GET", path) }).isEqualTo(404)
    }

    @Test
    fun `본문 version과 명시적 상태 변경이 서로 덮어쓰지 않는다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        val payload = draft()
        val id = request(owner.token, "POST", path, payload)["id"].asText()
        request(owner.token, "PUT", "$path/$id/state", mapOf("state" to "CLOSED"))
        val edited =
            request(
                owner.token,
                "PATCH",
                "$path/$id",
                payload + mapOf("title" to "수정", "version" to 0),
            )
        assertThat(edited["version"].asInt()).isEqualTo(1)
        assertThat(edited["state"].asText()).isEqualTo("CLOSED")
        assertThat(
                statusOf {
                    request(owner.token, "PATCH", "$path/$id", payload + mapOf("version" to 0))
                }
            )
            .isEqualTo(409)
        request(owner.token, "POST", path, payload)
        assertThat(request(owner.token, "GET", "$path/$id")["title"].asText()).isEqualTo("수정")
        val other = request(owner.token, "POST", path, draft())["id"].asText()
        val comment =
            request(
                owner.token,
                "POST",
                "$path/$other/comments",
                mapOf("body" to "다른 글", "clientRequestId" to UUID.randomUUID().toString()),
            )
        assertThat(
                statusOf {
                    request(
                        owner.token,
                        "PUT",
                        "$path/$id/decision",
                        mapOf("commentId" to comment["id"].asText()),
                    )
                }
            )
            .isEqualTo(404)
    }

    @Test
    fun `페이지와 검색을 제한하고 cursor를 검증한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        val ids =
            (1..3).map {
                request(owner.token, "POST", path, draft() + mapOf("title" to "검색 $it"))["id"]
                    .asText()
            }
        request(owner.token, "PUT", "$path/${ids.first()}/pin", mapOf("enabled" to true))
        val page = request(owner.token, "GET", "$path?size=1")
        assertThat(page["items"][0]["id"].asText()).isEqualTo(ids.first())
        val next = request(owner.token, "GET", "$path?size=1&cursor=${page["nextCursor"].asText()}")
        assertThat(next["items"][0]["id"].asText()).isEqualTo(ids.last())
        assertThat(request(owner.token, "GET", "$path?query=검색%202")["items"]).hasSize(1)
        for (suffix in listOf("size=0", "size=101", "cursor=bad", "state=BAD")) {
            assertThat(statusOf { request(owner.token, "GET", "$path?$suffix") }).isEqualTo(400)
        }
        assertThat(statusOf { request(owner.token, "POST", path, draft() + mapOf("title" to " ")) })
            .isEqualTo(400)
        repeat(3) {
            request(
                owner.token,
                "POST",
                "$path/${ids.first()}/comments",
                mapOf("body" to "댓글 $it", "clientRequestId" to UUID.randomUUID().toString()),
            )
        }
        val comments = request(owner.token, "GET", "$path/${ids.first()}/comments?size=2")
        assertThat(comments["items"]).hasSize(2)
        assertThat(
                request(
                    owner.token,
                    "GET",
                    "$path/${ids.first()}/comments?cursor=${comments["nextCursor"].asText()}",
                )["items"]
            )
            .hasSize(1)
    }

    @Test
    fun `댓글 편집 권한과 version 및 최근 활동 시각을 검증한다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val member = signIn("43", "member")
        val seed = seed(owner.userId)
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = seed.projectId,
                appUserId = member.userId,
                role = "MEMBER",
                createdAt = Instant.now(),
            )
        )
        val path = "/api/projects/${seed.projectId}/issue-discussions"
        val post = request(owner.token, "POST", path, draft())
        val id = post["id"].asText()
        val comment =
            request(
                member.token,
                "POST",
                "$path/$id/comments",
                mapOf("body" to "첫 댓글", "clientRequestId" to UUID.randomUUID().toString()),
            )
        val commentPath = "$path/$id/comments/${comment["id"].asText()}"
        val before = request(owner.token, "GET", "$path/$id")["updatedAt"].asText()
        assertThat(
                statusOf {
                    request(
                        owner.token,
                        "PATCH",
                        commentPath,
                        mapOf("body" to "남의 댓글", "version" to 0),
                    )
                }
            )
            .isEqualTo(403)
        val edited =
            request(member.token, "PATCH", commentPath, mapOf("body" to "수정 댓글", "version" to 0))
        assertThat(edited["body"].asText()).isEqualTo("수정 댓글")
        assertThat(edited["version"].asInt()).isEqualTo(1)
        assertThat(
                statusOf {
                    request(
                        member.token,
                        "PATCH",
                        commentPath,
                        mapOf("body" to "오래된 수정", "version" to 0),
                    )
                }
            )
            .isEqualTo(409)
        assertThat(Instant.parse(request(owner.token, "GET", "$path/$id")["updatedAt"].asText()))
            .isAfterOrEqualTo(Instant.parse(before))
    }

    @Test
    fun `이슈 선택기는 본문 없이 검색하고 페이지를 나눈다`(): Unit = runBlocking {
        val owner = signIn("42", "owner")
        val seed = seed(owner.userId)
        seedIssue(seed.qaTryId, "MAJOR", "카드 조합")
        val second = seedIssue(seed.qaTryId, "MAJOR", "카드 드래그")
        val path = "/api/projects/${seed.projectId}/issue-discussions/issue-options"
        val page = request(owner.token, "GET", "$path?size=1")
        assertThat(page["items"][0]["id"].asText()).isEqualTo(second.toString())
        assertThat(page["items"][0].has("detail")).isFalse()
        assertThat(page["hasMore"].asBoolean()).isTrue()
        assertThat(
                request(owner.token, "GET", "$path?cursor=${page["nextCursor"].asText()}")["items"]
            )
            .hasSize(1)
        assertThat(request(owner.token, "GET", "$path?query=드래그")["items"]).hasSize(1)
        val stranger = signIn("43", "stranger")
        assertThat(statusOf { request(stranger.token, "GET", path) }).isEqualTo(404)
    }

    private fun draft(issueId: Long? = null): Map<String, Any> =
        mapOf(
            "title" to "보상 문제 공유",
            "body" to "재현 조건을 함께 확인해 주세요.",
            "purpose" to "SHARE",
            "issueIds" to listOfNotNull(issueId?.toString()),
            "clientRequestId" to UUID.randomUUID().toString(),
        )

    private fun request(token: String, method: String, path: String, body: Any? = null) =
        objectMapper.readTree(
            WebClient.create("http://localhost:$port")
                .method(HttpMethod.valueOf(method))
                .uri(
                    java.net.URI.create(
                        java.net.URI.create("http://localhost:$port$path").toASCIIString()
                    )
                )
                .cookie("artel_access_token", token)
                .let { if (body == null) it else it.bodyValue(body) }
                .retrieve()
                .bodyToMono(String::class.java)
                .block()
        )

    private fun statusOf(call: () -> Any?): Int =
        try {
            call()
            200
        } catch (error: WebClientResponseException) {
            error.statusCode.value()
        }

    private data class Signed(val userId: Long, val token: String)

    private data class Seed(val projectId: Long, val qaTryId: Long)

    private suspend fun signIn(providerUserId: String, login: String): Signed {
        val user =
            oauthUserService.upsert(
                OAuthIdentity(
                    provider = "github",
                    providerUserId = providerUserId,
                    login = login,
                    displayName = login,
                    avatarUrl = null,
                    email = "$login@example.com",
                )
            )
        return Signed(user.userId.toLong(), jwtService.issue(user))
    }

    private suspend fun seed(ownerId: Long): Seed {
        val now = Instant.now()
        val project =
            projectRepository.save(
                ProjectEntity(
                    name = "issue-http",
                    genre = "ACTION",
                    createdAt = now,
                    updatedAt = now,
                )
            )!!
        projectMemberRepository.save(
            ProjectMemberEntity(
                projectId = project.id!!,
                appUserId = ownerId,
                role = ProjectRole.OWNER.name,
                createdAt = now,
            )
        )
        val scenario = testScenarioRepository.save(TestScenarioEntity(projectId = project.id!!))!!
        val instance =
            gameInstanceRepository.save(
                GameInstanceEntity(
                    projectId = project.id!!,
                    name = "instance",
                    platform = "UNITY",
                    sdkUuid = UUID.randomUUID().toString(),
                    createdAt = now,
                    updatedAt = now,
                )
            )!!
        val qaTry =
            qaTryRepository.save(
                QaTryEntity(
                    testScenarioId = scenario.id!!,
                    gameInstanceId = instance.id!!,
                    startedBy = ownerId,
                    status = "COMPLETED",
                    startedAt = now,
                    completedAt = now,
                )
            )!!
        return Seed(project.id!!, qaTry.id!!)
    }

    private suspend fun seedIssue(qaTryId: Long, severity: String, title: String): Long =
        issueRepository
            .save(
                IssueEntity(
                    qaTryId = qaTryId,
                    messageId = UUID.randomUUID().toString(),
                    severity = severity,
                    title = title,
                    detail = Json.of("""{"expected":"보인다","actual":"안 보인다"}"""),
                    reportedAt = Instant.parse("2026-07-28T12:34:56.123456Z"),
                    createdAt = Instant.now(),
                    updatedAt = Instant.now(),
                )
            )!!
            .id!!
}
