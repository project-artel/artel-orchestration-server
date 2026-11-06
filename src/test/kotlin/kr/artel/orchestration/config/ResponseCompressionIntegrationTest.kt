package kr.artel.orchestration.config

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.time.Duration
import java.util.zip.GZIPInputStream

/**
 * 큰 JSON 응답이 gzip 으로 나간다(ARTEL-1212). 압축은 Netty 서버에서 일어나므로 실제 포트로 부른다 —
 * `WebTestClient` 를 컨텍스트에 바로 묶으면 서버를 거치지 않아 압축이 보이지 않는다.
 *
 * `java.net.http` 는 응답을 스스로 풀지 않아 `Content-Encoding` 과 압축된 몸을 그대로 볼 수 있다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ResponseCompressionIntegrationTest {
    @LocalServerPort
    private val port: Int = 0

    private val client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build()

    private fun apiDocs(acceptEncoding: String?): HttpResponse<ByteArray> {
        val request = HttpRequest.newBuilder(URI.create("http://localhost:$port/v3/api-docs"))
            .timeout(Duration.ofSeconds(10))
            .apply { if (acceptEncoding != null) header("Accept-Encoding", acceptEncoding) }
            .GET()
            .build()
        return client.send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    @Test
    fun `gzips a large json response when the client accepts it`() {
        val plain = apiDocs(null)
        val zipped = apiDocs("gzip")

        assertThat(plain.headers().firstValue("Content-Encoding")).isEmpty
        assertThat(zipped.headers().firstValue("Content-Encoding")).hasValue("gzip")
        val unzipped = GZIPInputStream(zipped.body().inputStream()).readAllBytes()
        assertThat(String(unzipped)).isEqualTo(String(plain.body()))
        // OpenAPI 문서는 수백 KB 라 몇 분의 일로 줄어야 한다.
        assertThat(zipped.body().size).isLessThan(plain.body().size / 3)
    }
}
