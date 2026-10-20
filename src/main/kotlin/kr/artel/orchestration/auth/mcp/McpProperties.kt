package kr.artel.orchestration.auth.mcp

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * `artel-macro-mcp`(원격 MCP 서버 겸 OAuth 2.1 인가 서버)와 맞물리는 설정(ARTEL-1088). 계약은
 * `.plan/general/2026-10-15-developer-macro-mcp.md` 의 "레포 사이 계약" 이다.
 *
 * 두 방향이 있다.
 * - **access JWT**: MCP 가 발급하고 이 서버가 검증한다. [issuer] 와 [jwksUri] 가 둘 다 있을 때만 켜진다.
 * - **handoff JWT**: 이 서버가 발급하고 MCP 가 검증한다. [Handoff] 가 그 설정이다.
 *
 * 둘 다 기본은 꺼짐이다. MCP 를 배포하지 않은 환경에서 이 설정이 비어 있어도 서버는 뜨고, 기존 인증은 한 줄도
 * 달라지지 않는다.
 */
@ConfigurationProperties("artel.mcp")
data class McpProperties(
    /** MCP 서버의 공개 주소(`https://mcp.artel.kr`). handoff 응답의 `redirectUrl` 이 이 아래 `/oauth/handoff` 다. */
    val baseUrl: String = "",

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

    val handoff: Handoff = Handoff(),
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

    /**
     * 로그인 넘겨주기(handoff) JWT 설정. 사용자가 home 에서 MCP 연결을 허용하면 이 서버가 2분짜리 JWT 를 서명해
     * MCP `/oauth/handoff` 로 보낸다. MCP 는 `GET /.well-known/artel-handoff-jwks.json` 의 공개키로 검증한다.
     */
    data class Handoff(
        /**
         * handoff JWT 의 `iss`. MCP 서버의 `HANDOFF_ISSUER` 와 글자 그대로 같아야 한다 — MCP 는 이 값을 기대값으로 두고
         * 다르면 handoff 를 거절한다. MCP 쪽 `HANDOFF_ISSUER` 가 비면 그쪽 `ORCHESTRATION_BASE_URL` 이 기대값이므로, 이
         * 서버의 공개 base URL(`https://api.artel.kr`)을 쓰는 것이 권장값이다.
         */
        val issuer: String = "",

        /** handoff JWT 의 `aud`. */
        val audience: String = "artel-macro-mcp",

        /** handoff JWT 의 수명. 브라우저가 MCP 로 한 번 이동하는 시간이면 충분하다. */
        val ttl: Duration = Duration.ofMinutes(2),

        /**
         * 서명 키. RSA 2048 이상의 PKCS#8 PEM 이고 RS256 으로 서명한다. 환경변수 한 줄로 실을 수 있도록 `\n`
         * 이스케이프를 실제 개행으로 되돌려 읽는다(`GitHubAppProperties.privateKey` 와 같다).
         * 만들기: `openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048`.
         */
        val privateKey: String = "",

        /**
         * [privateKey] 가 비었을 때 기동마다 새 키를 만들지. 로컬과 테스트 전용이다 — 재기동하면 키가 바뀌어 발급해
         * 둔 handoff JWT 가 전부 무효가 되고, replica 가 여럿이면 replica 마다 키가 다르다. 운영에서는 끈다.
         */
        val generateDevKey: Boolean = false,
    ) {
        init {
            require(!ttl.isNegative && !ttl.isZero) { "artel.mcp.handoff.ttl 은 0 보다 커야 합니다." }
            require(audience.isNotBlank()) { "artel.mcp.handoff.audience 는 비어 있을 수 없습니다." }
        }

        /** 환경변수로 실려 온 `\n` 이스케이프를 되돌린 PEM 원문. */
        val privateKeyPem: String
            get() = privateKey.replace("\\n", "\n")
    }
}
