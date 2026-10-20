package kr.artel.orchestration.telemetry

import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.settings.repository.PlatformSettingRepository
import kr.artel.orchestration.support.PrivateTestDatabase
import kr.artel.orchestration.telemetry.service.TELEMETRY_CONSENT_SETTING
import kr.artel.orchestration.telemetry.service.TELEMETRY_INSTALL_ID_SETTING
import kr.artel.orchestration.telemetry.service.TelemetryConsentService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.web.reactive.function.client.WebClient

/**
 * 첫 가입 화면의 telemetry checkbox(`SignupRequest.telemetryOptIn`). 첫 가입자의 값만 저장된다.
 *
 * 두 번째 가입이 들어와야 확인할 수 있어 가입을 열어 두고, 빈 `app_user` 가 필요해 자기 database 에서 돈다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetrySignupIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun settings(registry: DynamicPropertyRegistry) {
            PrivateTestDatabase.register(registry, "telemetry_signup")
            registry.add("artel.auth.signup.open") { "true" }
            registry.add("artel.telemetry.endpoint") { "http://localhost:9/reports" }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var consentService: TelemetryConsentService
    @Autowired private lateinit var platformSettingRepository: PlatformSettingRepository

    @BeforeEach
    fun emptyDatabase(): Unit = runBlocking {
        databaseClient.sql("DELETE FROM platform_setting").fetch().rowsUpdated().awaitSingle()
        databaseClient.sql("TRUNCATE app_user CASCADE").fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `the first user's opt-in is stored and a later user's choice is ignored`(): Unit = runBlocking {
        assertThat(signup("owner@example.com", """"telemetryOptIn":true""")).isEqualTo(201)
        assertThat(consentService.isEnabled()).isTrue()
        assertThat(consentService.status().source).isEqualTo("setting")
        val installId = consentService.installId()
        assertThat(installId).isNotNull()
        val firstUserId = platformSettingRepository.findById(TELEMETRY_CONSENT_SETTING)!!.updatedBy

        assertThat(signup("guest@example.com", """"telemetryOptIn":false""")).isEqualTo(201)

        assertThat(consentService.isEnabled()).isTrue()
        assertThat(consentService.installId()).isEqualTo(installId)
        assertThat(platformSettingRepository.findById(TELEMETRY_CONSENT_SETTING)!!.updatedBy).isEqualTo(firstUserId)
    }

    @Test
    fun `a first user who declines stores off, and a second user cannot turn it on`(): Unit = runBlocking {
        assertThat(signup("owner@example.com", """"telemetryOptIn":false""")).isEqualTo(201)
        assertThat(signup("guest@example.com", """"telemetryOptIn":true""")).isEqualTo(201)

        assertThat(consentService.hasStoredConsent()).isTrue()
        assertThat(consentService.isEnabled()).isFalse()
        assertThat(platformSettingRepository.findById(TELEMETRY_INSTALL_ID_SETTING)).isNull()
    }

    @Test
    fun `a signup without the field stores nothing`(): Unit = runBlocking {
        assertThat(signup("owner@example.com", null)).isEqualTo(201)

        assertThat(consentService.hasStoredConsent()).isFalse()
    }

    private fun signup(email: String, telemetryField: String?): Int {
        val extra = telemetryField?.let { ",$it" }.orEmpty()
        return WebClient.create("http://localhost:$port").post().uri("/api/auth/signup")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue("""{"email":"$email","password":"correct horse","name":"${email.substringBefore('@')}"$extra}""")
            .exchangeToMono { response -> response.releaseBody().thenReturn(response.statusCode().value()) }
            .block()!!
    }
}
