package kr.artel.orchestration.auth.mcp

import com.nimbusds.jose.JWSAlgorithm
import com.nimbusds.jose.jwk.KeyUse
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.interfaces.RSAPublicKey
import java.util.Base64

class McpHandoffSigningKeyTest {

    @Test
    fun `PKCS#8 PEM 을 읽고 공개키는 개인키에서 다시 만든다`() {
        val keyPair = rsaKeyPair(2048)

        val signingKey = McpHandoffSigningKey(properties(privateKey = pemOf(keyPair)))

        val key = signingKey.key!!
        assertThat(key.toRSAPublicKey().modulus).isEqualTo((keyPair.public as RSAPublicKey).modulus)
        assertThat(key.isPrivate).isTrue()
        assertThat(key.algorithm).isEqualTo(JWSAlgorithm.RS256)
        assertThat(key.keyUse).isEqualTo(KeyUse.SIGNATURE)
        assertThat(key.keyID).describedAs("RFC 7638 thumbprint").isEqualTo(key.computeThumbprint().toString())
    }

    @Test
    fun `환경변수 한 줄로 실린 PEM 의 이스케이프된 개행을 되돌려 읽는다`() {
        val keyPair = rsaKeyPair(2048)
        val oneLine = pemOf(keyPair).replace("\n", "\\n")

        val key = McpHandoffSigningKey(properties(privateKey = oneLine)).key!!

        assertThat(key.toRSAPublicKey().modulus).isEqualTo((keyPair.public as RSAPublicKey).modulus)
    }

    @Test
    fun `같은 키는 같은 kid 다`() {
        val pem = pemOf(rsaKeyPair(2048))

        val first = McpHandoffSigningKey(properties(privateKey = pem)).key!!
        val second = McpHandoffSigningKey(properties(privateKey = pem)).key!!

        assertThat(first.keyID).isEqualTo(second.keyID)
    }

    @Test
    fun `JWKS 에는 공개 성분만 싣는다`() {
        val signingKey = McpHandoffSigningKey(properties(privateKey = pemOf(rsaKeyPair(2048))))

        val published = signingKey.publicJwkSet().keys.single()

        assertThat(published.isPrivate).isFalse()
        assertThat(published.keyID).isEqualTo(signingKey.key!!.keyID)
    }

    @Test
    fun `키도 개발 키 생성도 없으면 키가 없고 JWKS 는 비어 있다`() {
        val signingKey = McpHandoffSigningKey(properties())

        assertThat(signingKey.key).isNull()
        assertThat(signingKey.publicJwkSet().keys).isEmpty()
    }

    @Test
    fun `generate-dev-key 가 켜지면 키가 없을 때 2048 bit 키를 만든다`() {
        val first = McpHandoffSigningKey(properties(generateDevKey = true)).key!!
        val second = McpHandoffSigningKey(properties(generateDevKey = true)).key!!

        assertThat(first.size()).isEqualTo(2048)
        assertThat(first.algorithm).isEqualTo(JWSAlgorithm.RS256)
        assertThat(first.keyID).describedAs("기동마다 새 키다").isNotEqualTo(second.keyID)
    }

    @Test
    fun `키가 설정돼 있으면 generate-dev-key 가 켜져 있어도 설정된 키를 쓴다`() {
        val keyPair = rsaKeyPair(2048)

        val key = McpHandoffSigningKey(properties(privateKey = pemOf(keyPair), generateDevKey = true)).key!!

        assertThat(key.toRSAPublicKey().modulus).isEqualTo((keyPair.public as RSAPublicKey).modulus)
    }

    @Test
    fun `PEM 이 아니면 기동이 멈추고 메시지에 키 내용을 싣지 않는다`() {
        val garbage = "-----BEGIN PRIVATE KEY-----\nbm90LWEta2V5LXNlY3JldA==\n-----END PRIVATE KEY-----"

        assertThatThrownBy { McpHandoffSigningKey(properties(privateKey = garbage)) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageContaining("artel.mcp.handoff.private-key")
            .hasMessageNotContaining("bm90LWEta2V5LXNlY3JldA")
            .hasNoCause()
    }

    @Test
    fun `base64 가 아닌 값도 같은 오류다`() {
        assertThatThrownBy { McpHandoffSigningKey(properties(privateKey = "not a pem at all!")) }
            .isInstanceOf(IllegalStateException::class.java)
            .hasMessageNotContaining("not a pem")
    }

    @Test
    fun `2048 bit 보다 짧은 키는 받지 않는다`() {
        assertThatThrownBy { McpHandoffSigningKey(properties(privateKey = pemOf(rsaKeyPair(1024)))) }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("2048")
    }

    private fun properties(privateKey: String = "", generateDevKey: Boolean = false) =
        McpProperties(handoff = McpProperties.Handoff(privateKey = privateKey, generateDevKey = generateDevKey))

    private fun rsaKeyPair(bits: Int): KeyPair =
        KeyPairGenerator.getInstance("RSA").apply { initialize(bits) }.generateKeyPair()

    /** `openssl genpkey` 가 내는 것과 같은 PKCS#8 PEM. */
    private fun pemOf(keyPair: KeyPair): String {
        val body = Base64.getMimeEncoder(64, "\n".toByteArray()).encodeToString(keyPair.private.encoded)
        return "-----BEGIN PRIVATE KEY-----\n$body\n-----END PRIVATE KEY-----\n"
    }
}
