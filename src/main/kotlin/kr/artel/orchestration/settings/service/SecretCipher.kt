package kr.artel.orchestration.settings.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.settings.config.SecretsProperties
import org.springframework.stereotype.Component
import java.security.GeneralSecurityException
import java.security.SecureRandom
import java.util.Base64
import javax.crypto.Cipher
import javax.crypto.Mac
import javax.crypto.spec.GCMParameterSpec
import javax.crypto.spec.SecretKeySpec

/** 암호문 앞에 붙이는 형식 표시. 키 유도나 알고리즘을 바꾸면 v2 를 만들고 v1 은 계속 읽는다. */
private const val FORMAT_PREFIX = "v1:"

/** HMAC 으로 AES 키를 만들 때 쓰는 고정 문구. 같은 `ARTEL_SECRETS_KEY` 를 다른 용도에 써도 키가 겹치지 않는다. */
private const val KEY_DERIVATION_LABEL = "artel/platform_setting/aes-256-gcm/v1"

private const val NONCE_BYTES = 12
private const val TAG_BITS = 128

/**
 * `platform_setting` 값을 AES-256-GCM 으로 잠그고 연다.
 *
 * AES 키는 `HMAC-SHA256(ARTEL_SECRETS_KEY, KEY_DERIVATION_LABEL)` 이다. 사람이 고른 문자열을 그대로 키로
 * 쓰지 않고, 길이와 무관하게 32 바이트를 얻는다.
 *
 * 암호문마다 12 바이트 nonce 를 새로 뽑고, 설정 이름을 associated data 로 묶는다. 그래서 DB 를 고칠 수
 * 있는 사람이 한 설정의 암호문을 다른 설정 자리로 옮겨도 열리지 않는다.
 */
@Component
class SecretCipher(properties: SecretsProperties) {
    private val aesKey: SecretKeySpec? = properties.key.takeIf { it.isNotEmpty() }?.let(::deriveKey)
    private val random = SecureRandom()

    /** `ARTEL_SECRETS_KEY` 가 있는지. 없으면 [encrypt] 는 409 다. */
    val configured: Boolean
        get() = aesKey != null

    fun encrypt(plaintext: String, settingKey: String): String {
        val key = aesKey ?: throw SecretsKeyMissingException()
        val nonce = ByteArray(NONCE_BYTES).also(random::nextBytes)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key, GCMParameterSpec(TAG_BITS, nonce))
        cipher.updateAAD(settingKey.toByteArray(Charsets.UTF_8))
        val sealed = cipher.doFinal(plaintext.toByteArray(Charsets.UTF_8))
        return FORMAT_PREFIX + Base64.getEncoder().encodeToString(nonce + sealed)
    }

    /**
     * 열지 못하면 null 이다. `ARTEL_SECRETS_KEY` 가 없거나 바뀌었거나 값이 망가진 경우다. 원인을 예외로
     * 올리지 않는 이유는 그때도 `OPENROUTER_API_KEY` 로 계속 돌아야 하기 때문이다.
     */
    fun decrypt(stored: String, settingKey: String): String? {
        val key = aesKey ?: return null
        if (!stored.startsWith(FORMAT_PREFIX)) return null
        return try {
            val bytes = Base64.getDecoder().decode(stored.removePrefix(FORMAT_PREFIX))
            if (bytes.size <= NONCE_BYTES) return null
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(TAG_BITS, bytes, 0, NONCE_BYTES))
            cipher.updateAAD(settingKey.toByteArray(Charsets.UTF_8))
            String(cipher.doFinal(bytes, NONCE_BYTES, bytes.size - NONCE_BYTES), Charsets.UTF_8)
        } catch (error: GeneralSecurityException) {
            null
        } catch (error: IllegalArgumentException) {
            null
        }
    }

    private fun deriveKey(secret: String): SecretKeySpec {
        val mac = Mac.getInstance("HmacSHA256")
        mac.init(SecretKeySpec(secret.toByteArray(Charsets.UTF_8), "HmacSHA256"))
        return SecretKeySpec(mac.doFinal(KEY_DERIVATION_LABEL.toByteArray(Charsets.UTF_8)), "AES")
    }
}

/** `ARTEL_SECRETS_KEY` 없이 비밀 설정을 저장하려 할 때. */
class SecretsKeyMissingException :
    ConflictException(
        "ARTEL_SECRETS_KEY 가 설정되지 않아 값을 암호화해 저장할 수 없습니다. 서버 환경 변수에 넣거나 OPENROUTER_API_KEY 를 쓰세요.",
        code = "secrets_key_missing"
    )
