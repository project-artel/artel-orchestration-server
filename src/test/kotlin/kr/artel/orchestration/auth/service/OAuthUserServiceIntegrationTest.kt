package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles

@ActiveProfiles("test")
@SpringBootTest
class OAuthUserServiceIntegrationTest {
    @Autowired
    private lateinit var service: OAuthUserService

    @Autowired
    private lateinit var identityRepository: OAuthIdentityRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    /**
     * 리액티브 트랜잭션은 스레드가 아니라 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이
     * 동작하지 않는다. 인메모리 H2를 다른 테스트와 공유하므로 각 테스트 시작 시 직접 비운다.
     */
    @BeforeEach
    fun clean() {
        identityRepository.deleteAll().block()
        appUserRepository.deleteAll().block()
    }

    private fun identity(
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
        val first = service.upsert(identity(avatarUrl = "https://avatars.example/old.png")).block()
        val second = service.upsert(
            identity(
                login = "octocat-renamed",
                displayName = "Updated Octocat",
                avatarUrl = "https://avatars.example/new.png",
                email = "octocat@example.com"
            )
        ).block()

        assertThat(second?.userId).isEqualTo(first?.userId)
        assertThat(appUserRepository.findAll().collectList().block()).hasSize(1)

        val identities = identityRepository.findAll().collectList().block()!!
        assertThat(identities).hasSize(1)
        assertThat(identities.single().login).isEqualTo("octocat-renamed")
        assertThat(identities.single().displayName).isEqualTo("Updated Octocat")
        assertThat(identities.single().email).isEqualTo("octocat@example.com")
    }

    @Test
    fun `issues a stable user id that does not encode the provider`() {
        val user = service.upsert(identity()).block()!!

        val appUserId = appUserRepository.findAll().collectList().block()!!.single().id.toString()
        assertThat(user.userId).isEqualTo(appUserId)
        assertThat(user.userId).doesNotContain("github")
    }

    @Test
    fun `does not auto-link a different provider account that shares an email`() {
        val github = service.upsert(
            identity(provider = "github", providerUserId = "42", email = "same@example.com")
        ).block()!!
        val google = service.upsert(
            identity(
                provider = "google",
                providerUserId = "99",
                login = "octocat-google",
                email = "same@example.com"
            )
        ).block()!!

        // 이메일이 같아도 자동으로 묶이지 않는다. 제공자가 이메일 소유를 보장하지 않기 때문이다.
        assertThat(google.userId).isNotEqualTo(github.userId)
        assertThat(appUserRepository.findAll().collectList().block()).hasSize(2)
        assertThat(identityRepository.findAll().collectList().block()).hasSize(2)
    }

    @Test
    fun `keeps separate users for different accounts on the same provider`() {
        val first = service.upsert(identity(providerUserId = "42")).block()!!
        val second = service.upsert(identity(providerUserId = "43", login = "hubot")).block()!!

        assertThat(second.userId).isNotEqualTo(first.userId)
        assertThat(appUserRepository.findAll().collectList().block()).hasSize(2)
    }

    @Test
    fun `reads back the profile with its linked identities`() {
        val user = service.upsert(identity(email = "octocat@example.com")).block()!!

        val profile = service.findProfile(user.userId.toLong()).block()!!

        assertThat(profile.userId).isEqualTo(user.userId)
        assertThat(profile.displayName).isEqualTo("The Octocat")
        assertThat(profile.email).isEqualTo("octocat@example.com")
        assertThat(profile.identities).hasSize(1)
        assertThat(profile.identities.single().provider).isEqualTo("github")
        assertThat(profile.identities.single().login).isEqualTo("octocat")
    }

    @Test
    fun `returns empty for a user that does not exist`() {
        assertThat(service.findProfile(99999999L).block()).isNull()
    }
}
