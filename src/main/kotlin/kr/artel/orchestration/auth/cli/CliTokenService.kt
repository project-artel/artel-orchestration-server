package kr.artel.orchestration.auth.cli

import kotlinx.coroutines.flow.Flow
import kr.artel.orchestration.auth.entity.CliTokenEntity
import kr.artel.orchestration.auth.entity.MAX_CLI_TOKEN_NAME_LENGTH
import kr.artel.orchestration.auth.repository.CliTokenRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.security.SecureRandom
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

/** 토큰의 원본 바이트 길이. base64url 로 43자가 되어 접두사까지 49자다. */
private const val TOKEN_BYTES = 32

/**
 * `expires_in_days` 의 상한. 3650 을 적어 사실상 영구 토큰을 만들면서 만료가 있는 척하는 것을
 * 막는다. 영구가 필요하면 null 을 명시적으로 골라야 한다.
 */
private const val MAX_EXPIRES_IN_DAYS = 365

/**
 * `last_used_at` 의 해상도. 이 시간 안에 다시 들어온 요청은 UPDATE 를 내지 않는다.
 *
 * 요청마다 쓰면 읽기 전용 요청이 전부 쓰기가 된다. 얻는 것은 "마지막으로 언제 썼나"이고, 그
 * 질문에 5분 오차는 아무 차이를 만들지 않는다. "몇 번 썼나"에는 애초에 답하지 않는다.
 */
private val LAST_USED_RESOLUTION: Duration = Duration.ofMinutes(5)

/** 이번에 발급하는 토큰이 여는 범위. 좁은 scope 가 생기기 전까지 모든 행이 이 값이다. */
private const val DEFAULT_SCOPE = "full"

/**
 * 발급 직후에만 존재하는 쌍. [token] 원문은 여기서 201 응답으로 한 번 나가고 다시는 나오지 않는다.
 */
data class IssuedCliToken(val row: CliTokenEntity, val token: String)

/**
 * CLI 토큰의 발급·목록·폐기와, 원문을 사용자로 바꾸는 인증.
 *
 * 이 서비스가 상태 있는 자격증명을 다루는 유일한 자리다. `JwtService` 와 `RefreshTokenService`
 * 는 그대로 상태 없는 JWT 를 낸다 — 이 작업은 그 둘을 바꾸지 않는다.
 */
@Service
class CliTokenService(
    private val cliTokenRepository: CliTokenRepository,
    private val clock: Clock
) {
    private val random = SecureRandom()

    /**
     * 새 토큰을 만들어 해시만 저장하고, 원문을 함께 돌려준다.
     *
     * [expiresInDays] 가 null 이면 만료가 없다. 값이 있으면 `1..365` 이고, 벗어나면 400 이다.
     */
    suspend fun issue(userId: Long, rawName: String?, expiresInDays: Int?): IssuedCliToken {
        val name = normalizeName(rawName)
        val now = Instant.now(clock)
        val expiresAt = expiresInDays?.let { days ->
            if (days !in 1..MAX_EXPIRES_IN_DAYS) throw InvalidExpiresInDaysException()
            now.plus(Duration.ofDays(days.toLong()))
        }

        val token = newToken()
        val saved = cliTokenRepository.save(
            CliTokenEntity(
                appUserId = userId,
                name = name,
                tokenHash = hash(token),
                scope = DEFAULT_SCOPE,
                createdAt = now,
                expiresAt = expiresAt
            )
        )
        return IssuedCliToken(saved, token)
    }

    /** 자기 토큰 전부. 폐기된 것도 낸다 — 폐기했다는 사실이 화면의 정보다. */
    fun list(userId: Long): Flow<CliTokenEntity> = cliTokenRepository.findAllByOwner(userId)

    /**
     * 토큰을 폐기한다. 없는 id·남의 토큰·이미 폐기된 토큰을 404 하나로 답한다.
     *
     * 셋을 가르면 어느 id 가 존재하는지를 알려 주게 된다. 그래서 같은 토큰을 두 번 지우면 두
     * 번째는 404 다.
     */
    suspend fun revoke(userId: Long, id: Long) {
        if (cliTokenRepository.revoke(id, userId, Instant.now(clock)) == 0) {
            throw CliTokenNotFoundException()
        }
    }

    /**
     * 토큰 원문을 principal 로 바꾼다. 살아 있지 않으면 null 이다.
     *
     * `last_used_at` 갱신은 요청을 막는다(await). 별도 scope 로 띄우면 WebFlux 에서는 요청
     * context 가 취소되는 순간 쓰기가 조용히 사라지고, "마지막 사용 시각이 갱신된다"는 테스트를
     * sleep 없이 쓸 수 없게 된다. 비용은 5분에 한 번, primary key 한 건의 UPDATE 다.
     */
    suspend fun authenticate(rawToken: String): Jwt? {
        val now = Instant.now(clock)
        val row = cliTokenRepository.findUsableByTokenHash(hash(rawToken), now) ?: return null
        touchLastUsed(row, now)
        return cliTokenPrincipal(row)
    }

    /** 방금 읽은 행으로 먼저 거른다. 5분 안의 두 번째 요청은 질의를 아예 내지 않는다. */
    private suspend fun touchLastUsed(row: CliTokenEntity, now: Instant) {
        val staleBefore = now.minus(LAST_USED_RESOLUTION)
        val lastUsedAt = row.lastUsedAt
        if (lastUsedAt != null && !lastUsedAt.isBefore(staleBefore)) return
        cliTokenRepository.touchLastUsed(requireNotNull(row.id), now, staleBefore)
    }

    /**
     * 앞뒤 공백을 지운다. 이름은 목록에서 어느 토큰을 폐기할지 고르는 유일한 단서라 비울 수 없다.
     */
    private fun normalizeName(rawName: String?): String {
        val trimmed = rawName?.trim()
        if (trimmed.isNullOrEmpty()) {
            throw BadRequestException("토큰 이름은 비울 수 없습니다.", code = "invalid_cli_token_name")
        }
        if (trimmed.length > MAX_CLI_TOKEN_NAME_LENGTH) {
            throw BadRequestException(
                "토큰 이름은 $MAX_CLI_TOKEN_NAME_LENGTH 자를 넘을 수 없습니다.",
                code = "invalid_cli_token_name"
            )
        }
        return trimmed
    }

    private fun newToken(): String {
        val bytes = ByteArray(TOKEN_BYTES).also(random::nextBytes)
        return CLI_TOKEN_PREFIX + Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
    }

    /**
     * 원문을 저장하지 않으므로 조회 키는 해시다. 해시하는 대상은 접두사를 포함한 전체 문자열이라,
     * 조회 키가 사용자가 붙여 넣는 값과 정확히 같고 서버가 문자열을 자를 일이 없다.
     */
    private fun hash(token: String): String =
        MessageDigest.getInstance("SHA-256")
            .digest(token.toByteArray())
            .joinToString("") { "%02x".format(it) }
}

/** 없는 id, 남의 토큰, 이미 폐기된 토큰. 셋을 가르지 않는다. */
class CliTokenNotFoundException :
    NotFoundException("토큰을 찾을 수 없습니다.", code = "cli_token_not_found")

/** `1..365` 를 벗어난 값. 만료가 없는 토큰은 이 값이 아니라 null 로 고른다. */
class InvalidExpiresInDaysException :
    BadRequestException(
        "만료 기간은 1일 이상 ${MAX_EXPIRES_IN_DAYS}일 이하이거나, 만료 없음이어야 합니다.",
        code = "invalid_expires_in_days"
    )
