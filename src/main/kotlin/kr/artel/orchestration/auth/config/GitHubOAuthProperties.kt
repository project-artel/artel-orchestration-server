package kr.artel.orchestration.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * GitHub OAuth 로그인 앱. 두 값이 다 비어 있지 않을 때만 GitHub 로그인이 켜진다.
 *
 * 직접 설치하는 사람은 GitHub OAuth 앱을 등록하지 않아도 이메일 로그인으로 쓸 수 있어야 해서,
 * 이 두 값은 기동에 필요하지 않다. 비어 있으면 client registration 을 만들지 않고, 그러면
 * `/oauth2/authorization/github` 는 처리할 곳이 없어 404 다.
 *
 * ⚠️ [GitHubOAuthConfiguredCondition] 이 같은 두 키를 문자열로 읽는다. 키 이름을 바꾸면 둘을 함께
 * 바꾼다.
 */
@ConfigurationProperties("artel.auth.github")
data class GitHubOAuthProperties(
    val clientId: String = "",
    val clientSecret: String = "",
    /**
     * 처음 보는 GitHub 계정에 새 사용자를 만드는지. `ARTEL_GITHUB_SIGNUP_OPEN`.
     *
     * 기본값이 true 인 것은 stage 와 운영이 지금 그렇게 돌고 있어서다. false 면 이미 있는 사용자와
     * ADMIN 이 이메일로 만들어 둔 계정만 GitHub 으로 들어오고, 나머지는 `/login?error=signup_closed`
     * 로 돌아간다. 이메일 가입의 `ARTEL_SIGNUP_OPEN` 과는 따로 움직인다.
     */
    val signupOpen: Boolean = true
) {
    val enabled: Boolean
        get() = clientId.isNotBlank() && clientSecret.isNotBlank()

    /** 비밀값이 로그로 나가지 않도록 client secret 을 뺀다. */
    override fun toString(): String = "GitHubOAuthProperties(clientId=$clientId, enabled=$enabled, signupOpen=$signupOpen)"
}
