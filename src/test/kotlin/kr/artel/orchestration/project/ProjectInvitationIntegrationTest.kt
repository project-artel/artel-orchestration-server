package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectInvitationEntity
import kr.artel.orchestration.project.entity.ProjectInvitationStatus
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectInvitationRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Clock
import java.time.Duration
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectInvitationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var invitationRepository: ProjectInvitationRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var clock: Clock

    @BeforeEach
    fun clean(): Unit = runBlocking {
        invitationRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `invites an address that has no ARTEL account yet`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        val invitation = post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"email":"nobody@example.com","role":"MEMBER"}"""
        )

        assertThat(invitation["email"].asText()).isEqualTo("nobody@example.com")
        assertThat(invitation["role"].asText()).isEqualTo("MEMBER")
        assertThat(invitation["status"].asText()).isEqualTo("PENDING")
        assertThat(invitation["projectName"].asText()).isEqualTo("Demo Day")
        assertThat(invitation["invitedBy"].asText()).isEqualTo("octocat")
        assertThat(appUserRepository.findByEmailIgnoreCase("nobody@example.com").toList()).isEmpty()
    }

    @Test
    fun `stores the address in lower case and rejects a differently cased duplicate`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        val created = post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"email":"Hubot@Example.com","role":"MEMBER"}"""
        )
        assertThat(created["email"].asText()).isEqualTo("hubot@example.com")

        val error = errorOf {
            post(
                ownerToken,
                "/api/projects/$projectId/invitations",
                """{"email":"HUBOT@example.COM","role":"MEMBER"}"""
            )
        }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(codeOf(error)).isEqualTo("duplicate_invitation")
    }

    @Test
    fun `rejects an address that already belongs to a member`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        val error = errorOf {
            post(
                ownerToken,
                "/api/projects/$projectId/invitations",
                """{"email":"octocat@example.com","role":"MEMBER"}"""
            )
        }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(codeOf(error)).isEqualTo("already_member")
    }

    @Test
    fun `refuses an invitation sent by a member who is not an owner`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        acceptInvitationFor(ownerToken, projectId, "hubot@example.com", memberToken)

        val status = statusOf {
            post(
                memberToken,
                "/api/projects/$projectId/invitations",
                """{"email":"third@example.com","role":"MEMBER"}"""
            )
        }

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `shows an invitation to the address it was sent to, and makes them a member on accept`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)

        val inviteeToken = signIn("99", "hubot")
        val inbox = get(inviteeToken, "/api/invitations")
        assertThat(inbox).hasSize(1)
        assertThat(inbox[0]["projectName"].asText()).isEqualTo("Demo Day")
        assertThat(inbox[0]["invitedBy"].asText()).isEqualTo("octocat")

        // 수락 전에는 프로젝트가 보이지 않는다.
        assertThat(get(inviteeToken, "/api/projects")["total"].asLong()).isZero()

        val invitationId = inbox[0]["id"].asText()
        val accepted = trigger(inviteeToken, "/api/invitations/$invitationId/accept")
        assertThat(accepted["status"].asText()).isEqualTo("ACCEPTED")

        assertThat(get(inviteeToken, "/api/projects")["total"].asLong()).isEqualTo(1)
        assertThat(get(inviteeToken, "/api/invitations")).isEmpty()
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))?.role)
            .isEqualTo("MEMBER")
    }

    @Test
    fun `carries the invited role onto the membership`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        invite(ownerToken, projectId, "hubot@example.com", ProjectRole.OWNER)
        val inviteeToken = signIn("99", "hubot")

        acceptFirstInvitation(inviteeToken)

        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))?.role)
            .isEqualTo("OWNER")
    }

    /**
     * 초대 생성의 "이미 멤버" 확인은 `app_user.email` 이 unique 가 아니라 최선을 다하는 것일 뿐이다.
     * 그것을 지나온 초대가 실제 방어선을 때린다 — 여기서 멤버 행을 한 번 더 넣으면
     * `uk_project_member_project_user` 에 걸려 500 이 난다.
     *
     * 그 상태를 만들려고 `PENDING` 행을 직접 넣는다. API 로는 409 에 막혀 도달할 수 없다.
     */
    @Test
    fun `accepts idempotently when the account is already a member`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        acceptInvitationFor(ownerToken, projectId, "hubot@example.com", memberToken)

        val second = pendingInvitationRow(projectId, "hubot@example.com", ProjectRole.MEMBER)
        val accepted = trigger(memberToken, "/api/invitations/$second/accept")

        assertThat(accepted["status"].asText()).isEqualTo("ACCEPTED")
        val inviteeId = userIdOf("99")
        assertThat(memberRepository.findByProjectId(projectId).toList())
            .filteredOn { it.appUserId == inviteeId }
            .hasSize(1)
    }

    /**
     * 역할 변경은 이 스토리의 범위 밖이다. 이미 MEMBER 인 사람을 OWNER 로 초대해 수락하게 해도
     * 역할이 오르지 않아야 하고, 응답도 실제로 갖게 된 역할을 말해야 한다. 초대에 적힌 역할을
     * 그대로 실으면 응답이 멤버십과 다른 말을 한다.
     */
    @Test
    fun `does not promote an existing member, and says so in the response`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        acceptInvitationFor(ownerToken, projectId, "hubot@example.com", memberToken)

        val promotion = pendingInvitationRow(projectId, "hubot@example.com", ProjectRole.OWNER)
        val accepted = trigger(memberToken, "/api/invitations/$promotion/accept")

        assertThat(accepted["role"].asText()).isEqualTo("MEMBER")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))?.role)
            .isEqualTo("MEMBER")
    }

    @Test
    fun `refuses acceptance by an account whose email does not match`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val strangerToken = signIn("77", "stranger")

        val error = errorOf { trigger(strangerToken, "/api/invitations/$invitationId/accept") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.FORBIDDEN)
        assertThat(codeOf(error)).isEqualTo("invitation_not_yours")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("77"))).isNull()
    }

    @Test
    fun `gives an account without an email an empty inbox rather than someone else's`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val namelessToken = signInWithoutEmail("55", "ghost")

        assertThat(get(namelessToken, "/api/invitations")).isEmpty()
        assertThat(statusOf { trigger(namelessToken, "/api/invitations/$invitationId/accept") })
            .isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `declines without creating a membership`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val inviteeToken = signIn("99", "hubot")

        val declined = trigger(inviteeToken, "/api/invitations/$invitationId/decline")

        assertThat(declined["status"].asText()).isEqualTo("DECLINED")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))).isNull()
        assertThat(get(inviteeToken, "/api/invitations")).isEmpty()
        assertThat(requireNotNull(invitationRepository.findById(invitationId)).respondedAt).isNotNull()
    }

    @Test
    fun `refuses a second answer to an invitation that is already settled`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val inviteeToken = signIn("99", "hubot")
        trigger(inviteeToken, "/api/invitations/$invitationId/decline")

        val error = errorOf { trigger(inviteeToken, "/api/invitations/$invitationId/accept") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(codeOf(error)).isEqualTo("invitation_already_settled")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))).isNull()
    }

    @Test
    fun `lets the owner invite the same address again after a decline`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val first = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val inviteeToken = signIn("99", "hubot")
        trigger(inviteeToken, "/api/invitations/$first/decline")

        val second = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)

        assertThat(second).isNotEqualTo(first)
        assertThat(get(inviteeToken, "/api/invitations")).hasSize(1)
    }

    @Test
    fun `revokes an invitation so it leaves the invitee's inbox`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val inviteeToken = signIn("99", "hubot")
        assertThat(get(inviteeToken, "/api/invitations")).hasSize(1)

        delete(ownerToken, "/api/projects/$projectId/invitations/$invitationId")

        assertThat(get(inviteeToken, "/api/invitations")).isEmpty()
        assertThat(get(ownerToken, "/api/projects/$projectId/invitations")).isEmpty()
        assertThat(requireNotNull(invitationRepository.findById(invitationId)).status)
            .isEqualTo(ProjectInvitationStatus.REVOKED.name)
        assertThat(statusOf { trigger(inviteeToken, "/api/invitations/$invitationId/accept") })
            .isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `hides an invitation belonging to another project behind a 404`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val firstProject = createProject(ownerToken)
        val secondProject = createProject(ownerToken)
        val invitationId = invite(ownerToken, firstProject, "hubot@example.com", ProjectRole.MEMBER)

        val status = statusOf {
            delete(ownerToken, "/api/projects/$secondProject/invitations/$invitationId")
        }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `treats an expired invitation as gone, and refuses to accept it`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val inviteeToken = signIn("99", "hubot")
        val expired = expiredInvitation(projectId, "hubot@example.com")

        assertThat(get(inviteeToken, "/api/invitations")).isEmpty()
        assertThat(get(ownerToken, "/api/projects/$projectId/invitations")).isEmpty()

        val error = errorOf { trigger(inviteeToken, "/api/invitations/$expired/accept") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(codeOf(error)).isEqualTo("invitation_expired")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("99"))).isNull()
    }

    @Test
    fun `keeps a pending invitation off the inbox once the project is soft-deleted`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val invitationId = invite(ownerToken, projectId, "hubot@example.com", ProjectRole.MEMBER)
        val inviteeToken = signIn("99", "hubot")

        client().delete().uri("/api/projects/$projectId").cookie("artel_access_token", ownerToken)
            .retrieve().bodyToMono(String::class.java).block()

        // 행은 남는다. 프로젝트를 되살릴 수 있어야 하므로 초대까지 거두지는 않는다.
        assertThat(requireNotNull(invitationRepository.findById(invitationId)).status)
            .isEqualTo(ProjectInvitationStatus.PENDING.name)
        assertThat(get(inviteeToken, "/api/invitations")).isEmpty()
        assertThat(statusOf { trigger(inviteeToken, "/api/invitations/$invitationId/accept") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `hides the sent-invitation list from a member who is not an owner`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        acceptInvitationFor(ownerToken, projectId, "hubot@example.com", memberToken)

        val status = statusOf { get(memberToken, "/api/projects/$projectId/invitations") }

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `rejects a malformed address before it reaches the database`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        val error = errorOf {
            post(
                ownerToken,
                "/api/projects/$projectId/invitations",
                """{"email":"not-an-address","role":"MEMBER"}"""
            )
        }

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = objectMapper.readTree(error.responseBodyAsString)
        assertThat(body["code"].asText()).isEqualTo("invalid_request")
        assertThat(body["fields"].has("email")).isTrue()
    }

    private suspend fun signIn(providerUserId: String, login: String): String =
        signInWith(providerUserId, login, "$login@example.com")

    /** GitHub에서 공개 이메일을 받지 못한 계정. 어떤 초대의 수신자도 될 수 없다. */
    private suspend fun signInWithoutEmail(providerUserId: String, login: String): String =
        signInWith(providerUserId, login, email = null)

    private suspend fun signInWith(providerUserId: String, login: String, email: String?): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = email
            )
        )
        return jwtService.issue(user)
    }

    private fun invite(
        ownerToken: String,
        projectId: Long,
        email: String,
        role: ProjectRole
    ): Long =
        post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"email":"$email","role":"${role.name}"}"""
        )["id"].asText().toLong()

    private fun acceptFirstInvitation(inviteeToken: String) {
        val invitationId = get(inviteeToken, "/api/invitations")[0]["id"].asText()
        trigger(inviteeToken, "/api/invitations/$invitationId/accept")
    }

    private fun acceptInvitationFor(
        ownerToken: String,
        projectId: Long,
        email: String,
        inviteeToken: String
    ) {
        invite(ownerToken, projectId, email, ProjectRole.MEMBER)
        acceptFirstInvitation(inviteeToken)
    }

    /** 시계를 흔들지 않고 만료 상태를 만든다. expires_at 이 과거인 행을 직접 넣는다. */
    private suspend fun expiredInvitation(projectId: Long, email: String): Long {
        val now = Instant.now(clock)
        return invitationRow(
            projectId,
            email,
            ProjectRole.MEMBER,
            createdAt = now.minus(Duration.ofDays(30)),
            expiresAt = now.minus(Duration.ofDays(16))
        )
    }

    /** API 로는 409 에 막혀 못 만드는 `PENDING` 행을 직접 넣는다. */
    private suspend fun pendingInvitationRow(
        projectId: Long,
        email: String,
        role: ProjectRole
    ): Long {
        val now = Instant.now(clock)
        return invitationRow(projectId, email, role, now, now.plus(Duration.ofDays(14)))
    }

    private suspend fun invitationRow(
        projectId: Long,
        email: String,
        role: ProjectRole,
        createdAt: Instant,
        expiresAt: Instant
    ): Long = requireNotNull(
        invitationRepository.save(
            ProjectInvitationEntity(
                projectId = projectId,
                email = email,
                role = role.name,
                status = ProjectInvitationStatus.PENDING.name,
                invitedBy = userIdOf("42"),
                createdAt = createdAt,
                expiresAt = expiresAt
            )
        ).id
    )

    private suspend fun userIdOf(providerUserId: String): Long =
        requireNotNull(identityRepository.findByProviderAndProviderUserId("github", providerUserId))
            .appUserId

    private fun createProject(token: String): Long =
        objectMapper.readTree(
            client().post().uri("/api/projects").cookie("artel_access_token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"name":"Demo Day","genre":"ACTION"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["id"].asText().toLong()

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    /** 본문이 없는 POST. accept 와 decline 은 경로만으로 무엇을 할지가 정해진다. */
    private fun trigger(token: String, uri: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun delete(token: String, uri: String) {
        client().delete().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    }

    private fun client() = WebClient.create("http://localhost:$port")

    private fun codeOf(error: WebClientResponseException): String =
        objectMapper.readTree(error.responseBodyAsString)["code"].asText()

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
