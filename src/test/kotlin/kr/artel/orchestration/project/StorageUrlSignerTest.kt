package kr.artel.orchestration.project

import kr.artel.orchestration.project.storage.StorageSignatureException
import kr.artel.orchestration.project.storage.StorageUrlSigner
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatCode
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import java.time.Clock
import java.time.Instant
import java.time.ZoneOffset

class StorageUrlSignerTest {

    private val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val secret = "test-jwt-secret-that-is-at-least-32-bytes-long"
    private val signer = StorageUrlSigner(secret, clock)
    private val key = "projects/1/documents/abc/기획서.pdf"
    private val expiresAt: Instant = now.plusSeconds(600)

    @Test
    fun `서명한 업로드 값 그대로면 통과한다`() {
        val signature = signer.signUpload(key, expiresAt, "application/pdf", 1024)

        assertThat(signature).matches("[0-9a-f]{64}")
        assertThatCode { signer.verifyUpload(key, expiresAt.epochSecond, "application/pdf", 1024, signature) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `대문자 hex 서명도 같은 서명으로 받는다`() {
        val signature = signer.signDownload(key, expiresAt, "기획서.pdf")

        assertThatCode { signer.verifyDownload(key, expiresAt.epochSecond, "기획서.pdf", signature.uppercase()) }
            .doesNotThrowAnyException()
    }

    @Test
    fun `만료 시각이 지나면 서명이 맞아도 거절한다`() {
        val past = now.minusSeconds(1)
        val signature = signer.signDownload(key, past, "a.pdf")

        assertThatThrownBy { signer.verifyDownload(key, past.epochSecond, "a.pdf", signature) }
            .isInstanceOf(StorageSignatureException::class.java)
    }

    @Test
    fun `만료 시각을 늘려 보내면 거절한다`() {
        val signature = signer.signDownload(key, expiresAt, "a.pdf")

        assertThatThrownBy { signer.verifyDownload(key, expiresAt.epochSecond + 3600, "a.pdf", signature) }
            .isInstanceOf(StorageSignatureException::class.java)
    }

    @Test
    fun `업로드 서명은 키, 형식, 크기 중 하나만 바뀌어도 거절한다`() {
        val signature = signer.signUpload(key, expiresAt, "application/pdf", 1024)

        listOf(
            { signer.verifyUpload("projects/2/documents/abc/기획서.pdf", expiresAt.epochSecond, "application/pdf", 1024, signature) },
            { signer.verifyUpload(key, expiresAt.epochSecond, "text/html", 1024, signature) },
            { signer.verifyUpload(key, expiresAt.epochSecond, "application/pdf", 1025, signature) },
        ).forEach { tampered ->
            assertThatThrownBy { tampered() }.isInstanceOf(StorageSignatureException::class.java)
        }
    }

    @Test
    fun `내려받기 서명은 파일 이름이 바뀌면 거절한다`() {
        val signature = signer.signDownload(key, expiresAt, "a.pdf")

        assertThatThrownBy { signer.verifyDownload(key, expiresAt.epochSecond, "a.html", signature) }
            .isInstanceOf(StorageSignatureException::class.java)
    }

    /** 메서드가 서명에 들어가 있지 않으면 내려받기 URL 로 같은 키에 올릴 수 있다. */
    @Test
    fun `내려받기 서명으로는 업로드할 수 없다`() {
        val downloadSignature = signer.signDownload(key, expiresAt, "a.pdf")

        assertThatThrownBy { signer.verifyUpload(key, expiresAt.epochSecond, "a.pdf", 0, downloadSignature) }
            .isInstanceOf(StorageSignatureException::class.java)
    }

    @Test
    fun `다른 비밀로 만든 서명은 거절한다`() {
        val otherSigner = StorageUrlSigner("another-jwt-secret-that-is-at-least-32-bytes", clock)
        val signature = otherSigner.signDownload(key, expiresAt, "a.pdf")

        assertThatThrownBy { signer.verifyDownload(key, expiresAt.epochSecond, "a.pdf", signature) }
            .isInstanceOf(StorageSignatureException::class.java)
    }

    @Test
    fun `hex 가 아닌 서명이나 길이가 다른 서명은 거절한다`() {
        listOf("", "zz", "00".repeat(31)).forEach { signature ->
            assertThatThrownBy { signer.verifyDownload(key, expiresAt.epochSecond, "a.pdf", signature) }
                .isInstanceOf(StorageSignatureException::class.java)
        }
    }
}
