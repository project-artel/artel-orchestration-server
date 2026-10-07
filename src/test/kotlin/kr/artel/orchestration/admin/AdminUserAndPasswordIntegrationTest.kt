package kr.artel.orchestration.admin

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.support.PrivateTestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient

/**
 * ADMIN 의 사용자 관리와, 그것이 만든 임시 비밀번호 계정이 비밀번호를 바꾸기 전까지 막히는지.
 *
 * 매 테스트가 빈 database 에서 첫 가입으로 ADMIN 을 만들고 시작한다([PrivateTestDatabase]).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AdminUserAndPasswordIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "admin_users")

        private const val ADMIN_PASSWORD = "admin password"
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var platformAccessService: PlatformAccessService

    private lateinit var adminSession: String
    private lateinit var adminId: String

    @BeforeEach
    fun firstAdmin(): Unit = runBlocking {
        databaseClient.sql("TRUNCATE app_user CASCADE").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        val signup = call(
            HttpMethod.POST, "/api/auth/signup", session = null,
            json = """{"email":"admin@example.com","password":"$ADMIN_PASSWORD","name":"Admin"}"""
        )
        adminSession = signup.cookies.getValue(authProperties.cookieName)
        adminId = signup.body["id"].asText()
    }

    @Test
    fun `admin creates a user whose temporary password forces a password change`() {
        val created = call(
            HttpMethod.POST, "/api/admin/users", adminSession,
            """{"email":"member@example.com","name":"Member"}"""
        )
        assertThat(created.status).isEqualTo(201)
        val temporaryPassword = created.body["temporaryPassword"].asText()
        assertThat(temporaryPassword).hasSize(20)
        assertThat(created.body["user"]["platformRole"].asText()).isEqualTo("USER")
        assertThat(created.body["user"]["mustChangePassword"].asBoolean()).isTrue()

        val login = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"member@example.com","password":"$temporaryPassword"}""")
        assertThat(login.status).isEqualTo(200)
        assertThat(login.body["mustChangePassword"].asBoolean()).isTrue()
        val memberSession = login.cookies.getValue(authProperties.cookieName)

        // 비밀번호를 바꾸기 전에는 프로필 조회와 비밀번호 변경만 된다.
        val blocked = call(HttpMethod.GET, "/api/projects", memberSession)
        assertThat(blocked.status).isEqualTo(403)
        assertThat(blocked.body["code"].asText()).isEqualTo("password_change_required")
        assertThat(call(HttpMethod.PUT, "/api/auth/me/locale", memberSession, """{"locale":"en"}""").status).isEqualTo(403)
        assertThat(call(HttpMethod.GET, "/api/auth/me", memberSession).status).isEqualTo(200)

        val wrongCurrent = call(
            HttpMethod.POST, "/api/auth/password", memberSession,
            """{"currentPassword":"not the password","newPassword":"member password"}"""
        )
        assertThat(wrongCurrent.status).isEqualTo(400)
        assertThat(wrongCurrent.body["code"].asText()).isEqualTo("invalid_current_password")

        val changed = call(
            HttpMethod.POST, "/api/auth/password", memberSession,
            """{"currentPassword":"$temporaryPassword","newPassword":"member password"}"""
        )
        assertThat(changed.status).isEqualTo(204)

        // 같은 세션이 다음 요청부터 풀린다. 상태는 토큰이 아니라 DB 에서 읽기 때문이다.
        assertThat(call(HttpMethod.GET, "/api/projects", memberSession).status).isEqualTo(200)
        assertThat(call(HttpMethod.GET, "/api/auth/me", memberSession).body["mustChangePassword"].asBoolean()).isFalse()
        assertThat(call(HttpMethod.POST, "/api/auth/login", null, """{"email":"member@example.com","password":"member password"}""").status)
            .isEqualTo(200)
    }

    @Test
    fun `reset password issues a new temporary password and forces a change again`() {
        val memberId = createAndActivate("member@example.com", "USER").first

        val reset = call(HttpMethod.POST, "/api/admin/users/$memberId/reset-password", adminSession)
        assertThat(reset.status).isEqualTo(200)
        val temporaryPassword = reset.body["temporaryPassword"].asText()

        assertThat(call(HttpMethod.POST, "/api/auth/login", null, """{"email":"member@example.com","password":"member password"}""").status)
            .isEqualTo(401)
        val login = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"member@example.com","password":"$temporaryPassword"}""")
        assertThat(login.body["mustChangePassword"].asBoolean()).isTrue()
    }

    @Test
    fun `USER and DEVELOPER get 403 on every admin endpoint`() {
        val (userId, userSession) = createAndActivate("user@example.com", "USER")
        val (_, developerSession) = createAndActivate("developer@example.com", "DEVELOPER")

        for (session in listOf(userSession, developerSession)) {
            val requests = listOf(
                Triple(HttpMethod.GET, "/api/admin/users", null),
                Triple(HttpMethod.POST, "/api/admin/users", """{"email":"x@example.com","name":"X"}"""),
                Triple(HttpMethod.POST, "/api/admin/users/$userId/reset-password", null),
                Triple(HttpMethod.PATCH, "/api/admin/users/$userId", """{"platformRole":"ADMIN"}"""),
                Triple(HttpMethod.GET, "/api/admin/settings/llm", null),
                Triple(HttpMethod.PUT, "/api/admin/settings/llm", """{"apiKey":"sk-or-v1-should-not-be-stored"}"""),
                Triple(HttpMethod.POST, "/api/admin/settings/llm/check", null)
            )
            for ((method, path, json) in requests) {
                val response = call(method, path, session, json)
                assertThat(response.status).describedAs("$method $path").isEqualTo(403)
                assertThat(response.body["code"].asText()).describedAs("$method $path").isEqualTo("admin_required")
            }
        }
        assertThat(call(HttpMethod.GET, "/api/admin/users", adminSession).status).isEqualTo(200)
    }

    @Test
    fun `admin lists users, changes a role and ADMIN sees all projects`(): Unit = runBlocking {
        val (memberId, _) = createAndActivate("member@example.com", "USER")

        val listed = call(HttpMethod.GET, "/api/admin/users", adminSession)
        assertThat(listed.body.map { it["loginEmail"].asText() })
            .containsExactly("admin@example.com", "member@example.com")

        val promoted = call(HttpMethod.PATCH, "/api/admin/users/$memberId", adminSession, """{"platformRole":"DEVELOPER"}""")
        assertThat(promoted.body["platformRole"].asText()).isEqualTo("DEVELOPER")
        assertThat(platformAccessService.seesAllProjects(adminId.toLong())).isTrue()

        val invalidRole = call(HttpMethod.PATCH, "/api/admin/users/$memberId", adminSession, """{"platformRole":"ROOT"}""")
        assertThat(invalidRole.status).isEqualTo(400)
    }

    @Test
    fun `the last ADMIN cannot be demoted or disabled`() {
        val demote = call(HttpMethod.PATCH, "/api/admin/users/$adminId", adminSession, """{"platformRole":"USER"}""")
        val disable = call(HttpMethod.PATCH, "/api/admin/users/$adminId", adminSession, """{"disabled":true}""")

        assertThat(demote.status).isEqualTo(409)
        assertThat(demote.body["code"].asText()).isEqualTo("last_admin")
        assertThat(disable.status).isEqualTo(409)
    }

    @Test
    fun `a disabled account cannot log in, refresh, or use a live session`() {
        val (memberId, memberSession) = createAndActivate("member@example.com", "USER")

        call(HttpMethod.PATCH, "/api/admin/users/$memberId", adminSession, """{"disabled":true}""")

        val live = call(HttpMethod.GET, "/api/auth/me", memberSession)
        assertThat(live.status).isEqualTo(403)
        assertThat(live.body["code"].asText()).isEqualTo("account_disabled")
        val login = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"member@example.com","password":"member password"}""")
        assertThat(login.status).isEqualTo(403)
        assertThat(login.body["code"].asText()).isEqualTo("account_disabled")
    }

    @Test
    fun `wrong password and unknown email answer the same 401`() {
        val wrongPassword = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"admin@example.com","password":"wrong password"}""")
        val unknownEmail = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"nobody@example.com","password":"wrong password"}""")

        assertThat(wrongPassword.status).isEqualTo(401)
        assertThat(unknownEmail.status).isEqualTo(401)
        assertThat(wrongPassword.body).isEqualTo(unknownEmail.body)
    }

    /** 이메일로만 가입한 계정은 OAuth 신원이 없어도 refresh 쿠키로 access 쿠키를 다시 받는다. */
    @Test
    fun `a password-only account refreshes its session`() {
        val login = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"admin@example.com","password":"$ADMIN_PASSWORD"}""")
        val refreshCookie = login.cookies.getValue(authProperties.refreshCookieName)

        val refreshed = client().post().uri("/api/auth/refresh")
            .cookie(authProperties.refreshCookieName, refreshCookie)
            .exchangeToMono { response ->
                reactor.core.publisher.Mono.just(
                    response.statusCode().value() to response.cookies().getFirst(authProperties.cookieName)?.value
                )
            }.block()!!

        assertThat(refreshed.first).isEqualTo(204)
        assertThat(call(HttpMethod.GET, "/api/auth/me", refreshed.second).body["id"].asText()).isEqualTo(adminId)
    }

    /** ADMIN 이 계정을 만들고, 그 사람이 로그인해 비밀번호를 바꾼 세션을 돌려준다. */
    private fun createAndActivate(email: String, platformRole: String): Pair<String, String> {
        val created = call(
            HttpMethod.POST, "/api/admin/users", adminSession,
            """{"email":"$email","name":"${email.substringBefore('@')}","platformRole":"$platformRole"}"""
        )
        val temporaryPassword = created.body["temporaryPassword"].asText()
        val session = call(HttpMethod.POST, "/api/auth/login", null, """{"email":"$email","password":"$temporaryPassword"}""")
            .cookies.getValue(authProperties.cookieName)
        call(
            HttpMethod.POST, "/api/auth/password", session,
            """{"currentPassword":"$temporaryPassword","newPassword":"member password"}"""
        )
        return created.body["user"]["id"].asText() to session
    }

    private data class HttpResult(val status: Int, val body: JsonNode, val cookies: Map<String, String>)

    private fun call(method: HttpMethod, path: String, session: String?, json: String? = null): HttpResult {
        val request = client().method(method).uri(path)
            .apply { if (session != null) cookie(authProperties.cookieName, session) }
        val withBody = if (json != null) request.contentType(MediaType.APPLICATION_JSON).bodyValue(json) else request
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("{}").map { body ->
                HttpResult(
                    status = response.statusCode().value(),
                    body = objectMapper.readTree(body),
                    cookies = response.cookies().mapValues { it.value.first().value }
                )
            }
        }.block()!!
    }

    private fun client() = WebClient.create("http://localhost:$port")
}
