package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.annotation.Transactional

@ActiveProfiles("test")
@SpringBootTest
@Transactional
class OAuthUserServiceIntegrationTest {
    @Autowired
    private lateinit var service: OAuthUserService

    @Autowired
    private lateinit var identityRepository: OAuthIdentityRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    private fun githubIdentity(
        providerUserId: String = "42",
        login: String = "octocat",
        displayName: String = "The Octocat",
        avatarUrl: String? = null,
        email: String? = null,
        provider: String = "github"
    ) = OAuthIdentity(
        provider = provider,
        providerUserId = providerUserId,
        login = login,
        displayName = displayName,
        avatarUrl = avatarUrl,
        email = email
    )

    @Test
    fun `reuses one user and refreshes the profile when the same provider account logs in again`() {
        val first = service.upsert(
            githubIdentity(avatarUrl = "https://avatars.example/old.png")
        )
        val second = service.upsert(
            githubIdentity(
                login = "octocat-renamed",
                displayName = "Updated Octocat",
                avatarUrl = "https://avatars.example/new.png",
                email = "octocat@example.com"
            )
        )

        assertThat(second.userId).isEqualTo(first.userId)
        assertThat(appUserRepository.findAll()).hasSize(1)

        val identities = identityRepository.findAll()
        assertThat(identities).hasSize(1)
        assertThat(identities.single().login).isEqualTo("octocat-renamed")
        assertThat(identities.single().displayName).isEqualTo("Updated Octocat")
        assertThat(identities.single().email).isEqualTo("octocat@example.com")
    }

    @Test
    fun `issues a stable user id that does not encode the provider`() {
        val user = service.upsert(githubIdentity())

        val appUserId = appUserRepository.findAll().single().id.toString()
        assertThat(user.userId).isEqualTo(appUserId)
        assertThat(user.userId).doesNotContain("github")
    }

    @Test
    fun `does not auto-link a different provider account that shares an email`() {
        val github = service.upsert(
            githubIdentity(provider = "github", providerUserId = "42", email = "same@example.com")
        )
        val google = service.upsert(
            githubIdentity(
                provider = "google",
                providerUserId = "99",
                login = "octocat-google",
                email = "same@example.com"
            )
        )

        // 이메일이 같아도 자동으로 묶이지 않는다. 제공자가 이메일 소유를 보장하지 않기 때문이다.
        assertThat(google.userId).isNotEqualTo(github.userId)
        assertThat(appUserRepository.findAll()).hasSize(2)
        assertThat(identityRepository.findAll()).hasSize(2)
    }

    @Test
    fun `keeps separate users for different accounts on the same provider`() {
        val first = service.upsert(githubIdentity(providerUserId = "42"))
        val second = service.upsert(githubIdentity(providerUserId = "43", login = "hubot"))

        assertThat(second.userId).isNotEqualTo(first.userId)
        assertThat(appUserRepository.findAll()).hasSize(2)
    }
}
