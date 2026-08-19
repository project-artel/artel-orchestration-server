package kr.artel.orchestration

import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.nio.file.Files
import java.nio.file.Path
import java.time.Duration

/**
 * 런타임 OpenAPI 계약을 정적 스냅샷(`docs/api/openapi.json`)으로 떨군다.
 *
 * 워크플로우에서 `mvn test` 후 이 파일이 변했는지 diff로 확인한다.
 * 스냅샷이 최신 코드와 어긋나면 PR이 실패해 계약 drift를 머지 전에 잡는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiSnapshotTest {
    @LocalServerPort
    private val port: Int = 0

    @Test
    fun `snapshot the runtime OpenAPI contract`() {
        val spec = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(10))

        require(spec != null && spec.contains("Artel Orchestration Server API"))

        val target = Path.of("docs/api/openapi.json")
        Files.createDirectories(target.parent)
        Files.writeString(target, spec)
    }
}