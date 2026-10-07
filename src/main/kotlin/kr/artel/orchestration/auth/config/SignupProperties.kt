package kr.artel.orchestration.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 이메일과 비밀번호 가입(`POST /api/auth/signup`) 설정.
 *
 * 첫 가입은 이 값과 무관하게 언제나 열려 있다 — 첫 가입자가 ADMIN 이 되는 것이 설치를 시작하는
 * 방법이다. 그 뒤로는 [open] 이 true 일 때만 누구나 가입할 수 있고, false 면 ADMIN 이
 * `POST /api/admin/users` 로 계정을 만든다.
 *
 * GitHub OAuth 로 처음 들어오는 사람은 이 값의 영향을 받지 않는다. stage 와 운영이 GitHub 가입을
 * 그대로 받고 있어서, 이 값으로 그것까지 닫으면 배포만으로 가입이 멈춘다.
 */
@ConfigurationProperties("artel.auth.signup")
data class SignupProperties(
    val open: Boolean = false
)
