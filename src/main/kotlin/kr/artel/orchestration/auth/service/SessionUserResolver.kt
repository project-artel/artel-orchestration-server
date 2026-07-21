package kr.artel.orchestration.auth.service

import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Component

/**
 * 검증된 JWT가 가리키는 사용자.
 *
 * 토큰은 "누구인지"에 대해서만 권위를 갖는다. 표시 이름이나 연결된 제공자 같은 프로필은
 * 발급 이후 바뀔 수 있으므로 여기 담지 않고 DB에서 읽는다.
 */
data class SessionUser(val userId: Long)

/**
 * JWT를 세션 사용자 객체로 해석한다. 토큰 형식을 아는 유일한 지점이라,
 * 식별자 규칙이 바뀌어도 이 클래스만 고치면 된다.
 */
@Component
class SessionUserResolver {
    /**
     * 서명 검증을 통과했더라도 sub가 사용자 id 형식이 아니면 유효한 세션이 아니다.
     * 식별자 형식 변경 전에 발급된 토큰(`github:42`)이 브라우저에 남아 있는 경우가 여기 해당한다.
     */
    fun resolve(jwt: Jwt): SessionUser? =
        jwt.subject?.toLongOrNull()?.let(::SessionUser)
}
