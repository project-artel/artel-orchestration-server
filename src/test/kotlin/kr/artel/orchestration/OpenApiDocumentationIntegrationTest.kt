package kr.artel.orchestration

import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class OpenApiDocumentationIntegrationTest {
    @LocalServerPort
    private val port: Int = 0

    @Test
    fun `publishes the orchestration API contract`() {
        val response = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(response).contains("Artel Orchestration Server API")
        assertThat(response).contains("/api/sdk/registrations")
        assertThat(response).contains("/internal/action/{instanceId}")
        assertThat(response).contains("SDK 인스턴스 등록")
        assertThat(response).contains("Deliver agent actions to a game instance")
    }

    @Test
    fun `publishes the game instance and build endpoints`() {
        val response = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(response).contains("/api/projects/{projectId}/game-instances")
        assertThat(response).contains("/api/projects/{projectId}/game-instances/{instanceId}")
        assertThat(response).contains("/api/projects/{projectId}/game-builds")
        assertThat(response).contains("/api/projects/{projectId}/game-builds/{buildId}")
        // 인스턴스 생성은 대시보드가 아니라 SDK 등록이 한다.
        assertThat(response).contains("게임 인스턴스 목록")
        assertThat(response).contains("SDK 인스턴스 등록")
        assertThat(response).contains("게임 빌드 설명 수정")
    }

    @Test
    fun `publishes the project and planning-document endpoints`() {
        val response = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(response).contains("/api/projects")
        assertThat(response).contains("/api/projects/{projectId}")
        assertThat(response).contains("/api/projects/{projectId}/documents")
        assertThat(response).contains("/api/projects/{projectId}/documents/upload-url")
        assertThat(response).contains("/api/projects/{projectId}/documents/{documentId}/download-url")
        // 이 키가 "/download-url" 접미사가 붙은 경로와 헷갈리지 않도록 따옴표까지 확인한다.
        assertThat(response).contains("\"/api/projects/{projectId}/documents/{documentId}\"")
        assertThat(response).contains("프로젝트 생성")
        assertThat(response).contains("업로드 URL 발급")
        assertThat(response).contains("기획서 삭제")
    }

    /**
     * `@CurrentUserId`는 세션에서 오는 값이지 호출자가 보내는 값이 아니다(ARTEL-312).
     *
     * springdoc은 모르는 파라미터 어노테이션을 쿼리 파라미터로 문서화한다. 무시 목록에서 빠지면
     * 인증 엔드포인트마다 `appUserId`가 **필수 쿼리 파라미터**로 계약에 실리고, 이 문서에서
     * 파생되는 Insomnia 컬렉션이 그 값을 요구하게 된다.
     *
     * **파라미터 자리만 본다.** 종전에는 문서 어디에도 그 문자열이 없어야 한다고 걸었는데, 그것은
     * 지키려는 것의 대용품이었지 그것 자체가 아니었다. 초대를 `appUserId`로 보내는 길이 생기면서
     * (ARTEL-756) 같은 이름이 `CreateInvitationRequest`·`InvitationSuggestionResponse`의 정당한
     * 필드로 계약에 들어왔고, 넓은 단정이 그것을 결함으로 읽어 develop을 빨갛게 만들었다.
     *
     * 호출자가 **보내는** 값인 스키마 필드는 괜찮다. 안 되는 것은 세션에서 오는 값이 호출자에게
     * 요구되는 것이고, 그것은 `parameters` 배열에만 나타난다 — `in`이 `query`든 `path`든 마찬가지다.
     */
    @Test
    fun `keeps the session-derived user id out of the contract`() {
        val response = WebClient.create("http://localhost:$port")
            .get()
            .uri("/v3/api-docs")
            .retrieve()
            .bodyToMono(String::class.java)
            .block(Duration.ofSeconds(5))

        assertThat(response).doesNotContain(""""name":"appUserId"""")
    }
}
