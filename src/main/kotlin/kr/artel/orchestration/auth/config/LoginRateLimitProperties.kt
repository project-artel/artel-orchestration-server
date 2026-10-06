package kr.artel.orchestration.auth.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `POST /api/auth/login` 의 실패 횟수 제한. 이메일 한도와 주소 한도가 따로 있고, 둘 다 [window] 안의
 * 실패를 센다.
 *
 * 카운터는 프로세스 메모리에 있다. 서버를 다시 띄우면 비워지고, 인스턴스가 여럿이면 인스턴스마다 따로
 * 센다. 직접 설치하는 서버는 한 대라 그것으로 충분하다.
 */
@ConfigurationProperties("artel.auth.login-rate-limit")
data class LoginRateLimitProperties(
    /** 한 이메일의 실패 한도. 넘으면 맞는 비밀번호도 429 다. 한 계정에 비밀번호를 쏟아붓는 것을 막는다. */
    val maxFailures: Int = 10,
    /**
     * 한 클라이언트 주소의 실패 한도. 이메일 한도보다 훨씬 높게 둔다.
     *
     * Docker 기본 설치에서는 userland proxy 가 진짜 주소를 가려 모든 클라이언트가 같은 bridge 주소로
     * 들어온다. 그러면 이 한도는 사람마다가 아니라 서버 전체에 걸리는 브레이크가 되고, 낮게 두면 누구
     * 하나의 실패가 모두를 막는다. 그래서 이 한도에 닿아도 맞는 비밀번호는 통과한다(`LoginAttemptLimiter`).
     */
    val addressMaxFailures: Int = 100,
    val window: Duration = Duration.ofMinutes(15),
    /**
     * 메모리에 들고 있는 키 수의 상한. 넘으면 창이 끝난 키부터 지운다. 주소를 바꿔 가며 보내는 요청이
     * 메모리를 끝없이 늘리지 못하게 한다.
     */
    val maxTrackedKeys: Int = 100_000
) {
    init {
        require(maxFailures > 0) { "artel.auth.login-rate-limit.max-failures must be positive" }
        require(addressMaxFailures > 0) { "artel.auth.login-rate-limit.address-max-failures must be positive" }
        require(!window.isZero && !window.isNegative) { "artel.auth.login-rate-limit.window must be positive" }
        require(maxTrackedKeys > 0) { "artel.auth.login-rate-limit.max-tracked-keys must be positive" }
    }
}
