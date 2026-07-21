package kr.artel.orchestration.auth.oauth

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.security.core.authority.SimpleGrantedAuthority
import org.springframework.security.oauth2.client.authentication.OAuth2AuthenticationToken
import org.springframework.security.oauth2.core.user.DefaultOAuth2User

class GitHubOAuthIdentityMapperTest {
    private val mapper = GitHubOAuthIdentityMapper()

    @Test
    fun `maps GitHub attributes to a provider-neutral identity`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf(
                "id" to 42,
                "login" to "octocat",
                "name" to "The Octocat",
                "avatar_url" to "https://avatars.example/octocat.png"
            ),
            "id"
        )
        val authentication = OAuth2AuthenticationToken(
            principal,
            principal.authorities,
            "github"
        )

        val identity = mapper.map(authentication)

        assertThat(identity.provider).isEqualTo("github")
        assertThat(identity.providerUserId).isEqualTo("42")
        assertThat(identity.login).isEqualTo("octocat")
        assertThat(identity.displayName).isEqualTo("The Octocat")
        assertThat(identity.avatarUrl).isEqualTo("https://avatars.example/octocat.png")
    }

    @Test
    fun `falls back to login when GitHub name is absent`() {
        val principal = DefaultOAuth2User(
            listOf(SimpleGrantedAuthority("ROLE_USER")),
            mapOf("id" to 42, "login" to "octocat"),
            "id"
        )

        val identity = mapper.map(
            OAuth2AuthenticationToken(principal, principal.authorities, "github")
        )

        assertThat(identity.displayName).isEqualTo("octocat")
    }
}
