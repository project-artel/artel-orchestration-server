package kr.artel.orchestration.auth.service

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
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

    @Autowired
    private lateinit var db: DatabaseClient

    /**
     * 리액티브 트랜잭션은 스레드가 아니라 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이
     * 동작하지 않는다. 인메모리 H2를 다른 테스트와 공유하므로 각 테스트 시작 시 직접 비운다.
     */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
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
    fun `reuses one user and refreshes the profile when the same provider account logs in again`(): Unit = runBlocking {
        val first = service.upsert(identity(avatarUrl = "https://avatars.example/old.png"))
        val second = service.upsert(
            identity(
                login = "octocat-renamed",
                displayName = "Updated Octocat",
                avatarUrl = "https://avatars.example/new.png",
                email = "octocat@example.com"
            )
        )

        assertThat(second.userId).isEqualTo(first.userId)
        assertThat(appUserRepository.findAll().toList()).hasSize(1)

        val identities = identityRepository.findAll().toList()
        assertThat(identities).hasSize(1)
        assertThat(identities.single().login).isEqualTo("octocat-renamed")
        assertThat(identities.single().displayName).isEqualTo("Updated Octocat")
        assertThat(identities.single().email).isEqualTo("octocat@example.com")
    }

    @Test
    fun `issues a stable user id that does not encode the provider`(): Unit = runBlocking {
        val user = service.upsert(identity())

        val appUserId = appUserRepository.findAll().toList().single().id.toString()
        assertThat(user.userId).isEqualTo(appUserId)
        assertThat(user.userId).doesNotContain("github")
    }

    @Test
    fun `does not auto-link a different provider account that shares an email`(): Unit = runBlocking {
        val github = service.upsert(
            identity(provider = "github", providerUserId = "42", email = "same@example.com")
        )
        val google = service.upsert(
            identity(
                provider = "google",
                providerUserId = "99",
                login = "octocat-google",
                email = "same@example.com"
            )
        )

        // 이메일이 같아도 자동으로 묶이지 않는다. 제공자가 이메일 소유를 보장하지 않기 때문이다.
        assertThat(google.userId).isNotEqualTo(github.userId)
        assertThat(appUserRepository.findAll().toList()).hasSize(2)
        assertThat(identityRepository.findAll().toList()).hasSize(2)
    }

    @Test
    fun `keeps separate users for different accounts on the same provider`(): Unit = runBlocking {
        val first = service.upsert(identity(providerUserId = "42"))
        val second = service.upsert(identity(providerUserId = "43", login = "hubot"))

        assertThat(second.userId).isNotEqualTo(first.userId)
        assertThat(appUserRepository.findAll().toList()).hasSize(2)
    }

    @Test
    fun `reads back the profile with its linked identities`(): Unit = runBlocking {
        val user = service.upsert(identity(email = "octocat@example.com"))

        val profile = service.findProfile(user.userId.toLong())!!

        assertThat(profile.userId).isEqualTo(user.userId)
        assertThat(profile.displayName).isEqualTo("The Octocat")
        assertThat(profile.email).isEqualTo("octocat@example.com")
        assertThat(profile.identities).hasSize(1)
        assertThat(profile.identities.single().provider).isEqualTo("github")
        assertThat(profile.identities.single().login).isEqualTo("octocat")
    }

    @Test
    fun `returns empty for a user that does not exist`(): Unit = runBlocking {
        assertThat(service.findProfile(99999999L)).isNull()
    }

    @Test
    fun `seeds the nickname from the provider display name`(): Unit = runBlocking {
        val user = service.upsert(identity())

        val profile = service.findProfile(user.userId.toLong())!!
        assertThat(profile.nickname).isEqualTo("The Octocat")
        assertThat(profile.userTag).isEqualTo("0000")
    }

    @Test
    fun `falls back to the provider login when the display name is blank`(): Unit = runBlocking {
        val user = service.upsert(identity(login = "octocat", displayName = "   "))

        assertThat(service.findProfile(user.userId.toLong())!!.nickname).isEqualTo("octocat")
    }

    @Test
    fun `gives two accounts with the same provider display name different userTags`(): Unit = runBlocking {
        val first = service.upsert(identity(providerUserId = "42", login = "octocat"))
        val second = service.upsert(identity(providerUserId = "43", login = "hubot"))

        val firstProfile = service.findProfile(first.userId.toLong())!!
        val secondProfile = service.findProfile(second.userId.toLong())!!
        assertThat(secondProfile.nickname).isEqualTo(firstProfile.nickname)
        assertThat(firstProfile.userTag).isEqualTo("0000")
        assertThat(secondProfile.userTag).isEqualTo("0001")
    }

    @Test
    fun `grows the userTag to five digits once every four digit one is taken`(): Unit = runBlocking {
        val user = service.upsert(identity())
        // 0000~9999 를 API 로 만들면 만 번 로그인해야 한다. 검증 대상은 배정 규칙이므로 행을 직접 넣는다.
        fillFourDigitSpace()

        val profile = service.updateProfile(user.userId.toLong(), "Crowded")!!

        assertThat(profile.userTag).isEqualTo("00000")
    }

    /** nickname "Crowded" 아래 네 자리 번호 10000개를 모두 채운다. */
    private fun fillFourDigitSpace() {
        db.sql(
            """
            INSERT INTO app_user (display_name, nickname, user_tag)
            SELECT 'Crowded', 'Crowded', LPAD(tag_number::text, 4, '0')
            FROM generate_series(0, 9999) AS tag_number
            """
        ).fetch().rowsUpdated().block()
    }
}
