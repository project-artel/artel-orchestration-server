package kr.artel.orchestration.project

import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.FilesystemDocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import org.springframework.test.web.reactive.server.WebTestClient
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path

/**
 * `ARTEL_STORAGE=filesystem` 으로 뜬 서버에서 서명 URL 로 올리고 내려받는다.
 *
 * 컨트롤러만 따로 띄우지 않고 실제 포트로 부르는 이유는 보안 체인까지 지나야 하기 때문이다. 이 경로는 세션 없이 열려야
 * 하고, 만료된 세션 쿠키를 실은 브라우저 요청도 401 이 아니라 서명으로만 판정되어야 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = ["artel.storage.backend=filesystem"]
)
class FilesystemStorageRoutesIntegrationTest {

    companion object {
        private val storageDirectory: Path = Files.createTempDirectory("artel-storage-routes")

        @JvmStatic
        @DynamicPropertySource
        fun storageDirectory(registry: DynamicPropertyRegistry) {
            registry.add("artel.storage.dir") { storageDirectory.toString() }
        }
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var storage: FilesystemDocumentStorage
    @Autowired private lateinit var documentStorage: DocumentStorage

    private lateinit var client: WebTestClient

    @BeforeEach
    fun setUp() {
        client = WebTestClient.bindToServer().baseUrl("http://localhost:$port").build()
    }

    /** presign 은 ARTEL_HOME_URL 을 앞에 붙이므로, 테스트 서버로 보낼 때는 경로와 쿼리만 쓴다. */
    private fun local(url: String): URI {
        val signed = URI.create(url)
        return URI.create("http://localhost:$port${signed.rawPath}?${signed.rawQuery}")
    }

    @Test
    fun `서비스가 주입받는 저장소가 filesystem 구현이다`() {
        assertThat(documentStorage).isSameAs(storage)
    }

    @Test
    fun `서명한 URL 로 올린 객체를 서명한 URL 로 내려받는다`() {
        val objectKey = "projects/1/documents/routes/기획서 1.pdf"
        val content = "%PDF-1.7 routes".toByteArray()
        val upload = storage.presignUpload(objectKey, "application/pdf", content.size.toLong())

        client.put().uri(local(upload.url))
            .header(HttpHeaders.CONTENT_TYPE, "application/pdf")
            .bodyValue(content)
            .exchange()
            .expectStatus().isOk

        assertThat(storage.head(objectKey).block()!!.sizeBytes).isEqualTo(content.size.toLong())

        val download = storage.presignDownload(objectKey, "기획서 1.pdf")
        client.get().uri(local(download.url))
            // 만료된 세션 쿠키가 실려 와도 서명만으로 판정한다.
            .cookie("artel_access_token", "expired-or-garbage")
            .exchange()
            .expectStatus().isOk
            .expectHeader().contentType(MediaType.APPLICATION_PDF)
            .expectHeader().contentLength(content.size.toLong())
            .expectHeader().valueEquals(
                HttpHeaders.CONTENT_DISPOSITION,
                "attachment; filename=\"___ 1.pdf\"; filename*=UTF-8''%EA%B8%B0%ED%9A%8D%EC%84%9C%201.pdf"
            )
            .expectBody().consumeWith { assertThat(it.responseBody).isEqualTo(content) }
    }

    @Test
    fun `서명이 틀리면 403 이다`() {
        val upload = storage.presignUpload("qa-captures/1/tampered.jpg", "image/jpeg", 3)
        val tampered = upload.url.dropLast(1) + if (upload.url.last() == '0') '1' else '0'

        client.put().uri(local(tampered))
            .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
            .bodyValue("abc".toByteArray())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
            .expectBody().jsonPath("$.code").isEqualTo("invalid_storage_signature")
    }

    @Test
    fun `서명한 형식이나 크기와 다르게 올리면 403 이다`() {
        val upload = storage.presignUpload("qa-captures/1/other.jpg", "image/jpeg", 3)

        client.put().uri(local(upload.url))
            .header(HttpHeaders.CONTENT_TYPE, "text/html")
            .bodyValue("abc".toByteArray())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        client.put().uri(local(upload.url))
            .header(HttpHeaders.CONTENT_TYPE, "image/jpeg")
            .bodyValue("abcd".toByteArray())
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)

        assertThat(storage.head("qa-captures/1/other.jpg").block()).isNull()
    }

    @Test
    fun `내려받기 URL 의 파일 이름을 바꾸면 403 이다`() {
        storage.put("qa-captures/1/name.jpg", "abc".toByteArray(), "image/jpeg").block()
        val download = storage.presignDownload("qa-captures/1/name.jpg", "name.jpg")

        client.get().uri(local(download.url.replace("filename=name.jpg", "filename=name.html")))
            .exchange()
            .expectStatus().isEqualTo(HttpStatus.FORBIDDEN)
    }

    @Test
    fun `경로 조각에 인코딩한 슬래시나 상위 경로를 넣으면 거절한다`() {
        val download = storage.presignDownload("qa-captures/1/name.jpg", "name.jpg")
        val query = URI.create(download.url).rawQuery

        listOf(
            "/api/storage/objects/qa-captures%2F1%2Fname.jpg",
            "/api/storage/objects/qa-captures/1/%2E%2E/%2E%2E/escape.jpg",
        ).forEach { path ->
            client.get().uri(URI.create("http://localhost:$port$path?$query"))
                .exchange()
                .expectStatus().value { status -> assertThat(status).isIn(400, 403) }
        }
    }
}
