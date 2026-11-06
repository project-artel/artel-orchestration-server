package kr.artel.orchestration.auth.cli

import kr.artel.orchestration.auth.entity.CliTokenEntity
import kr.artel.orchestration.auth.service.SessionUserResolver
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import java.time.Instant

/**
 * 직접 만든 `Jwt` 가 기존 resolver 두 개를 그대로 통과하는지. 그것이 이 작업의 축이다 —
 * 통과하지 않으면 `@CurrentUserId` 25 곳과 `@AuthenticationPrincipal Jwt` 8 개 컨트롤러를
 * 전부 고쳐야 한다.
 */
class CliTokenPrincipalTest {

    private val sessionUserResolver = SessionUserResolver()

    @Test
    fun `resolves to the token owner's user id`() {
        val principal = cliTokenPrincipal(row(appUserId = 4242))

        assertThat(sessionUserResolver.resolve(principal)?.userId).isEqualTo(4242L)
    }

    @Test
    fun `carries no raw token in its token value`() {
        val principal = cliTokenPrincipal(row(tokenHash = "a".repeat(64)))

        // principal 은 로그와 오류 속성을 타고 어디로든 나간다. 원문도, 조회 키인 해시도 실리지 않는다.
        assertThat(principal.tokenValue).isEqualTo("cli-token:7")
        assertThat(principal.tokenValue).doesNotContain(CLI_TOKEN_PREFIX)
        assertThat(principal.tokenValue).doesNotContain("a".repeat(64))
    }

    @Test
    fun `marks the credential kind as cli`() {
        val principal = cliTokenPrincipal(row())

        // 자격증명 종류를 봐야 하는 코드는 principal 의 타입이 아니라 이 claim 을 본다.
        assertThat(principal.getClaimAsString(CREDENTIAL_CLAIM)).isEqualTo(CREDENTIAL_CLI)
    }

    @Test
    fun `mints a principal for a token that never expires`() {
        val principal = cliTokenPrincipal(row(expiresAt = null))

        assertThat(principal.expiresAt).isNull()
        assertThat(sessionUserResolver.resolve(principal)).isNotNull()
    }

    private fun row(
        appUserId: Long = 1,
        tokenHash: String = "0".repeat(64),
        expiresAt: Instant? = Instant.parse("2026-12-03T00:00:00Z")
    ) = CliTokenEntity(
        id = 7,
        appUserId = appUserId,
        name = "노트북",
        tokenHash = tokenHash,
        scope = "full",
        createdAt = Instant.parse("2026-09-03T00:00:00Z"),
        expiresAt = expiresAt
    )
}
