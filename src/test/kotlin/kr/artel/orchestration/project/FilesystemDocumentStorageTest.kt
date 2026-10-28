package kr.artel.orchestration.project

import kr.artel.orchestration.project.config.StorageBackend
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.project.storage.FilesystemDocumentStorage
import kr.artel.orchestration.project.storage.InvalidStorageObjectKeyException
import kr.artel.orchestration.project.storage.StorageObjectIncompleteException
import kr.artel.orchestration.project.storage.StorageObjectTooLargeException
import kr.artel.orchestration.project.storage.StorageUrlSigner
import kr.artel.orchestration.project.storage.UrlAudience
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import org.springframework.core.io.buffer.DataBuffer
import org.springframework.core.io.buffer.DefaultDataBufferFactory
import reactor.core.publisher.Flux
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.security.MessageDigest
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.HexFormat

class FilesystemDocumentStorageTest {

    @TempDir
    lateinit var temporaryDirectory: Path

    private val now: Instant = Instant.parse("2030-01-01T00:00:00Z")
    private val clock: Clock = Clock.fixed(now, ZoneOffset.UTC)
    private val signer = StorageUrlSigner("test-jwt-secret-that-is-at-least-32-bytes-long", clock)

    private fun storage(
        root: Path = temporaryDirectory.resolve("storage"),
        internalBaseUrl: String? = null
    ) = FilesystemDocumentStorage(
        root = root,
        publicBaseUrl = "http://127.0.0.1:8088/",
        internalBaseUrl = internalBaseUrl,
        uploadUrlTtl = Duration.ofMinutes(10),
        downloadUrlTtl = Duration.ofMinutes(5),
        signer = signer,
        clock = clock
    )

    @Test
    fun `시연 prefix의 orphan만 조회하고 QA 파일은 보존한다`() {
        val storage = storage()
        storage.put("demonstrations/old/frame.png",byteArrayOf(1),"image/png").block()
        storage.put("qa-recordings/keep.webm",byteArrayOf(2),"video/webm").block()
        val objects = storage.retiredDemonstrationObjects().collectList().block()!!
        assertThat(objects.map { it.key }).containsExactly("demonstrations/old/frame.png")
        objects.forEach { storage.deleteRetiredDemonstrationObject(it).block() }
        assertThat(storage.retiredDemonstrationObjects().collectList().block()).isEmpty()
        assertThat(storage.read("qa-recordings/keep.webm").block()).containsExactly(2.toByte())
    }

    @Test
    fun `시연 경로의 외부 symlink는 따라가지 않고 삭제도 거절한다`() {
        val storage = storage()
        val outside = Files.createDirectory(temporaryDirectory.resolve("outside"))
        Files.write(outside.resolve("keep.png"),byteArrayOf(3))
        val demo = Files.createDirectories(temporaryDirectory.resolve("storage/objects/demonstrations"))
        Files.createSymbolicLink(demo.resolve("outside"),outside)
        assertThat(storage.retiredDemonstrationObjects().collectList().block()).isEmpty()
        assertThatThrownBy { storage.deleteRetiredDemonstrationObject(
            kr.artel.orchestration.project.storage.RetiredStorageObject("demonstrations/outside/keep.png")).block() }
            .isInstanceOf(InvalidStorageObjectKeyException::class.java)
        assertThat(Files.readAllBytes(outside.resolve("keep.png"))).containsExactly(3.toByte())
    }
    private fun bodyOf(vararg chunks: String): Flux<DataBuffer> =
        Flux.fromIterable(chunks.map { DefaultDataBufferFactory.sharedInstance.wrap(it.toByteArray()) })

