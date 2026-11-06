package kr.artel.orchestration.auth.mcp

import com.nimbusds.jose.JOSEObjectType
import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.JWSHeader
import com.nimbusds.jose.crypto.RSASSASigner
import com.nimbusds.jwt.JWTClaimsSet
import com.nimbusds.jwt.SignedJWT
import kr.artel.orchestration.common.error.ForbiddenException
import kr.artel.orchestration.common.error.UpstreamUnavailableException
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant
import java.util.Date
import java.util.UUID

/** handoff JWT 에서 MCP 의 저장된 authorization 요청을 가리키는 claim. */
const val HANDOFF_REQUEST_ID_CLAIM = "request_id"

/**
 * 로그인 넘겨주기(handoff) JWT 를 발급한다(ARTEL-1088, 계약 §1).
 *
 * 사용자가 home 의 `/mcp/connect` 에서 MCP 연결을 허용하면 home 이 `POST /api/mcp/handoffs` 를 부르고, 이 서버가
 * "이 브라우저의 Artel 사용자는 `sub` 이고, MCP 의 요청 `request_id` 에 대해 허용했다" 를 2분짜리 JWT 로 서명한다.
 * MCP `/oauth/handoff` 는 그 JWT 를 `/.well-known/artel-handoff-jwks.json` 으로 검증하고 `jti` 를 한 번만 받는다.
 *
 * MCP 는 이 서버의 세션 JWT 를 받지 않고, 이 서버는 MCP 의 사용자 저장소를 모른다. 둘을 잇는 것은 이 JWT 하나다.
 */
@Service
class McpHandoffService(
    private val properties: McpProperties,
    private val signingKey: McpHandoffSigningKey,
    private val clock: Clock,
) {

    /** handoff JWT 를 서명하고 브라우저가 이동할 MCP 주소를 돌려준다. */
    fun issue(userId: Long, requestId: String): String {
        val key = signingKey.key
        if (key == null || properties.baseUrl.isBlank() || properties.handoff.issuer.isBlank()) {
            throw McpHandoffNotConfiguredException()
        }
        val issuedAt = Instant.now(clock)
        val claims = JWTClaimsSet.Builder()
            .issuer(properties.handoff.issuer)
            .audience(properties.handoff.audience)
            .subject(userId.toString())
            .claim(HANDOFF_REQUEST_ID_CLAIM, requestId)
            .jwtID(UUID.randomUUID().toString())
            .issueTime(Date.from(issuedAt))
            .expirationTime(Date.from(issuedAt.plus(properties.handoff.ttl)))
            .build()
        val header = JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.keyID).type(JOSEObjectType.JWT).build()
        val token = SignedJWT(header, claims).apply { sign(RSASSASigner(key)) }.serialize()
        // JWT 는 base64url 세 덩어리와 `.` 뿐이라 쿼리 값으로 인코딩할 글자가 없다.
        return "${properties.baseUrl.trimEnd('/')}/oauth/handoff?token=$token"
    }
}

/**
 * handoff 서명 키나 MCP 주소가 설정되지 않았다. `tracker_not_configured` 와 같은 503 이다 — 사용자가 고칠 것이 아니라
 * 이 서버의 배포 설정이 빠졌다.
 */
class McpHandoffNotConfiguredException :
    UpstreamUnavailableException("MCP handoff is not configured", code = "mcp_handoff_not_configured")

/**
 * 브라우저 세션이 아닌 자격증명으로 handoff 를 요청했다. CLI 토큰이나 MCP access JWT 로 handoff 를 만들 수 있으면
 * 새어 나간 token 하나로 MCP 에 새 grant 를 열어 두고 원본 폐기를 피할 수 있다(`CliTokenCannotIssueException` 과 같은 이유).
 */
class McpHandoffRequiresSessionException :
    ForbiddenException("MCP 연결은 브라우저 로그인으로만 허용할 수 있습니다.", code = "mcp_handoff_requires_session")
