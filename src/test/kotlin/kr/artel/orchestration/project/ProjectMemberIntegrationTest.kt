package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectMemberEntity
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
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectMemberIntegrationTest {

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

    /** 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        invitationRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `lists the owner first, then members in join order`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        // 나중에 들어온 사람을 먼저 만들어 둔다. 목록이 참여 순서를 따르는지, 만든 순서를 따르는지
        // 가르려면 둘이 어긋나 있어야 한다.
        val laterMemberId = joinAs("11", "zebra", projectId, ProjectRole.MEMBER, joinedAt = 20)
        val earlierMemberId = joinAs("99", "hubot", projectId, ProjectRole.MEMBER, joinedAt = 10)

        val members = get(ownerToken, "/api/projects/$projectId/members")

        assertThat(members).hasSize(3)
        assertThat(members[0]["role"].asText()).isEqualTo("OWNER")
        assertThat(members[0]["displayName"].asText()).isEqualTo("octocat")
        assertThat(members[0]["email"].asText()).isEqualTo("octocat@example.com")
        assertThat(members[1]["userId"].asText()).isEqualTo(earlierMemberId.toString())
        assertThat(members[2]["userId"].asText()).isEqualTo(laterMemberId.toString())
    }

    @Test
    fun `shows a null email rather than hiding the member`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signInWithoutEmail("55", "ghost")
        joinAs("55", "ghost", projectId, ProjectRole.MEMBER)

        val members = get(ownerToken, "/api/projects/$projectId/members")

        assertThat(members).hasSize(2)
        assertThat(members[1]["displayName"].asText()).isEqualTo("ghost")
        assertThat(members[1]["email"].isNull).isTrue()
    }

    @Test
    fun `lets a plain member read the list too`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        joinAs("99", "hubot", projectId, ProjectRole.MEMBER)

        val members = get(memberToken, "/api/projects/$projectId/members")

        assertThat(members).hasSize(2)
    }

    @Test
    fun `hides the member list from someone who is not in the project`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val strangerToken = signIn("77", "stranger")

        val status = statusOf { get(strangerToken, "/api/projects/$projectId/members") }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `hides the member list once the project is soft-deleted`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        deleteProject(ownerToken, projectId)

        // 멤버 행은 남아 있다. 그래도 삭제된 프로젝트는 없는 것으로 보여야 한다.
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, userIdOf("42"))).isNotNull()
        assertThat(statusOf { get(ownerToken, "/api/projects/$projectId/members") })
            .isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `removes a member when the owner asks`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberId = joinAs("99", "hubot", projectId, ProjectRole.MEMBER)

        delete(ownerToken, "/api/projects/$projectId/members/$memberId")

        assertThat(get(ownerToken, "/api/projects/$projectId/members")).hasSize(1)
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, memberId)).isNull()
    }

    @Test
    fun `refuses removal by a member who is not an owner`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        joinAs("99", "hubot", projectId, ProjectRole.MEMBER)
        val ownerId = userIdOf("42")

        val status = statusOf { delete(memberToken, "/api/projects/$projectId/members/$ownerId") }

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `refuses to remove the last owner`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val ownerId = userIdOf("42")

        val error = errorOf { delete(ownerToken, "/api/projects/$projectId/members/$ownerId") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(objectMapper.readTree(error.responseBodyAsString)["code"].asText())
            .isEqualTo("last_owner")
        assertThat(memberRepository.findByProjectIdAndAppUserId(projectId, ownerId)).isNotNull()
    }

    @Test
    fun `removes an owner once a second owner exists`(): Unit = runBlocking {
        val firstToken = signIn("42", "octocat")
        val projectId = createProject(firstToken)
        val secondOwnerId = joinAs("99", "hubot", projectId, ProjectRole.OWNER)

        delete(firstToken, "/api/projects/$projectId/members/$secondOwnerId")

        assertThat(get(firstToken, "/api/projects/$projectId/members")).hasSize(1)
    }

    @Test
    fun `reports a stranger as a missing member, not as a missing project`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("77", "stranger")
        val outsiderId = userIdOf("77")

        val error = errorOf { delete(ownerToken, "/api/projects/$projectId/members/$outsiderId") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        // 프로젝트가 아니라 멤버가 없다는 답이어야 한다. 상태만 보면 두 갈래를 가르지 못한다.
        assertThat(objectMapper.readTree(error.responseBodyAsString)["message"].asText())
            .isEqualTo("프로젝트 멤버를 찾을 수 없습니다.")
    }

    private suspend fun signIn(providerUserId: String, login: String): String =
        signInWith(providerUserId, login, "$login@example.com")

    /** GitHub에서 공개 이메일을 받지 못한 계정. */
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

    /** 초대 흐름을 거치지 않고 참여 행을 직접 만든다. 여기서 검증할 것은 멤버 API 쪽이다. */
    private suspend fun joinAs(
        providerUserId: String,
        login: String,
        projectId: Long,
        role: ProjectRole,
        joinedAt: Long = 0
    ): Long {
        val appUserId = identityRepository
            .findByProviderAndProviderUserId("github", providerUserId)
            ?.appUserId
            ?: run {
                signIn(providerUserId, login)
                userIdOf(providerUserId)
            }
        memberRepository.save(
            ProjectMemberEntity(
                projectId = projectId,
                appUserId = appUserId,
                role = role.name,
                createdAt = Instant.now(clock).plusSeconds(joinedAt)
            )
        )
        return appUserId
    }

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

    private fun deleteProject(token: String, projectId: Long) {
        client().delete().uri("/api/projects/$projectId").cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun delete(token: String, uri: String) {
        client().delete().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    }

    private fun client() = WebClient.create("http://localhost:$port")

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
