package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
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
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

private val PDF_BYTES = "%PDF-1.7\nfake pdf body".toByteArray(Charsets.US_ASCII)
private val NOT_PDF_BYTES = "<html>이건 PDF가 아니다</html>".toByteArray(Charsets.UTF_8)

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ProjectDocumentIntegrationTest {

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

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    @BeforeEach
    fun clean() {
        documentRepository.deleteAll().block()
        memberRepository.deleteAll().block()
        projectRepository.deleteAll().block()
        identityRepository.deleteAll().block()
        appUserRepository.deleteAll().block()
        fakeStorage.clear()
    }

    @Test
    fun `uploads a planning document and makes it the current version`() {
        val token = signIn()
        val projectId = createProject(token)

        val document = upload(token, projectId, "기획서.pdf", PDF_BYTES)

        assertThat(document["version"].asInt()).isEqualTo(1)
        assertThat(document["fileName"].asText()).isEqualTo("기획서.pdf")
        assertThat(document["contentType"].asText()).isEqualTo("application/pdf")
        assertThat(document["parseStatus"].asText()).isEqualTo("PENDING")
        assertThat(document["uploadedBy"]["displayName"].asText()).isEqualTo("octocat")

        val detail = get(token, "/api/projects/$projectId")
        assertThat(detail["document"]["version"].asInt()).isEqualTo(1)
    }

    @Test
    fun `stacks versions instead of replacing the previous document`() {
        val token = signIn()
        val projectId = createProject(token)

        upload(token, projectId, "기획서.pdf", PDF_BYTES)
        val second = upload(token, projectId, "기획서.pdf", PDF_BYTES)

        assertThat(second["version"].asInt()).isEqualTo(2)

        val history = get(token, "/api/projects/$projectId/documents")
        assertThat(history).hasSize(2)
        // 최신 버전이 앞에 온다.
        assertThat(history[0]["version"].asInt()).isEqualTo(2)
        assertThat(history[1]["version"].asInt()).isEqualTo(1)

        val detail = get(token, "/api/projects/$projectId")
        assertThat(detail["document"]["version"].asInt()).isEqualTo(2)

        val summary = get(token, "/api/projects")["items"][0]
        assertThat(summary["documentCount"].asLong()).isEqualTo(2)
        assertThat(summary["latestDocument"]["version"].asInt()).isEqualTo(2)
    }

    @Test
    fun `rejects a ticket request for anything other than a pdf`() {
        val token = signIn()
        val projectId = createProject(token)

        val byExtension = statusOf {
            ticket(token, projectId, """{"fileName":"기획서.docx","contentType":"application/pdf","sizeBytes":100}""")
        }
        val byContentType = statusOf {
            ticket(token, projectId, """{"fileName":"기획서.pdf","contentType":"text/plain","sizeBytes":100}""")
        }

        assertThat(byExtension).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(byContentType).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `rejects a ticket request above the size limit`() {
        val token = signIn()
        val projectId = createProject(token)

        val status = statusOf {
            ticket(
                token,
                projectId,
                """{"fileName":"큰파일.pdf","contentType":"application/pdf","sizeBytes":99999999}"""
            )
        }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `refuses to register an object that was never uploaded`() {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "기획서.pdf")

        // 업로드를 건너뛴 채 바로 등록한다.
        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(documentRepository.count().block()).isZero()
    }

    @Test
    fun `refuses to register bytes that are not actually a pdf`() {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "위장.pdf")
        // Content-Type은 application/pdf라고 신고했지만 내용은 PDF가 아니다.
        fakeStorage.put(objectKey, NOT_PDF_BYTES)

        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(documentRepository.count().block()).isZero()
    }

    @Test
    fun `refuses to register an empty object`() {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "빈파일.pdf")
        fakeStorage.put(objectKey, ByteArray(0))

        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `refuses an object key that belongs to another project`() {
        val token = signIn()
        val mine = createProject(token)
        val other = createProject(token)
        val otherKey = ticketFor(token, other, "남의기획서.pdf")
        fakeStorage.put(otherKey, PDF_BYTES)

        val status = statusOf { register(token, mine, otherKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `issues a fresh download url instead of storing a permanent link`() {
        val token = signIn()
        val projectId = createProject(token)
        val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES)["id"].asText()

        val ticket = get(token, "/api/projects/$projectId/documents/$documentId/download-url")

        assertThat(ticket["downloadUrl"].asText()).startsWith("https://fake-storage.test/")
        assertThat(ticket["expiresAt"].asText()).isNotBlank()
    }

    @Test
    fun `never exposes the storage key`() {
        val token = signIn()
        val projectId = createProject(token)
        val document = upload(token, projectId, "기획서.pdf", PDF_BYTES)

        assertThat(document.has("objectKey")).isFalse()
        assertThat(document.has("storageUrl")).isFalse()
    }

    @Test
    fun `hides documents of a project the caller is not a member of`() {
        val ownerToken = signIn("42", "octocat")
        val strangerToken = signIn("99", "hubot")
        val projectId = createProject(ownerToken)
        upload(ownerToken, projectId, "기획서.pdf", PDF_BYTES)

        val status = statusOf { get(strangerToken, "/api/projects/$projectId/documents") }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /** 티켓 발급 → 저장소에 올림 → 등록까지, 클라이언트가 하는 세 단계를 그대로 지난다. */
    private fun upload(token: String, projectId: String, fileName: String, content: ByteArray) =
        ticketFor(token, projectId, fileName)
            .also { fakeStorage.put(it, content) }
            .let { register(token, projectId, it) }

    private fun ticketFor(token: String, projectId: String, fileName: String): String =
        ticket(
            token,
            projectId,
            """{"fileName":"$fileName","contentType":"application/pdf","sizeBytes":${PDF_BYTES.size}}"""
        )["objectKey"].asText()

    private fun ticket(token: String, projectId: String, body: String) =
        post(token, "/api/projects/$projectId/documents/upload-url", body)

    private fun register(token: String, projectId: String, objectKey: String) =
        post(token, "/api/projects/$projectId/documents", """{"objectKey":"$objectKey"}""")

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"Demo Day","genre":"ACTION"}""")["id"].asText()

    private fun signIn(providerUserId: String = "42", login: String = "octocat"): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        ).block()!!
        return jwtService.issue(user)
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }
}
