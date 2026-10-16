package kr.artel.orchestration.auth.mcp

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `artel-macro-mcp`(원격 MCP 서버 겸 OAuth 2.1 인가 서버)와 맞물리는 설정(ARTEL-1088). 계약은
 * `.plan/general/2026-10-15-developer-macro-mcp.md` 의 "레포 사이 계약" 이다.
 *
 * **access JWT**: MCP 가 발급하고 이 서버가 검증한다. [issuer] 와 [jwksUri] 가 둘 다 있을 때만 켜진다.
 *
 * 기본은 꺼짐이다. MCP 를 배포하지 않은 환경에서 이 설정이 비어 있어도 서버는 뜨고, 기존 인증은 한 줄도
 * 달라지지 않는다.
 */
@ConfigurationProperties("artel.mcp")
data class McpProperties(
    /**
     * MCP access JWT 의 `iss`. 요청의 bearer 가 MCP access JWT 인지는 이 값과 `iss` 가 같은지로 가른다
     * (`McpAccessTokenDetector`). 그래서 `artel.auth.issuer` 와 같으면 안 된다.
     */
    val issuer: String = "",

    /** MCP 서명 키의 공개키 주소(`{MCP}/.well-known/jwks.json`). */
    val jwksUri: String = "",

    /** MCP access JWT 의 `aud` 에 있어야 하는 값. */
    val audience: String = "artel-orchestration",

    /** JWKS 를 받아 오는 요청의 응답 상한. 첫 요청과 모르는 `kid` 를 만난 요청이 이만큼 늦어질 수 있다. */
    val jwksTimeout: Duration = Duration.ofSeconds(5),
) {
    /** MCP access JWT 인증이 켜졌는지. */
    val accessTokenEnabled: Boolean
        get() = issuer.isNotBlank() && jwksUri.isNotBlank()

    init {
        require(issuer.isBlank() == jwksUri.isBlank()) {
            "artel.mcp.issuer 와 artel.mcp.jwks-uri 는 둘 다 주거나 둘 다 비워야 합니다."
        }
        require(audience.isNotBlank()) { "artel.mcp.audience 는 비어 있을 수 없습니다." }
    }
}
