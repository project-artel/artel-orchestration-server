package kr.artel.orchestration.auth

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.PasswordAccountService
import kr.artel.orchestration.support.PrivateTestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * 가입이 열려 있을 때(`ARTEL_SIGNUP_OPEN=true`) 동시에 온 첫 가입들.
 *
 * 닫힌 가입에서는 늦게 온 쪽이 거절되므로 ADMIN 이 둘 생기는 경합이 덜 드러난다. 여기서는 여덟 건이
 * 모두 들어가야 하고, 그중 ADMIN 은 정확히 하나여야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = ["artel.auth.signup.open=true"]
)
class OpenSignupRaceIntegrationTest {

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "signup_open")
    }

    @Autowired private lateinit var passwordAccountService: PasswordAccountService
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var databaseClient: DatabaseClient

    @BeforeEach
    fun emptyDatabase(): Unit = runBlocking {
        databaseClient.sql("TRUNCATE app_user CASCADE").fetch().rowsUpdated().awaitSingle()
    }

    @Test
    fun `concurrent signups all succeed and exactly one becomes ADMIN`(): Unit = runBlocking {
        (1..8).map { index ->
            async(Dispatchers.IO) {
                passwordAccountService.signup("racer$index@example.com", "correct horse", "Racer $index")
            }
        }.awaitAll()

        val roles = appUserRepository.findAll().toList().map { it.platformRole }
        assertThat(roles).hasSize(8)
        assertThat(roles.count { it == PlatformRole.ADMIN.name }).isEqualTo(1)
        assertThat(roles.count { it == PlatformRole.USER.name }).isEqualTo(7)
    }

    @Test
    fun `the same email cannot sign up twice`(): Unit = runBlocking {
        passwordAccountService.signup("twice@example.com", "correct horse", "First")

        val second = runCatching { passwordAccountService.signup("TWICE@example.com", "correct horse", "Second") }

        assertThat(second.exceptionOrNull()).hasFieldOrPropertyWithValue("code", "email_taken")
    }
}
