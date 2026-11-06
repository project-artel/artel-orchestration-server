package kr.artel.orchestration.auth.config

import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Condition
import org.springframework.context.annotation.ConditionContext
import org.springframework.context.annotation.Conditional
import org.springframework.context.annotation.Configuration
import org.springframework.core.type.AnnotatedTypeMetadata
import org.springframework.security.config.oauth2.client.CommonOAuth2Provider
import org.springframework.security.oauth2.client.registration.InMemoryReactiveClientRegistrationRepository
import org.springframework.security.oauth2.client.registration.ReactiveClientRegistrationRepository

/** `OAuthIdentityResolver` 가 매퍼를 고르는 registration id 와 같아야 한다. */
const val GITHUB_REGISTRATION_ID = "github"

/**
 * GitHub client registration 을 직접 만든다.
 *
 * `spring.security.oauth2.client.registration.github` 로 두면 Boot 의 `OAuth2ClientProperties` 가
 * client id 가 비었을 때 기동을 멈춘다. 그래서 그 키를 쓰지 않고, 두 값이 다 있을 때만 이 빈을
 * 만든다. 이 빈이 없으면 `SecurityConfig` 가 `oauth2Login` 을 붙이지 않는다.
 */
@Configuration
@Conditional(GitHubOAuthConfiguredCondition::class)
class GitHubOAuthClientConfig {

    @Bean
    fun reactiveClientRegistrationRepository(
        properties: GitHubOAuthProperties
    ): ReactiveClientRegistrationRepository =
        InMemoryReactiveClientRegistrationRepository(
            CommonOAuth2Provider.GITHUB.getBuilder(GITHUB_REGISTRATION_ID)
                .clientId(properties.clientId)
                .clientSecret(properties.clientSecret)
                .scope("read:user", "user:email")
                .build()
        )
}

/**
 * 빈을 만들기 전에 판단해야 해서 [GitHubOAuthProperties] 빈을 읽지 못하고 같은 두 키를 문자열로
 * 읽는다(`.agents/docs/configuration.md` 의 예외). `@ConditionalOnProperty` 로는 "둘 다 비어 있지
 * 않다" 를 쓸 수 없다.
 */
class GitHubOAuthConfiguredCondition : Condition {
    override fun matches(context: ConditionContext, metadata: AnnotatedTypeMetadata): Boolean {
        val environment = context.environment
        val clientId = environment.getProperty("artel.auth.github.client-id").orEmpty()
        val clientSecret = environment.getProperty("artel.auth.github.client-secret").orEmpty()
        return clientId.isNotBlank() && clientSecret.isNotBlank()
    }
}
