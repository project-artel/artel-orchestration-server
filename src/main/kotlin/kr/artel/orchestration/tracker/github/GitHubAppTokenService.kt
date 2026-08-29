package kr.artel.orchestration.tracker.github

import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty
import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kr.artel.orchestration.tracker.client.TrackerNotConfiguredException
import kr.artel.orchestration.tracker.config.GitHubAppProperties
import org.springframework.stereotype.Component
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.awaitBody
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateKey
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Instant
import java.util.Base64
import java.util.Date
import java.util.concurrent.ConcurrentHashMap

/** GitHub 이 돌려주는 installation access token. 만료 시각까지 함께 온다. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class GitHubInstallationTokenResponse(
    val token: String,
    @param:JsonProperty("expires_at") val expiresAt: Instant
)

/** 발급받아 메모리에 들고 있는 token 한 건. */
private data class CachedInstallationToken(val token: String, val expiresAt: Instant)

/**
 * App JWT 로 installation access token 을 받아 메모리에 cache 한다.
 *
 * **DB 에는 어떤 비밀도 쓰지 않는다.** 저장하는 것은 `installation_ref` 뿐이고, 실제 자격증명은 매번
 * 여기서 만들어진다. 재기동하면 cache 가 비고 다음 호출에서 한 번 더 발급받는다 — 비밀을 남기지
 * 않기 위해 치르는 비용이며, 의도된 것이다.
 *
 * private key 파싱은 기동이 아니라 **첫 사용**에서 한 번만 한다. 기동에서 검증하면 키 오타 하나가
 * 서버를 못 뜨게 해 tracker 를 쓰지 않는 QA 경로까지 죽인다. 대신 오타는 연동 endpoint 를 부르는
 * 순간 [TrackerNotConfiguredException] 으로 드러나고, 원인은 `cause` 로 로그에 남는다.
 */
@Component
class GitHubAppTokenService(
    private val properties: GitHubAppProperties,
    private val webClient: WebClient,
    private val clock: Clock
) {

    private val cache = ConcurrentHashMap<String, CachedInstallationToken>()

    /**
     * installation 하나당 발급 mutex. 없으면 같은 installation 에 대한 동시 요청이 각자 token 을
     * 발급받아, GitHub 의 발급 rate limit 을 근거 없이 태운다.
     */
    private val locks = ConcurrentHashMap<String, Mutex>()

    private val privateKey: RSAPrivateKey by lazy { parsePrivateKey() }

    /** 유효한 token. 만료 [GitHubAppProperties.tokenRefreshSkew] 전이면 다시 발급받는다. */
    suspend fun installationToken(installationRef: String): String {
        requireConfigured()
        cache[installationRef]?.takeIf { it.isFresh() }?.let { return it.token }

        return locks.computeIfAbsent(installationRef) { Mutex() }.withLock {
            // lock 을 기다리는 동안 다른 요청이 이미 발급했을 수 있다.
            cache[installationRef]?.takeIf { it.isFresh() }?.let { return@withLock it.token }
            val issued = requestInstallationToken(installationRef)
            cache[installationRef] = CachedInstallationToken(issued.token, issued.expiresAt)
            issued.token
        }
    }

    /** App 자체를 증명하는 JWT. installation token 발급과 App 수준 조회에만 쓴다. */
    fun appJwt(): String {
        requireConfigured()
        val now = Instant.now(clock)
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.appId)
            // GitHub 이 서버 시계가 앞선 경우를 거절하므로 iat 를 조금 뒤로 민다.
            .issueTime(Date.from(now.minusSeconds(60)))
            // GitHub 이 받는 상한은 10분이다. 여유를 두고 9분으로 끊는다.
            .expirationTime(Date.from(now.plusSeconds(540)))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).build()
        return SignedJWT(header, claims).apply { sign(RSASSASigner(privateKey)) }.serialize()
    }

    private fun CachedInstallationToken.isFresh(): Boolean =
        Instant.now(clock).isBefore(expiresAt.minus(properties.tokenRefreshSkew))

    private suspend fun requestInstallationToken(installationRef: String): GitHubInstallationTokenResponse =
        try {
            webClient.post()
                .uri("${properties.apiBaseUrl}/app/installations/$installationRef/access_tokens")
                .header("Authorization", "Bearer ${appJwt()}")
                .retrieve()
                .awaitBody<GitHubInstallationTokenResponse>()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 5xx 라 message 는 클라이언트로 나가지 않는다. cause 가 원인을 로그로 나른다.
            throw TrackerNotConfiguredException(
                "GitHub installation token 발급에 실패했습니다(installation=$installationRef).",
                error
            )
        }

    private fun requireConfigured() {
        if (!properties.configured) {
            throw TrackerNotConfiguredException("GitHub App이 설정되지 않았습니다.")
        }
    }

    private fun parsePrivateKey(): RSAPrivateKey {
        val body = properties.privateKeyPem
            .replace("-----BEGIN RSA PRIVATE KEY-----", "")
            .replace("-----END RSA PRIVATE KEY-----", "")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .filterNot { it.isWhitespace() }
        return try {
            val decoded = Base64.getDecoder().decode(body)
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(decoded)) as RSAPrivateKey
        } catch (error: Exception) {
            // GitHub 이 내려주는 원본은 PKCS#1(BEGIN RSA PRIVATE KEY)이라 그대로는 못 읽는다.
            // 변환 명령을 오류에 적어 두지 않으면 원인을 찾는 데 시간이 든다.
            throw TrackerNotConfiguredException(
                "GitHub App private key를 읽을 수 없습니다. PKCS#8 PEM이어야 합니다" +
                    "(openssl pkcs8 -topk8 -inform PEM -outform PEM -nocrypt -in key.pem).",
                error
            )
        }
    }
}