    @Test
    fun `SERVER 용 다운로드 URL 은 내부 주소를 쓰고 PUBLIC 용은 공개 주소를 쓴다`() {
        val storage = storage(internalBaseUrl = "http://orchestration:8080/")

        val server = storage.presignDownload("qa-captures/1/a.jpg", "a.jpg", audience = UrlAudience.SERVER)
        val public = storage.presignDownload("qa-captures/1/a.jpg", "a.jpg")

        assertThat(server.url).startsWith("http://orchestration:8080/api/storage/objects/qa-captures/1/a.jpg?")
        assertThat(public.url).startsWith("http://127.0.0.1:8088/api/storage/objects/qa-captures/1/a.jpg?")
        // 서명은 주소와 무관하므로 쿼리가 같다.
        assertThat(URI.create(server.url).rawQuery).isEqualTo(URI.create(public.url).rawQuery)
    }

    @Test
    fun `내부 주소가 없으면 SERVER 용 URL 도 공개 주소를 쓴다`() {
        val blank = storage(internalBaseUrl = "  ")

        assertThat(blank.presignDownload("k/a.jpg", "a.jpg", audience = UrlAudience.SERVER).url)
            .startsWith("http://127.0.0.1:8088/api/storage/objects/k/a.jpg?")
        assertThat(storage().presignDownload("k/a.jpg", "a.jpg", audience = UrlAudience.SERVER).url)
            .startsWith("http://127.0.0.1:8088/api/storage/objects/k/a.jpg?")
    }

    @Test
    fun `업로드 URL 은 ARTEL_HOME_URL 아래의 서명 경로이고 키 조각을 인코딩한다`() {
        val presigned = storage().presignUpload("projects/1/documents/abc/기획 서.pdf", "application/pdf", 1024)
        val url = URI.create(presigned.url)

        assertThat(presigned.url).startsWith("http://127.0.0.1:8088/api/storage/objects/projects/1/documents/abc/")
        assertThat(url.rawPath).endsWith("/%EA%B8%B0%ED%9A%8D%20%EC%84%9C.pdf")
        assertThat(url.rawQuery).matches("expires=${now.plusSeconds(600).epochSecond}&signature=[0-9a-f]{64}")
        assertThat(presigned.requiredHeaders).containsEntry("Content-Type", "application/pdf")
        assertThat(presigned.expiresAt).isEqualTo(now.plusSeconds(600))
    }

    @Test
    fun `내려받기 URL 은 파일 이름을 싣고 ttl 을 덮어쓸 수 있다`() {
        val presigned = storage().presignDownload("qa-captures/1/a.jpg", "화면.jpg", Duration.ofMinutes(30))

        assertThat(presigned.url).contains("filename=%ED%99%94%EB%A9%B4.jpg")
        assertThat(presigned.expiresAt).isEqualTo(now.plus(Duration.ofMinutes(30)))
    }

