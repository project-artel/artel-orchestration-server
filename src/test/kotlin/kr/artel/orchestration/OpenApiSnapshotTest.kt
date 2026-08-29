package kr.artel.orchestration

import com.fasterxml.jackson.core.util.DefaultIndenter
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter
import com.fasterxml.jackson.core.util.Separators
import com.fasterxml.jackson.databind.ObjectMapper
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

        // springdoc 은 한 줄짜리 JSON 을 준다. 그대로 떨구면 계약이 한 글자만 바뀌어도
        // diff 가 파일 전체를 한 줄로 보여줘서 무엇이 변했는지 읽을 수 없다.
        // key 순서는 Jackson 이 읽은 그대로 유지되므로 pretty print 를 해도 스냅샷은 안정적이다.
        val mapper = ObjectMapper()
        val pretty = mapper.writer(prettyPrinter()).writeValueAsString(mapper.readTree(spec))

        val target = Path.of("docs/api/openapi.json")
        Files.createDirectories(target.parent)
        Files.writeString(target, pretty + "\n")
    }

    /**
     * Jackson 기본 pretty printer 는 `"key" : value` 처럼 콜론 앞에 공백을 넣고 배열을 한 줄에 붙인다.
     * 줄바꿈은 플랫폼을 따라가서 Windows 에서 뜨면 파일 전체가 CRLF 로 바뀐다.
     * 셋 다 고정해 어느 기계에서 떠도 같은 바이트가 나오게 한다.
     */
    private fun prettyPrinter(): DefaultPrettyPrinter {
        val indenter = DefaultIndenter("  ", "\n")
        return DefaultPrettyPrinter()
            .withObjectIndenter(indenter)
            .withArrayIndenter(indenter)
            .withSeparators(
                Separators.createDefaultInstance()
                    .withObjectFieldValueSpacing(Separators.Spacing.AFTER)
            )
    }
}