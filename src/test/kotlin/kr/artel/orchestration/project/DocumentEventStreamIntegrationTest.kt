package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.knowledge.service.DocumentKnowledgeExtractionService
import kr.artel.orchestration.project.dto.DocumentStreamEvent
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.core.ParameterizedTypeReference
import org.springframework.http.MediaType
import org.springframework.http.codec.ServerSentEvent
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.Disposable
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.CopyOnWriteArrayList

private val PDF_BYTES_A = "%PDF-1.7\nfirst document".toByteArray(Charsets.US_ASCII)
private val PDF_BYTES_B = "%PDF-1.7\nsecond document".toByteArray(Charsets.US_ASCII)

/**
 * `GET /api/projects/{projectId}/documents/events` 통합 테스트(ARTEL-760).
 *
 * 추출 파이프라인은 이 프로필에서 꺼져 있다(`artel.agent.extract.enabled=false`,
 * `application-test.yml`). 실시간 `document` 프레임을 검증하는 테스트는
 * [DocumentKnowledgeExtractionService]를 직접 불러 우회한다 — Agent가 `localhost:8000`에
 * 없으므로 `EXTRACTING` → `FAILED`로 자연히 넘어가고, 그 두 전이가 각각 SSE 프레임을 낸다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class DocumentEventStreamIntegrationTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var extractionService: DocumentKnowledgeExtractionService

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    private val sseType = object : ParameterizedTypeReference<ServerSentEvent<DocumentStreamEvent>>() {}

    @BeforeEach
    fun clean(): Unit = runBlocking {
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        fakeStorage.clear()
    }

    @Test
    fun `sends a snapshot of every document's current status right after subscribing`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val firstId = upload(token, projectId, "기획서1.pdf", PDF_BYTES_A)["id"].asText()
        val secondId = upload(token, projectId, "기획서2.pdf", PDF_BYTES_B)["id"].asText()

        val events = CopyOnWriteArrayList<ServerSentEvent<DocumentStreamEvent>>()
        val subscription = subscribeSse(projectId, token) { events.add(it) }

        awaitUntil { events.isNotEmpty() }

        val snapshot = events.first()
        assertThat(snapshot.event()).isEqualTo("snapshot")
        val documents = requireNotNull(snapshot.data()?.documents)
        assertThat(documents).hasSize(2)
        assertThat(documents.map { it.documentId }).containsExactlyInAnyOrder(firstId, secondId)
        // 추출이 꺼져 있으니 업로드 직후 상태(PENDING) 그대로고, PENDING은 stale의 대상이 아니다.
        assertThat(documents).allSatisfy {
            assertThat(it.parseStatus).isEqualTo("PENDING")
            assertThat(it.stale).isFalse()
        }

        subscription.dispose()
    }

    @Test
    fun `pushes a document frame on every parse_status change without ever marking a live transition stale`(): Unit =
        runBlocking {
            val token = signIn()
            val projectId = createProject(token)
            val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES_A)["id"].asText()

            val events = CopyOnWriteArrayList<ServerSentEvent<DocumentStreamEvent>>()
            val subscription = subscribeSse(projectId, token) { events.add(it) }
            awaitUntil { events.any { it.event() == "snapshot" } }

            // 추출 파이프라인을 직접 돌린다(백그라운드 트리거는 꺼져 있음). Agent가 없어
            // EXTRACTING → FAILED로 자연히 넘어간다 — 그 두 전이 각각이 SSE document 프레임을 낸다.
            val document = requireNotNull(documentRepository.findById(documentId.toLong()))
            extractionService.extractAndStoreForDocument(document)

            awaitUntil(timeoutMs = 10_000) { events.count { it.event() == "document" } >= 2 }

            val documentEvents = events.filter { it.event() == "document" }
            assertThat(documentEvents.map { it.data()?.document?.parseStatus })
                .containsExactly("EXTRACTING", "FAILED")
            assertThat(documentEvents).allSatisfy {
                assertThat(it.data()?.document?.documentId).isEqualTo(documentId)
                // 이 서버가 직접 돌리고 있는 추출이라 언제나 stale=false여야 한다.
                assertThat(it.data()?.document?.stale).isFalse()
            }

            subscription.dispose()
        }

    @Test
    fun `marks a row stuck in EXTRACTING as stale when this server holds no in-flight extraction for it`(): Unit =
        runBlocking {
            val token = signIn()
            val projectId = createProject(token)
            val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES_A)["id"].asText().toLong()

            // 서버가 재시작돼 진행 중이던 추출이 유실된 상태를 흉내낸다: in-flight 집합에는 없이
            // DB의 parse_status만 EXTRACTING으로 굳힌다(추출 코디네이터를 거치지 않는다).
            documentRepository.updateParseStatus(documentId, "EXTRACTING")

            val events = CopyOnWriteArrayList<ServerSentEvent<DocumentStreamEvent>>()
            val subscription = subscribeSse(projectId, token) { events.add(it) }
            awaitUntil { events.isNotEmpty() }

            val snapshot = events.first { it.event() == "snapshot" }
            val response = requireNotNull(snapshot.data()?.documents)
                .single { it.documentId == documentId.toString() }
            assertThat(response.parseStatus).isEqualTo("EXTRACTING")
            assertThat(response.stale).isTrue()

            subscription.dispose()
        }

    @Test
    fun `refuses to open a stream for a project the caller cannot access`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val strangerToken = signIn("99", "hubot")
        val projectId = createProject(ownerToken)

        val status = client().get()
            .uri("/api/projects/$projectId/documents/events")
            .accept(MediaType.TEXT_EVENT_STREAM)
            .cookie("artel_access_token", strangerToken)
            .exchangeToMono { Mono.just(it.statusCode().value()) }
            .block(Duration.ofSeconds(5))

        assertThat(status).isEqualTo(404)
    }

    /**
     * SSE는 HTTP 응답이라 도착 시점을 정확히 알 수 없다. 조건이 될 때까지 짧게 폴링하고
     * [timeoutMs] 안에 만족하지 않으면 실패시킨다 — 값을 걸어 둔 채 기다리는 sleep 한 번보다
     * 빠르게 끝나면서도 놓치지 않는다.
     */
    private fun awaitUntil(timeoutMs: Long = 5_000, intervalMs: Long = 50, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            check(System.currentTimeMillis() < deadline) { "조건이 ${timeoutMs}ms 안에 충족되지 않았다." }
            Thread.sleep(intervalMs)
        }
    }

    private fun subscribeSse(
        projectId: String,
        token: String,
        onEvent: (ServerSentEvent<DocumentStreamEvent>) -> Unit
    ): Disposable = client().get()
        .uri("/api/projects/$projectId/documents/events")
        .accept(MediaType.TEXT_EVENT_STREAM)
        .cookie("artel_access_token", token)
        .retrieve()
        .bodyToFlux(sseType)
        .doOnNext(onEvent)
        .subscribe()

    /** 티켓 발급 → 저장소에 올림 → 등록까지, 클라이언트가 하는 세 단계를 그대로 지난다. */
    private fun upload(token: String, projectId: String, fileName: String, content: ByteArray) =
        ticketFor(token, projectId, fileName)
            .also { fakeStorage.put(it, content) }
            .let { register(token, projectId, it) }

    private fun ticketFor(token: String, projectId: String, fileName: String): String =
        ticket(
            token,
            projectId,
            """{"fileName":"$fileName","contentType":"application/pdf","sizeBytes":${PDF_BYTES_A.size}}"""
        )["objectKey"].asText()

    private fun ticket(token: String, projectId: String, body: String) =
        post(token, "/api/projects/$projectId/documents/upload-url", body)

    private fun register(token: String, projectId: String, objectKey: String) =
        post(token, "/api/projects/$projectId/documents", """{"objectKey":"$objectKey"}""")

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"Demo Day","genre":"ACTION"}""")["id"].asText()

    private suspend fun signIn(providerUserId: String = "42", login: String = "octocat"): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )
        return jwtService.issue(user)
    }

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")
}