    @Test
    fun `put 한 객체를 head, readPrefix, read, sha256 으로 읽고 delete 로 지운다`() {
        val storage = storage()
        val content = "%PDF-1.7 body".toByteArray()

        storage.put("projects/1/documents/x/a.pdf", content, "application/pdf").block()

        val stored = storage.head("projects/1/documents/x/a.pdf").block()!!
        assertThat(stored.sizeBytes).isEqualTo(content.size.toLong())
        assertThat(stored.contentType).isEqualTo("application/pdf")
        assertThat(storage.readPrefix("projects/1/documents/x/a.pdf", 4).block()).isEqualTo("%PDF".toByteArray())
        assertThat(storage.read("projects/1/documents/x/a.pdf").block()).isEqualTo(content)
        assertThat(storage.sha256("projects/1/documents/x/a.pdf").block())
            .isEqualTo(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content)))

        storage.delete("projects/1/documents/x/a.pdf").block()

        assertThat(storage.head("projects/1/documents/x/a.pdf").block()).isNull()
        assertThat(storage.read("projects/1/documents/x/a.pdf").block()).isNull()
        assertThat(storage.sha256("projects/1/documents/x/a.pdf").block()).isNull()
    }

    @Test
    fun `없는 키는 빈 결과이고 지워도 실패하지 않는다`() {
        val storage = storage()

        assertThat(storage.head("missing/a.pdf").block()).isNull()
        assertThat(storage.readPrefix("missing/a.pdf", 4).block()).isNull()
        storage.delete("missing/a.pdf").block()
    }

    @Test
    fun `write 는 조각난 본문을 이어 저장하고 임시 파일을 남기지 않는다`() {
        val root = temporaryDirectory.resolve("storage")
        val storage = storage(root)

        storage.write("qa-captures/1/a.jpg", "image/jpeg", 6, bodyOf("abc", "def")).block()

        assertThat(storage.read("qa-captures/1/a.jpg").block()).isEqualTo("abcdef".toByteArray())
        assertThat(storage.head("qa-captures/1/a.jpg").block()!!.contentType).isEqualTo("image/jpeg")
        assertThat(Files.list(root.resolve("uploads-in-progress")).use { it.count() }).isZero()
    }

    @Test
    fun `신고한 크기를 넘는 본문은 저장하지 않고 이전 객체를 남긴다`() {
        val storage = storage()
        storage.put("qa-captures/1/a.jpg", "old".toByteArray(), "image/jpeg").block()

        assertThatThrownBy { storage.write("qa-captures/1/a.jpg", "image/jpeg", 3, bodyOf("ab", "cd")).block() }
            .isInstanceOf(StorageObjectTooLargeException::class.java)
        assertThat(storage.read("qa-captures/1/a.jpg").block()).isEqualTo("old".toByteArray())
    }

    @Test
    fun `신고한 크기보다 먼저 끝난 본문은 저장하지 않는다`() {
        val storage = storage()

        assertThatThrownBy { storage.write("qa-captures/1/b.jpg", "image/jpeg", 10, bodyOf("abc")).block() }
            .isInstanceOf(StorageObjectIncompleteException::class.java)
        assertThat(storage.head("qa-captures/1/b.jpg").block()).isNull()
    }

    @Test
    fun `루트 밖을 가리키거나 해석이 갈리는 키는 거절한다`() {
        val storage = storage()
        val unsafeKeys = listOf(
            "",
            "../escape.txt",
            "projects/../../escape.txt",
            "projects/./a.txt",
            "/etc/passwd",
            "projects//a.txt",
            "projects/a.txt/",
            "projects\\..\\a.txt",
            "C:/Windows/a.txt",
            "projects/a\u0000.txt",
            "projects/a\n.txt",
            "a".repeat(1025)
        )

        unsafeKeys.forEach { key ->
            assertThatThrownBy { storage.put(key, "x".toByteArray(), "text/plain").block() }
                .`as`("key=%s", key)
                .isInstanceOf(InvalidStorageObjectKeyException::class.java)
            assertThatThrownBy { storage.presignUpload(key, "text/plain", 1) }
                .`as`("key=%s", key)
                .isInstanceOf(InvalidStorageObjectKeyException::class.java)
        }
        assertThat(Files.exists(temporaryDirectory.resolve("escape.txt"))).isFalse()
    }

    @Test
    fun `루트 밖 디렉터리로 가는 링크를 따라 쓰거나 읽지 않는다`() {
        val root = temporaryDirectory.resolve("storage")
        val storage = storage(root)
        val outside = Files.createDirectories(temporaryDirectory.resolve("outside"))
        Files.writeString(outside.resolve("secret.txt"), "secret")
        Files.createSymbolicLink(root.resolve("objects").resolve("linked"), outside)

        assertThatThrownBy { storage.put("linked/new.txt", "x".toByteArray(), "text/plain").block() }
            .isInstanceOf(InvalidStorageObjectKeyException::class.java)
        assertThat(storage.read("linked/secret.txt").block()).isNull()
        assertThat(Files.exists(outside.resolve("new.txt"))).isFalse()
    }

    @Test
    fun `filesystem 모드에서 디렉터리가 없으면 설정 단계에서 멈춘다`() {
        assertThatThrownBy { StorageProperties(bucket = "artel-local", backend = StorageBackend.FILESYSTEM, dir = " ") }
            .isInstanceOf(IllegalArgumentException::class.java)
            .hasMessageContaining("ARTEL_STORAGE_DIR")
    }
}
