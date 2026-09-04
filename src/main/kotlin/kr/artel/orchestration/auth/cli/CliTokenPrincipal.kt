package kr.artel.orchestration.auth.cli

import kr.artel.orchestration.auth.entity.CliTokenEntity
import org.springframework.security.oauth2.jwt.Jwt

/**
 * CLI 토큰 행을 컨트롤러가 받는 principal 로 바꾼다.
 *
 * 새 principal 타입을 만들지 않고 `Jwt` 를 직접 만든다. `Jwt` 는 서명 검증의 산물이 아니라 값
 * 객체일 뿐이고, `SessionUserResolver` 는 `subject` 하나만,
 * `CurrentUserIdArgumentResolver` 는 타입만 본다. `subject` 에 `app_user.id` 를 넣으면 `@CurrentUserId`
 * 를 쓰는 25 개 파일과 `@AuthenticationPrincipal Jwt` 를 쓰는 컨트롤러 8 개가 한 줄도 바뀌지 않는다.
 *
 * 대가는 서명된 JWT 가 아닌 것이 `Jwt` 타입으로 흐른다는 점이다. 자격증명 종류를 봐야 하는 코드는
 * 타입이 아니라 [CREDENTIAL_CLAIM] 을 본다.
 *
 * `tokenValue` 에 토큰 원문을 넣지 않는다. principal 은 로그, 오류 속성, `@AuthenticationPrincipal`
 * 덤프를 타고 어디로든 나가는 값이라, 원문을 실으면 "원문은 저장하지 않는다"를 지켜 놓고 그것을
 * 로그로 흘리는 꼴이 된다. `cli-token:{id}` 는 그 자체로는 아무것도 열지 못하는 손잡이다.
 */
fun cliTokenPrincipal(row: CliTokenEntity): Jwt =
    Jwt.withTokenValue("cli-token:${row.id}")
        // headers 가 비어 있으면 Jwt 생성자가 거절한다. 값 자체에는 의미가 없고, 덤프에서
        // 서명된 JWT 와 구분되는 표시로만 쓴다.
        .header("typ", "cli-token")
        .subject(row.appUserId.toString())
        .claim(CREDENTIAL_CLAIM, CREDENTIAL_CLI)
        .issuedAt(row.createdAt)
        .also { builder -> row.expiresAt?.let(builder::expiresAt) }
        .build()
