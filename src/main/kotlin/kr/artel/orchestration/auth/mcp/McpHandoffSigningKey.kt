package kr.artel.orchestration.auth.mcp

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.JWKSet
import com.nimbusds.jose.jwk.KeyUse
import com.nimbusds.jose.jwk.RSAKey
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.security.KeyFactory
import java.security.interfaces.RSAPrivateCrtKey
import java.security.interfaces.RSAPublicKey
import java.security.spec.PKCS8EncodedKeySpec
import java.security.spec.RSAPublicKeySpec
import java.util.Base64

/** handoff 서명 키의 최소 길이. RS256 키로 2048 bit 미만은 받지 않는다. */
private const val MIN_RSA_KEY_BITS = 2048

/**
 * handoff JWT 를 서명하는 RSA 키(ARTEL-1088, 계약 §1).
 *
 * 키는 `artel.mcp.handoff.private-key`(PKCS#8 PEM)에서 읽는다. 공개키는 개인키의 CRT 성분에서 다시 만들어
 * `GET /.well-known/artel-handoff-jwks.json` 으로 낸다 — 공개키를 따로 설정받으면 둘이 어긋난 설정이 기동을 통과한다.
 * `kid` 는 RFC 7638 thumbprint 다. 키를 바꾸면 `kid` 도 바뀌어 MCP 가 새 키를 JWKS 에서 다시 찾는다.
 *
 * 비어 있으면 `generate-dev-key` 가 켜졌을 때만 기동마다 새 키를 만든다(로컬·테스트). 둘 다 아니면 [key] 가 null 이고
 * handoff 발급은 503 `mcp_handoff_not_configured` 다. 서버는 뜬다 — MCP 를 배포하지 않은 환경이 이 설정 때문에
 * 멈추면 안 된다.
 *
 * 설정된 키를 읽지 못하면 기동이 멈춘다. 이미 MCP 를 쓰는 환경에서 키 오타가 handoff 만 조용히 503 으로 만드는
 * 것보다 낫다. 오류 메시지에 키 내용은 싣지 않는다.
 */
@Component
class McpHandoffSigningKey(properties: McpProperties) {

    private val logger = LoggerFactory.getLogger(McpHandoffSigningKey::class.java)

    /** 서명 키. 설정도 개발 키 생성도 없으면 null 이다. */
    val key: RSAKey? = when {
        properties.handoff.privateKey.isNotBlank() -> parse(properties.handoff.privateKeyPem)
        properties.handoff.generateDevKey -> generate()
        else -> null
    }

    /** JWKS 응답. 키가 없으면 빈 `keys` 다. 공개 성분만 싣는다. */
    fun publicJwkSet(): JWKSet = JWKSet(listOfNotNull(key?.toPublicJWK()))

    private fun generate(): RSAKey {
        logger.warn(
            "artel.mcp.handoff.private-key 가 비어 기동마다 바뀌는 개발용 handoff 서명 키를 만들었다. " +
                "운영에서는 키를 설정하고 artel.mcp.handoff.generate-dev-key 를 끈다."
        )
        return RSAKeyGenerator(MIN_RSA_KEY_BITS)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyIDFromThumbprint(true)
            .generate()
    }

    private fun parse(pem: String): RSAKey {
        val privateKey = try {
            val body = pem
                .replace("-----BEGIN PRIVATE KEY-----", "")
                .replace("-----END PRIVATE KEY-----", "")
                .replace("\\s".toRegex(), "")
            KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(Base64.getDecoder().decode(body)))
                as RSAPrivateCrtKey
        } catch (error: Exception) {
            // 원인 예외의 message 에 키 조각이 실릴 수 있어 cause 를 넘기지 않는다.
            throw IllegalStateException(
                "artel.mcp.handoff.private-key 를 읽을 수 없습니다. RSA PKCS#8 PEM 이어야 합니다 " +
                    "(openssl genpkey -algorithm RSA -pkeyopt rsa_keygen_bits:2048). 원인: ${error.javaClass.simpleName}"
            )
        }
        require(privateKey.modulus.bitLength() >= MIN_RSA_KEY_BITS) {
            "artel.mcp.handoff.private-key 는 ${MIN_RSA_KEY_BITS} bit 이상이어야 합니다."
        }
        val publicKey = KeyFactory.getInstance("RSA")
            .generatePublic(RSAPublicKeySpec(privateKey.modulus, privateKey.publicExponent)) as RSAPublicKey
        return RSAKey.Builder(publicKey)
            .privateKey(privateKey)
            .keyUse(KeyUse.SIGNATURE)
            .algorithm(JWSAlgorithm.RS256)
            .keyIDFromThumbprint()
            .build()
    }
}
