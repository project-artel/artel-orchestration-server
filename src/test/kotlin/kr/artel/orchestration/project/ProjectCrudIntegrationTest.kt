package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
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

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectCrudIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository

    /**
     * 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다.
     * 인메모리 H2를 다른 테스트와 공유하므로 각 테스트 시작 시 직접 비운다.
     */
    @BeforeEach
    fun clean() {
        documentRepository.deleteAll().block()
        memberRepository.deleteAll().block()
        projectRepository.deleteAll().block()
        identityRepository.deleteAll().block()
        appUserRepository.deleteAll().block()
    }

    @Test
    fun `creates a project and returns it in the owner's list`() {
        val token = signIn("42", "octocat")

        val created = post(token, "/api/projects", """{"name":"Demo Day","description":"QA 대상","genre":"ACTION"}""")

        assertThat(created["name"].asText()).isEqualTo("Demo Day")
        assertThat(created["genre"].asText()).isEqualTo("ACTION")
        assertThat(created["myRole"].asText()).isEqualTo("OWNER")
        assertThat(created["document"].isNull).isTrue()

        val page = get(token, "/api/projects")
        assertThat(page["total"].asLong()).isEqualTo(1)
        assertThat(page["page"].asInt()).isZero()
        assertThat(page["items"][0]["name"].asText()).isEqualTo("Demo Day")
        assertThat(page["items"][0]["documentCount"].asLong()).isZero()
    }

    @Test
    fun `reads back a project by id`() {
        val token = signIn("42", "octocat")
        val id = post(token, "/api/projects", """{"name":"Demo Day","genre":"RPG"}""")["id"].asText()

        val detail = get(token, "/api/projects/$id")

        assertThat(detail["id"].asText()).isEqualTo(id)
        assertThat(detail["genre"].asText()).isEqualTo("RPG")
    }

    @Test
    fun `patches only the fields that were sent`() {
        val token = signIn("42", "octocat")
        val id = post(
            token,
            "/api/projects",
            """{"name":"Demo Day","description":"원래 설명","genre":"ACTION"}"""
        )["id"].asText()

        val patched = patch(token, "/api/projects/$id", """{"name":"이름만 변경"}""")

        assertThat(patched["name"].asText()).isEqualTo("이름만 변경")
        assertThat(patched["description"].asText()).isEqualTo("원래 설명")
        assertThat(patched["genre"].asText()).isEqualTo("ACTION")
    }

    @Test
    fun `clears the description with an empty string, not with null`() {
        val token = signIn("42", "octocat")
        val id = post(
            token,
            "/api/projects",
            """{"name":"Demo Day","description":"지워질 설명","genre":"ACTION"}"""
        )["id"].asText()

        val untouched = patch(token, "/api/projects/$id", """{"name":"새 이름"}""")
        assertThat(untouched["description"].asText()).isEqualTo("지워질 설명")

        val cleared = patch(token, "/api/projects/$id", """{"description":""}""")
        assertThat(cleared["description"].isNull).isTrue()
    }

    @Test
    fun `hides a project from users who are not members`() {
        val ownerToken = signIn("42", "octocat")
        val strangerToken = signIn("99", "hubot")
        val id = post(ownerToken, "/api/projects", """{"name":"비밀 프로젝트","genre":"OTHER"}""")["id"].asText()

        val status = statusOf { get(strangerToken, "/api/projects/$id") }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(strangerToken, "/api/projects")["total"].asLong()).isZero()
    }

    @Test
    fun `soft-deletes a project so it becomes indistinguishable from a missing one`() {
        val token = signIn("42", "octocat")
        val id = post(token, "/api/projects", """{"name":"삭제될 프로젝트","genre":"CASUAL"}""")["id"].asText()

        val deleted = delete(token, "/api/projects/$id")
        assertThat(deleted["deleted"].asBoolean()).isTrue()

        assertThat(statusOf { get(token, "/api/projects/$id") }).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(get(token, "/api/projects")["total"].asLong()).isZero()

        // 행 자체는 남아 있어야 한다. 지운 것이 아니라 지웠다고 표시한 것이다.
        assertThat(projectRepository.count().block()).isEqualTo(1)
    }

    @Test
    fun `rejects a project without a name`() {
        val token = signIn("42", "octocat")

        val error = errorOf { post(token, "/api/projects", """{"name":"","genre":"ACTION"}""") }

        assertThat(error.statusCode).isEqualTo(HttpStatus.BAD_REQUEST)
        val body = objectMapper.readTree(error.responseBodyAsString)
        assertThat(body["code"].asText()).isEqualTo("invalid_request")
        assertThat(body["fields"].has("name")).isTrue()
    }

    @Test
    fun `rejects an unknown genre`() {
        val token = signIn("42", "octocat")

        val status = statusOf { post(token, "/api/projects", """{"name":"X","genre":"MOBA"}""") }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `requires a session`() {
        val status = statusOf {
            WebClient.create("http://localhost:$port").get().uri("/api/projects")
                .retrieve().bodyToMono(String::class.java).block()
        }

        assertThat(status).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    @Test
    fun `pages the list and clamps an oversized page size`() {
        val token = signIn("42", "octocat")
        repeat(3) { index ->
            post(token, "/api/projects", """{"name":"프로젝트 $index","genre":"ACTION"}""")
        }

        val firstPage = get(token, "/api/projects?page=0&size=2")
        assertThat(firstPage["items"]).hasSize(2)
        assertThat(firstPage["total"].asLong()).isEqualTo(3)

        val secondPage = get(token, "/api/projects?page=1&size=2")
        assertThat(secondPage["items"]).hasSize(1)
        assertThat(secondPage["total"].asLong()).isEqualTo(3)

        val clamped = get(token, "/api/projects?size=5000")
        assertThat(clamped["size"].asInt()).isEqualTo(100)
    }

    private fun signIn(providerUserId: String, login: String): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        ).block()!!
        return jwtService.issue(user)
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun patch(token: String, uri: String, body: String) = objectMapper.readTree(
        client().patch().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun delete(token: String, uri: String) = objectMapper.readTree(
        client().delete().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

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
