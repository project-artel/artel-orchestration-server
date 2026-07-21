package kr.artel.orchestration.auth.service

import kr.artel.orchestration.auth.repository.OAuthUserRepository
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
    private lateinit var repository: OAuthUserRepository

    @Test
    fun `creates and updates one user for the same provider identity`() {
        service.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat",
                displayName = "The Octocat",
                avatarUrl = "https://avatars.example/old.png",
                email = null
            )
        )
        service.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = "42",
                login = "octocat-renamed",
                displayName = "Updated Octocat",
                avatarUrl = "https://avatars.example/new.png",
                email = "octocat@example.com"
            )
        )

        val users = repository.findAll()
        assertThat(users).hasSize(1)
        assertThat(users.single().provider).isEqualTo("github")
        assertThat(users.single().providerUserId).isEqualTo("42")
        assertThat(users.single().login).isEqualTo("octocat-renamed")
        assertThat(users.single().displayName).isEqualTo("Updated Octocat")
        assertThat(users.single().email).isEqualTo("octocat@example.com")
    }
}
