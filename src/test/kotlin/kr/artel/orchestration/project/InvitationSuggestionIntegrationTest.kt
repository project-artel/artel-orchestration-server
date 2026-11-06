package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectInvitationEntity
import kr.artel.orchestration.project.entity.ProjectInvitationStatus
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
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * `GET /api/projects/{projectId}/invitation-suggestions` 와, 그 결과를 그대로 초대로 잇는
 * `appUserId` 경로.
 *
 * 이메일 경로의 초대 규칙은 [ProjectInvitationIntegrationTest] 가 덮는다. 여기는 후보를 고르는
 * 규칙 — 권한, 제외 셋, `nickname#user_tag` 정확 일치, 이메일 전체 일치, 정렬, 개수 — 만 본다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class InvitationSuggestionIntegrationTest {

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

    @BeforeEach
    fun clean(): Unit = runBlocking {
        invitationRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `hides the suggestions from someone who is not in the project`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val outsiderToken = signIn("77", "stranger")

        val status = statusOf { suggest(outsiderToken, projectId, "octo") }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `refuses the suggestions to a member who is not the owner`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        val memberToken = signIn("99", "hubot")
        makeMember(ownerToken, projectId, "hubot@example.com", memberToken)

        val status = statusOf { suggest(memberToken, projectId, "hubot") }

        assertThat(status).isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `carries the handle and the identity but never the address`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot", avatarUrl = "https://avatars.example.com/hubot.png")
        val hubotId = renameTo("60", nickname = "Yuni", userTag = "0042")

        val suggestions = suggest(ownerToken, projectId, "yun")

        assertThat(suggestions).hasSize(1)
        val only = suggestions[0]
        assertThat(only["appUserId"].asText()).isEqualTo(hubotId.toString())
        assertThat(only["nickname"].asText()).isEqualTo("Yuni")
        assertThat(only["userTag"].asText()).isEqualTo("0042")
        assertThat(only["displayName"].asText()).isEqualTo("hubot")
        assertThat(only["login"].asText()).isEqualTo("hubot")
        assertThat(only["avatarUrl"].asText()).isEqualTo("https://avatars.example.com/hubot.png")
        assertThat(only.fieldNames().asSequence().toList()).doesNotContain("email")
    }

    @Test
    fun `does no work for a query shorter than two characters`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")

        assertThat(suggest(ownerToken, projectId, "h")).isEmpty()
        assertThat(suggest(ownerToken, projectId, " h ")).isEmpty()
        assertThat(suggest(ownerToken, projectId, "hu")).hasSize(1)
    }

    @Test
    fun `finds by nickname, by login, and by display name, ignoring case`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        signIn("60", "someone", displayName = "someone")
        renameTo("60", nickname = "Wanderer", userTag = "0001")
        signIn("61", "wandering-hubot", displayName = "wandering-hubot")
        renameTo("61", nickname = "Other", userTag = "0001")
        signIn("62", "third", displayName = "Wanda Maximoff")
        renameTo("62", nickname = "Third", userTag = "0001")

        val nicknames = suggest(ownerToken, projectId, "WAND").map { it["nickname"].asText() }

        assertThat(nicknames).containsExactlyInAnyOrder("Wanderer", "Other", "Third")
    }

    @Test
    fun `matches a nickname and user tag pair exactly, and does not prefix search it`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "first")
        val wanted = renameTo("60", nickname = "Yuni", userTag = "0042")
        signIn("61", "second")
        renameTo("61", nickname = "Yuni", userTag = "0043")

        val exact = suggest(ownerToken, projectId, "Yuni#0042")
        assertThat(exact.map { it["appUserId"].asText() }).containsExactly(wanted.toString())

        // `#` 이 있으면 접두사 검색을 하지 않는다. 반쯤 적은 user_tag 로는 아무도 나오면 안 된다.
        assertThat(suggest(ownerToken, projectId, "Yuni#00")).isEmpty()
        // 이름만으로는 둘 다 나온다 — 짝을 맞춘 것이 하나를 고른 것이 맞는지 보기 위해서다.
        assertThat(suggest(ownerToken, projectId, "Yuni")).hasSize(2)
    }

    @Test
    fun `matches a full address but never a prefix of one`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        val full = suggest(ownerToken, projectId, "HUBOT@example.com")
        assertThat(full.map { it["appUserId"].asText() }).containsExactly(hubotId.toString())

        // 주소를 글자씩 늘려 가며 남의 주소를 훑을 수 없어야 한다.
        assertThat(suggest(ownerToken, projectId, "hubot@ex")).isEmpty()
        assertThat(suggest(ownerToken, projectId, "hubot@example.co")).isEmpty()
    }

    @Test
    fun `puts an exact match ahead of the prefix matches`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        signIn("60", "adalind", displayName = "adalind")
        renameTo("60", nickname = "Adalind", userTag = "0001")
        signIn("61", "adamant", displayName = "adamant")
        renameTo("61", nickname = "Adamant", userTag = "0001")
        signIn("62", "ada", displayName = "ada")
        renameTo("62", nickname = "Ada", userTag = "0001")

        val nicknames = suggest(ownerToken, projectId, "ada").map { it["nickname"].asText() }

        assertThat(nicknames).containsExactly("Ada", "Adalind", "Adamant")
    }

    @Test
    fun `leaves out members and people already invited`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)

        val memberToken = signIn("60", "cand-member")
        renameTo("60", nickname = "CandMember", userTag = "0001")
        makeMember(ownerToken, projectId, "cand-member@example.com", memberToken)

        signIn("61", "cand-invited")
        renameTo("61", nickname = "CandInvited", userTag = "0001")
        invite(ownerToken, projectId, "cand-invited@example.com")

        signIn("63", "cand-open")
        val openId = renameTo("63", nickname = "CandOpen", userTag = "0001")

        val suggestions = suggest(ownerToken, projectId, "cand")

        assertThat(suggestions.map { it["appUserId"].asText() }).containsExactly(openId.toString())
    }

    // ARTEL-774: 이메일이 없거나 확인 전이라는 이유만으로는 더 이상 후보에서 빠지지 않는다.
    // WHERE 에서 u.email IS NOT NULL AND u.email_verified_at IS NOT NULL 을 뺀 것을 검증한다.

    @Test
    fun `finds a candidate who has no email address at all`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signInWithoutEmail("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        val suggestions = suggest(ownerToken, projectId, "zeph")

        assertThat(suggestions.map { it["appUserId"].asText() }).containsExactly(hubotId.toString())
    }

    @Test
    fun `finds a candidate whose address is not verified yet`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")
        claimWithoutVerifying("60", "zephyr@example.com")

        val suggestions = suggest(ownerToken, projectId, "zeph")

        assertThat(suggestions.map { it["appUserId"].asText() }).containsExactly(hubotId.toString())
    }

    @Test
    fun `never matches an unverified address by its full text`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")
        renameTo("60", nickname = "Zephyr", userTag = "0001")
        claimWithoutVerifying("60", "zephyr@example.com")

        assertThat(suggest(ownerToken, projectId, "zephyr@example.com")).isEmpty()
    }

    @Test
    fun `excludes a candidate who already has a pending invitation addressed to their account`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signInWithoutEmail("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")
        // 이메일이 없어 email 경로로는 부를 수 없는 계정이다. 대기 중 초대를 app_user_id 로 직접
        // 넣어, 이름 대신 계정을 가리키는 초대도 후보에서 빼는지를 본다 — 검색 단만 확인하는
        // 것이라 `ProjectInvitationRepository` 를 그대로 써서 행을 만든다.
        invitationRepository.save(
            ProjectInvitationEntity(
                projectId = projectId,
                appUserId = hubotId,
                role = "MEMBER",
                status = ProjectInvitationStatus.PENDING.name,
                createdAt = Instant.now(),
                expiresAt = Instant.now().plus(1, ChronoUnit.DAYS)
            )
        )

        assertThat(suggest(ownerToken, projectId, "zeph")).isEmpty()
    }

    @Test
    fun `hands back at most ten candidates`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        repeat(12) { index ->
            signIn("${100 + index}", "crowd$index")
            renameTo("${100 + index}", nickname = "Crowd$index", userTag = "0001")
        }

        assertThat(suggest(ownerToken, projectId, "crowd")).hasSize(10)
    }

    @Test
    fun `invites the account an appUserId names and carries its handle, not its address`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        val invitation = post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"appUserId":"$hubotId","role":"MEMBER"}"""
        )

        // 계정을 가리키는 초대라 주소가 실리지 않는다. 그 계정에 확인을 마친 주소가 있어도
        // 그렇다 — 이름으로 사람을 고른 화면이 남의 주소를 알아내는 통로가 되면 안 된다.
        assertThat(invitation["email"].isNull).isTrue()
        assertThat(invitation["nickname"].asText()).isEqualTo("Zephyr")
        assertThat(invitation["userTag"].asText()).isEqualTo("0001")
        assertThat(invitation["displayName"].asText()).isEqualTo("hubot")
        assertThat(invitation["role"].asText()).isEqualTo("MEMBER")
        assertThat(invitation["status"].asText()).isEqualTo("PENDING")
        // 그 사람은 이제 답을 기다리는 초대가 있으므로 후보에서 빠진다.
        assertThat(suggest(ownerToken, projectId, "zeph")).isEmpty()
    }

    @Test
    fun `refuses an invitation that names the target twice or not at all`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signIn("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        val both = errorOf {
            post(
                ownerToken,
                "/api/projects/$projectId/invitations",
                """{"email":"hubot@example.com","appUserId":"$hubotId","role":"MEMBER"}"""
            )
        }
        assertThat(both.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(codeOf(both)).isEqualTo("invitation_target_ambiguous")

        val neither = errorOf {
            post(ownerToken, "/api/projects/$projectId/invitations", """{"role":"MEMBER"}""")
        }
        assertThat(neither.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(codeOf(neither)).isEqualTo("invitation_target_ambiguous")
    }

    @Test
    fun `refuses an appUserId that names no account`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signInWithoutEmail("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        val missing = errorOf {
            post(
                ownerToken,
                "/api/projects/$projectId/invitations",
                """{"appUserId":"${hubotId + 100_000}","role":"MEMBER"}"""
            )
        }
        assertThat(missing.statusCode).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(codeOf(missing)).isEqualTo("invitation_target_not_found")
    }

    /**
     * 후보로 나오는 것과 실제로 부를 수 있는 것이 어긋나면, 화면이 고를 수 없는 사람을 목록에
     * 낸다. 주소가 아예 없는 계정에서 그 둘이 같은지를 여기서 본다 — 이 초대가 막히던 것이
     * ARTEL-774 가 고친 것이다.
     */
    @Test
    fun `invites an account that has no address at all`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val projectId = createProject(ownerToken)
        signInWithoutEmail("60", "hubot")
        val hubotId = renameTo("60", nickname = "Zephyr", userTag = "0001")

        assertThat(suggest(ownerToken, projectId, "zeph").map { it["appUserId"].asText() })
            .containsExactly(hubotId.toString())

        val invitation = post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"appUserId":"$hubotId","role":"MEMBER"}"""
        )

        assertThat(invitation["email"].isNull).isTrue()
        assertThat(invitation["nickname"].asText()).isEqualTo("Zephyr")
        assertThat(invitation["status"].asText()).isEqualTo("PENDING")
        assertThat(suggest(ownerToken, projectId, "zeph")).isEmpty()
    }

    /**
     * 검색어를 URI 변수로 넘긴다. 문자열을 직접 이어 붙이면 `#` 이 fragment 를 열어 서버에 닿지
     * 않고, 미리 percent 인코딩해 붙이면 WebClient 가 그 `%` 를 다시 인코딩해 `%23` 이 글자
     * 그대로 도착한다. 변수로 넘겨야 WebClient 가 값만 한 번 인코딩한다.
     */
    private fun suggest(token: String, projectId: Long, query: String): List<JsonNode> =
        objectMapper.readTree(
            client().get()
                .uri("/api/projects/{projectId}/invitation-suggestions?query={query}", projectId, query)
                .cookie("artel_access_token", token)
                .retrieve().bodyToMono(String::class.java).block()
        ).toList()

    /**
     * 이름을 검증하는 테스트라 nickname 과 user_tag 를 직접 정한다. `OAuthUserService` 는 제공자
     * display name 에서 이름을 만들고 번호를 스스로 배정하므로, 그대로 두면 무엇을 찾는지가
     * 로그인 값에 묶인다.
     */
    private suspend fun renameTo(providerUserId: String, nickname: String, userTag: String): Long {
        val identity = requireNotNull(
            identityRepository.findByProviderAndProviderUserId("github", providerUserId)
        )
        val user = requireNotNull(appUserRepository.findById(identity.appUserId))
        appUserRepository.save(user.copy(nickname = nickname, userTag = userTag))
        return identity.appUserId
    }

    private suspend fun signIn(
        providerUserId: String,
        login: String,
        displayName: String = login,
        avatarUrl: String? = null
    ): String = signInWith(providerUserId, login, displayName, "$login@example.com", avatarUrl)

    /** GitHub에서 공개 이메일을 받지 못한 계정. */
    private suspend fun signInWithoutEmail(providerUserId: String, login: String): String =
        signInWith(providerUserId, login, login, email = null, avatarUrl = null)

    /**
     * 확인을 거치지 않고 `app_user.email` 에만 주소를 넣는다. API 로는 만들 수 없는 상태다 —
     * `POST /api/auth/me/email` 은 주소를 대기로만 두고, 확정은 토큰이 한다. 확인 전 주소가 후보
     * 검색에 어떻게 걸리는지를 보이려면 그 상태를 직접 만들어야 한다.
     */
    private suspend fun claimWithoutVerifying(providerUserId: String, email: String) {
        val identity = requireNotNull(
            identityRepository.findByProviderAndProviderUserId("github", providerUserId)
        )
        val user = requireNotNull(appUserRepository.findById(identity.appUserId))
        appUserRepository.save(user.copy(email = email, emailVerifiedAt = null))
    }

    private suspend fun signInWith(
        providerUserId: String,
        login: String,
        displayName: String,
        email: String?,
        avatarUrl: String?
    ): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = displayName,
                avatarUrl = avatarUrl,
                email = email
            )
        )
        return jwtService.issue(user)
    }

    private fun invite(ownerToken: String, projectId: Long, email: String) {
        post(
            ownerToken,
            "/api/projects/$projectId/invitations",
            """{"email":"$email","role":"MEMBER"}"""
        )
    }

    private fun makeMember(
        ownerToken: String,
        projectId: Long,
        email: String,
        inviteeToken: String
    ) {
        invite(ownerToken, projectId, email)
        val invitationId = readArray(inviteeToken, "/api/invitations")[0]["id"].asText()
        client().post().uri("/api/invitations/$invitationId/accept")
            .cookie("artel_access_token", inviteeToken)
            .retrieve().bodyToMono(String::class.java).block()
    }

    private fun createProject(token: String): Long =
        objectMapper.readTree(
            client().post().uri("/api/projects").cookie("artel_access_token", token)
                .contentType(MediaType.APPLICATION_JSON)
                .bodyValue("""{"name":"Demo Day","genre":"ACTION"}""")
                .retrieve().bodyToMono(String::class.java).block()
        )["id"].asText().toLong()

    private fun readArray(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

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
