package kr.artel.orchestration.auth

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.GitHubOAuthProperties
import kr.artel.orchestration.auth.entity.PlatformRole
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.service.AccountDisabledException
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthLoginService
import kr.artel.orchestration.auth.service.OAuthSignupClosedException
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.auth.service.PasswordAccountService
import kr.artel.orchestration.support.PrivateTestDatabase
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Nested
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource

/**
 * `ARTEL_GITHUB_SIGNUP_OPEN` 의 두 값. GitHub 왕복 없이 확인하려고 성공 핸들러가 부르는
 * [OAuthLoginService] 를 직접 부른다. 핸들러는 [OAuthSignupClosedException] 을 `/login?error=signup_closed`
 * 로 옮길 뿐이다.
 */
class GitHubSignupPolicyIntegrationTest {

    private fun githubIdentity(id: String, email: String?) = OAuthIdentity(
        provider = "github",
        providerUserId = id,
        login = "login-$id",
        displayName = "GitHub $id",
        avatarUrl = null,
        email = email
    )

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
    inner class SignupOpenByDefault {
        @Autowired private lateinit var oauthLoginService: OAuthLoginService
        @Autowired private lateinit var gitHubOAuthProperties: GitHubOAuthProperties

        @Test
        fun `defaults to open and creates a new user for an unknown GitHub account`(): Unit = runBlocking {
            assertThat(gitHubOAuthProperties.signupOpen).isTrue()

            val user = oauthLoginService.signIn(githubIdentity("policy-open-${System.nanoTime()}", null))

            assertThat(user.userId).isNotBlank()
        }
    }

    @Nested
    @ActiveProfiles("test")
    @SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = ["artel.auth.github.signup-open=false"]
    )
    inner class SignupClosed {
        @Autowired private lateinit var oauthLoginService: OAuthLoginService
        @Autowired private lateinit var oauthUserService: OAuthUserService
        @Autowired private lateinit var passwordAccountService: PasswordAccountService
        @Autowired private lateinit var appUserRepository: AppUserRepository
        @Autowired private lateinit var databaseClient: DatabaseClient

        @BeforeEach
        fun emptyDatabase(): Unit = runBlocking {
            databaseClient.sql("TRUNCATE app_user CASCADE").fetch().rowsUpdated().awaitSingle()
        }

        @Test
        fun `rejects an unknown GitHub account without creating a user`(): Unit = runBlocking {
            assertThatThrownBy { runBlocking { oauthLoginService.signIn(githubIdentity("stranger", "stranger@example.com")) } }
                .isInstanceOf(OAuthSignupClosedException::class.java)
            assertThat(appUserRepository.findAll().toList()).isEmpty()
        }

        @Test
        fun `an existing GitHub user keeps logging in`(): Unit = runBlocking {
            val existing = oauthUserService.upsert(githubIdentity("existing", null))

            val again = oauthLoginService.signIn(githubIdentity("existing", null))

            assertThat(again.userId).isEqualTo(existing.userId)
        }

        @Test
        fun `a GitHub account whose email an ADMIN registered joins that account`(): Unit = runBlocking {
            val created = passwordAccountService.createByAdmin("Invited@Example.com", "Invited", PlatformRole.USER)

            val user = oauthLoginService.signIn(githubIdentity("invited", "invited@example.com"))

            assertThat(user.userId).isEqualTo(created.user.id.toString())
            assertThat(appUserRepository.findAll().toList()).hasSize(1)
            // 같은 계정에 다른 GitHub 계정이 같은 이메일로 또 붙지는 못한다.
            assertThatThrownBy { runBlocking { oauthLoginService.signIn(githubIdentity("impostor", "invited@example.com")) } }
                .isInstanceOf(OAuthSignupClosedException::class.java)
        }

        @Test
        fun `a disabled GitHub user is refused`(): Unit = runBlocking {
            val existing = oauthUserService.upsert(githubIdentity("disabled", null))
            val row = appUserRepository.findById(existing.userId.toLong())!!
            appUserRepository.save(row.copy(disabled = true))

            assertThatThrownBy { runBlocking { oauthLoginService.signIn(githubIdentity("disabled", null)) } }
                .isInstanceOf(AccountDisabledException::class.java)
        }
    }

    companion object {
        @JvmStatic
        @DynamicPropertySource
        fun privateDatabase(registry: DynamicPropertyRegistry) =
            PrivateTestDatabase.register(registry, "github_policy")
    }
}
