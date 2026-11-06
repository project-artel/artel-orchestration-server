package kr.artel.orchestration.auth.service

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder
import org.springframework.stereotype.Component

/**
 * BCrypt 해시와 대조. 한 번에 수십 ms 를 CPU 로 쓰므로 event loop 가 아니라 [Dispatchers.Default] 에서
 * 돈다 — Netty 스레드에서 돌리면 그동안 그 스레드의 다른 요청이 전부 멈춘다.
 */
@Component
class PasswordHasher {
    private val encoder = BCryptPasswordEncoder()

    /**
     * 존재하지 않는 계정으로 로그인할 때 대조할 해시. 계정이 없다고 대조를 건너뛰면 응답 시간이
     * 짧아져, 시간만 재도 그 이메일로 가입한 계정이 있는지 알 수 있다.
     */
    private val unknownAccountHash = encoder.encode("artel-unknown-account")

    suspend fun hash(password: String): String =
        withContext(Dispatchers.Default) { encoder.encode(password) }

    suspend fun matches(password: String, passwordHash: String): Boolean =
        withContext(Dispatchers.Default) { encoder.matches(password, passwordHash) }

    /** 계정이 없을 때도 같은 시간을 쓰고 false 를 돌려준다. */
    suspend fun matchesNothing(password: String): Boolean {
        matches(password, unknownAccountHash)
        return false
    }
}
