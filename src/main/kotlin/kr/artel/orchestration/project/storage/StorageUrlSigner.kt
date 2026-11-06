package kr.artel.orchestration.project.storage

import kr.artel.orchestration.common.error.ForbiddenException
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.time.Clock
import java.time.Instant
import java.util.HexFormat
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec

/**
 * 파일시스템 저장소의 URL 서명. `/api/storage/objects/` 는 세션 없이 열리므로 이 서명이 유일한 자격증명이다.
 *
 * 서명 대상은 메서드, 객체 키, 만료 시각이고, 업로드는 Content-Type 과 Content-Length 를, 내려받기는
 * 응답에 실을 파일 이름을 더한다. 업로드 URL 하나로 다른 형식이나 더 큰 파일을 올리거나, 내려받기 URL 을
 * 업로드에 쓰는 것을 이 목록이 막는다.
 *
 * 서명 키는 [rootSecret] 을 그대로 쓰지 않고 [KEY_LABEL] 로 HMAC 을 한 번 거친 값이다. 같은 비밀로 JWT 도
 * 서명하므로, 그대로 쓰면 한쪽 서명을 다른 쪽에 내미는 길이 열린다.
 */
class StorageUrlSigner(rootSecret: String, private val clock: Clock) {

    private val signingKey: ByteArray = hmac(rootSecret.toByteArray(StandardCharsets.UTF_8), KEY_LABEL)

    fun signUpload(objectKey: String, expiresAt: Instant, contentType: String, contentLength: Long): String =
        sign(listOf(METHOD_PUT, objectKey, expiresAt.epochSecond.toString(), contentType, contentLength.toString()))

    fun signDownload(objectKey: String, expiresAt: Instant, fileName: String): String =
        sign(listOf(METHOD_GET, objectKey, expiresAt.epochSecond.toString(), fileName))

    /** 만료됐거나 서명이 맞지 않으면 [StorageSignatureException] 이다. */
    fun verifyUpload(
        objectKey: String,
        expiresEpochSeconds: Long,
        contentType: String,
        contentLength: Long,
        signature: String
    ) {
        requireNotExpired(expiresEpochSeconds)
        val expected = sign(listOf(METHOD_PUT, objectKey, expiresEpochSeconds.toString(), contentType, contentLength.toString()))
        requireMatches(expected, signature)
    }

    fun verifyDownload(objectKey: String, expiresEpochSeconds: Long, fileName: String, signature: String) {
        requireNotExpired(expiresEpochSeconds)
        val expected = sign(listOf(METHOD_GET, objectKey, expiresEpochSeconds.toString(), fileName))
        requireMatches(expected, signature)
    }

    private fun requireNotExpired(expiresEpochSeconds: Long) {
        if (Instant.now(clock).epochSecond > expiresEpochSeconds) {
            throw StorageSignatureException()
        }
    }

    /** 한 글자씩 비교하면 앞에서부터 맞는 길이가 응답 시간으로 드러난다. 그래서 고정 시간 비교를 쓴다. */
    private fun requireMatches(expectedHex: String, actualHex: String) {
        val matches = MessageDigest.isEqual(
            expectedHex.toByteArray(StandardCharsets.US_ASCII),
            actualHex.lowercase().toByteArray(StandardCharsets.US_ASCII)
        )
        if (!matches) throw StorageSignatureException()
    }

    /**
     * 각 필드를 퍼센트 인코딩한 뒤 줄바꿈으로 잇는다. 인코딩된 값에는 줄바꿈이 없으므로, 파일 이름에 줄바꿈이
     * 들어 있어도 필드 경계를 옮겨 다른 요청의 서명과 같아지게 만들 수 없다.
     */
    private fun sign(fields: List<String>): String {
        val canonical = fields.joinToString("\n") { URLEncoder.encode(it, StandardCharsets.UTF_8) }
        return HexFormat.of().formatHex(hmac(signingKey, canonical))
    }

    private fun hmac(key: ByteArray, message: String): ByteArray =
        Mac.getInstance(HMAC_ALGORITHM).run {
            init(SecretKeySpec(key, HMAC_ALGORITHM))
            doFinal(message.toByteArray(StandardCharsets.UTF_8))
        }

    private companion object {
        const val HMAC_ALGORITHM = "HmacSHA256"
        const val KEY_LABEL = "artel/storage-url-signature/v1"
        const val METHOD_PUT = "PUT"
        const val METHOD_GET = "GET"
    }
}

/** 서명이 없거나, 맞지 않거나, 만료됐다. 어느 쪽인지 알려주지 않는다. */
class StorageSignatureException :
    ForbiddenException("저장소 URL 이 만료됐거나 올바르지 않습니다.", code = "invalid_storage_signature")
