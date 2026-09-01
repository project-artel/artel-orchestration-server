package kr.artel.orchestration.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("artel.auth")
data class AuthProperties(
    val frontendUrl: String,
    val issuer: String,
    val audience: String,
    val jwtSecret: String,
    val accessTokenTtl: Duration = Duration.ofMinutes(15),
    val cookieName: String = "artel_access_token",
    val secureCookie: Boolean = true,
    // refresh 토큰은 access 토큰과 audience를 나눈다. 같으면 refresh 토큰 하나로 API를 그대로
    // 부를 수 있어, 수명만 긴 access 토큰이 된다. 어느 audience의 access 토큰을 만들 수 있는지는
    // 토큰 안의 `for` 클레임이 들고 다닌다.
    val refreshAudience: String = "artel-refresh",
    val refreshTokenTtl: Duration = Duration.ofDays(14),
    val refreshCookieName: String = "artel_refresh_token",
    // SDK는 사람이 창을 띄우기 어려운 환경(빌드 머신, 에디터)에서 돌아 재로그인 비용이 크다.
    val sdkRefreshTokenTtl: Duration = Duration.ofDays(90),
    // SDK 토큰은 브라우저 세션과 audience를 나눈다. 수명이 30일이라 웹 API까지 열어주면
    // 한 번 새어나간 토큰이 한 달짜리 대시보드 세션이 된다.
    val sdkAudience: String = "artel-sdk",
    val sdkTokenTtl: Duration = Duration.ofDays(30),
    // 브라우저가 받아 SDK에 넘기는 일회용 코드의 수명. 사람이 창을 옮기는 시간이면 충분하다.
    val sdkLoginCodeTtl: Duration = Duration.ofMinutes(5),
    // GitHub App 설치 화면으로 보냈다가 돌아오는 `state` 의 audience. 세션 토큰과 갈라 두지 않으면
    // 브라우저 쿠키를 그대로 state 로 내밀 수 있고, 그러면 남의 프로젝트에 설치를 붙이는 길이 열린다.
    val trackerSetupAudience: String = "artel-tracker-setup",
    // 사람이 GitHub 설치 화면에서 저장소를 고르는 시간이면 충분하다. 길게 두면 유출된 state 하나가
    // 그만큼 오래 살아 있는다.
    val trackerSetupStateTtl: Duration = Duration.ofMinutes(15),
    // CORS 로 더 열어 줄 origin(패턴). 아래 FIRST_PARTY_ORIGINS 에 더해지며 그것을 대체하지 않는다.
    // 배포마다 달라지는 출처(프리뷰 배포, 임시 호스트)를 여기에 싣는다. 값이 패턴일 수 있어 CORS 는
    // allowedOrigins 가 아니라 allowedOriginPatterns 로 적용한다.
    val allowedOrigins: List<String> = emptyList()
) {
    /** Frontend origin without a trailing slash, safe to concatenate redirect paths onto. */
    val frontendOrigin: String
        get() = frontendUrl.trimEnd('/')

    /**
     * CORS 허용 목록. FIRST_PARTY_ORIGINS 와 리다이렉트 대상(frontendOrigin)은 설정이 무엇이든 항상 들어간다.
     */
    val corsAllowedOrigins: List<String>
        get() = (FIRST_PARTY_ORIGINS + allowedOrigins.map { it.trimEnd('/') } + frontendOrigin)
            .filter { it.isNotBlank() }
            .distinct()

    init {
        require(jwtSecret.toByteArray(Charsets.UTF_8).size >= 32) {
            "ARTEL_JWT_SECRET must contain at least 32 bytes"
        }
    }

    companion object {
        /**
         * 어느 배포에서든 브라우저로 이 서버를 부르는 ARTEL 소유 호스트. 설정이 아니라 코드가 들고
         * 있어서, 배포의 ARTEL_ALLOWED_ORIGINS 가 무엇이든 이 셋은 CORS 허용 목록에서 빠지지 않는다.
         *
         * 설정으로만 두었을 때 실제로 빠졌다. ARTEL-295 가 admin.artel.kr 을 application.yml 기본값에
         * 넣었지만, 환경변수는 그 기본값에 더해지는 것이 아니라 통째로 대체한다. stage 의 .env 가
         * 좁은 값을 들고 있어 그 수정은 배포에서 무효였고, admin-page 는 다시 CORS 로 막혔다(ARTEL-702).
         *
         * 배포마다 다른 프런트엔드(stage 의 home.stage.artel.kr)는 여기 없다. 그것은 그 배포의
         * frontendUrl 로 이미 항상 허용되고, 여기 적으면 다른 배포에서까지 열린다.
         *
         * 하위 도메인 전체를 한 패턴(`*.artel.kr`)으로 묶지 않고 호스트를 하나씩 적는다. 묶으면
         * 하위 도메인을 하나 잃었을 때 그것이 곧 자격증명 실린 CORS origin 이 된다. 호스트가 늘면
         * 여기에 줄을 더한다.
         */
        val FIRST_PARTY_ORIGINS = listOf(
            "https://artel.kr",
            "https://www.artel.kr",
            "https://admin.artel.kr"
        )
    }
}
