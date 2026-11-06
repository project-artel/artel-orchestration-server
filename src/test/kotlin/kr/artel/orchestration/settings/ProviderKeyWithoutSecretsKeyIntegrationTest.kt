package kr.artel.orchestration.settings

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.support.testAppUser
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono

/**
 * `ARTEL_SECRETS_KEY` 가 없는 설치. 테스트 기본 설정이 그렇다. 세 key 와 사용자 key 모두 저장을 409
 * `secrets_key_missing` 으로 거절하고, 아무것도 남기지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProviderKeyWithoutSecretsKeyIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var authProperties: AuthProperties
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    private var adminId: Long = 0
    private lateinit var adminSession: String

    @BeforeEach
    fun seedAdmin(): Unit = runBlocking {
        val admin = appUserRepository.save(testAppUser("no-secrets-admin").copy(platformRole = PlatformRole.ADMIN.name))
        adminId = admin.id!!
        adminSession = jwtService.issue(AuthenticatedUser(adminId.toString(), "password", "no-secrets-admin", "no-secrets-admin", null))
    }

    @AfterEach
    fun clean(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        appUserRepository.deleteById(adminId)
    }

    @Test
    fun `saving any provider key is 409 secrets_key_missing`(): Unit = runBlocking {
        for (path in listOf("/api/admin/settings/bedrock", "/api/admin/settings/llm", "/api/admin/settings/anthropic")) {
            // `sk-ant-` 로 시작해야 Claude API key 의 형식 확인을 지나 암호화 단계까지 간다.
            val (status, body) = put(path, """{"apiKey":"sk-ant-key-that-cannot-be-stored-0123456789"}""")
            assertThat(status).describedAs(path).isEqualTo(409)
            assertThat(objectMapper.readTree(body)["code"].asText()).describedAs(path).isEqualTo("secrets_key_missing")
        }
        val rows = databaseClient.sql("SELECT COUNT(*) AS n FROM platform_setting")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(rows).isZero()
    }

    /** 사용자 key 도 같다. 저장할 수 없으니 서비스에 묻기 전에 거절한다 — 이 테스트에는 가짜 서비스가 없다. */
    @Test
    fun `saving a user key is 409 secrets_key_missing before the service is called`(): Unit = runBlocking {
        val (status, body) = put("/api/auth/me/llm-keys/openai", """{"apiKey":"sk-proj-key-that-cannot-be-stored-0123456789"}""")

        assertThat(status).isEqualTo(409)
        assertThat(objectMapper.readTree(body)["code"].asText()).isEqualTo("secrets_key_missing")
        val rows = databaseClient.sql("SELECT COUNT(*) AS n FROM user_llm_key")
            .map { row, _ -> row.get("n", Long::class.javaObjectType)!! }
            .one().awaitSingle()
        assertThat(rows).isZero()
    }

    private fun put(path: String, json: String): Pair<Int, String> =
        WebClient.create("http://localhost:$port").put().uri(path)
            .cookie(authProperties.cookieName, adminSession)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(json.toByteArray(Charsets.UTF_8))
            .exchangeToMono { response ->
                response.bodyToMono(ByteArray::class.java).map { response.statusCode().value() to String(it, Charsets.UTF_8) }
                    .switchIfEmpty(Mono.just(response.statusCode().value() to ""))
            }.block()!!
}
