package kr.artel.orchestration.project

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeService
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
    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var knowledgeEventRepository: KnowledgeEventRepository

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    @BeforeEach
    fun clean(): Unit = runBlocking {
        knowledgeEventRepository.deleteAll()
        knowledgeRepository.deleteAll()
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        fakeStorage.clear()
    }

    @Test
    fun `uploads a planning document and makes it the current version`(): Unit = runBlocking {
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
    fun `stacks versions instead of replacing the previous document`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)

        upload(token, projectId, "기획서.pdf", PDF_BYTES)
        // 버전 누적은 "내용이 다른 개정본"에서 일어난다(같은 파일 재업로드는 dedup으로 409).
        val revised = "%PDF-1.7\nrevised body".toByteArray(Charsets.US_ASCII)
        val second = upload(token, projectId, "기획서.pdf", revised)

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

    /**
     * 동시에 등록해도 두 행이 같은 버전을 갖지 않아야 한다.
     *
     * MAX(version) + 1은 읽고 쓰는 사이에 경합이 난다. 유니크 제약이 그 충돌을 예외로 만들고
     * 서비스가 최대 버전을 다시 읽어 재시도한다. 직렬화되든 실제로 부딪히든 결과는 1과 2여야 한다.
     */
    @Test
    fun `assigns distinct versions to concurrent uploads`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)

        // 내용을 다르게 해야 dedup에 안 걸리고 버전 채번 경합만 검증된다(같은 파일이면 409).
        val keys = (1..2).map { index ->
            val content = "%PDF-1.7\nconcurrent body $index".toByteArray(Charsets.US_ASCII)
            ticketFor(token, projectId, "동시$index.pdf").also { fakeStorage.put(it, content) }
        }

        val failures = mutableListOf<Throwable>()
        val threads = keys.map { key ->
            Thread {
                runCatching { register(token, projectId, key) }
                    .onFailure { synchronized(failures) { failures += it } }
            }
        }
        threads.forEach(Thread::start)
        threads.forEach(Thread::join)

        assertThat(failures).isEmpty()

        val versions = get(token, "/api/projects/$projectId/documents").map { it["version"].asInt() }
        assertThat(versions).containsExactlyInAnyOrder(2, 1)
    }

    @Test
    fun `rejects a ticket request for anything other than a pdf`(): Unit = runBlocking {
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
    fun `rejects a ticket request above the size limit`(): Unit = runBlocking {
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
    fun `refuses to register an object that was never uploaded`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "기획서.pdf")

        // 업로드를 건너뛴 채 바로 등록한다.
        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(documentRepository.count()).isZero()
    }

    @Test
    fun `refuses to register bytes that are not actually a pdf`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "위장.pdf")
        // Content-Type은 application/pdf라고 신고했지만 내용은 PDF가 아니다.
        fakeStorage.put(objectKey, NOT_PDF_BYTES)

        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(documentRepository.count()).isZero()
    }

    @Test
    fun `refuses to register an empty object`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val objectKey = ticketFor(token, projectId, "빈파일.pdf")
        fakeStorage.put(objectKey, ByteArray(0))

        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `refuses an object key that belongs to another project`(): Unit = runBlocking {
        val token = signIn()
        val mine = createProject(token)
        val other = createProject(token)
        val otherKey = ticketFor(token, other, "남의기획서.pdf")
        fakeStorage.put(otherKey, PDF_BYTES)

        val status = statusOf { register(token, mine, otherKey) }

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `issues a fresh download url instead of storing a permanent link`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES)["id"].asText()

        val ticket = get(token, "/api/projects/$projectId/documents/$documentId/download-url")

        assertThat(ticket["downloadUrl"].asText()).startsWith("https://fake-storage.test/")
        assertThat(ticket["expiresAt"].asText()).isNotBlank()
    }

    @Test
    fun `never exposes the storage key`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val document = upload(token, projectId, "기획서.pdf", PDF_BYTES)

        assertThat(document.has("objectKey")).isFalse()
        assertThat(document.has("storageUrl")).isFalse()
    }

    @Test
    fun `hides documents of a project the caller is not a member of`(): Unit = runBlocking {
        val ownerToken = signIn("42", "octocat")
        val strangerToken = signIn("99", "hubot")
        val projectId = createProject(ownerToken)
        upload(ownerToken, projectId, "기획서.pdf", PDF_BYTES)

        val status = statusOf { get(strangerToken, "/api/projects/$projectId/documents") }

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `rejects re-uploading the same file to the same project`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        upload(token, projectId, "기획서.pdf", PDF_BYTES)

        // 같은 내용을 다른 이름으로 다시 올려도 프로젝트 단위 hash 중복 → 409
        val objectKey = ticketFor(token, projectId, "기획서-복사본.pdf").also { fakeStorage.put(it, PDF_BYTES) }
        val status = statusOf { register(token, projectId, objectKey) }

        assertThat(status).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `allows the same file in a different project`(): Unit = runBlocking {
        val token = signIn()
        val projectA = createProject(token)
        val projectB = createProject(token)
        upload(token, projectA, "기획서.pdf", PDF_BYTES)

        // 다른 프로젝트(=파일 공유)면 같은 내용도 허용된다.
        val document = upload(token, projectB, "기획서.pdf", PDF_BYTES)
        assertThat(document["id"].asText()).isNotBlank()
    }

    @Test
    fun `allows a different file in the same project`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        upload(token, projectId, "v1.pdf", PDF_BYTES)

        // 내용이 다르면 hash가 달라 허용된다(버전 누적).
        val other = "%PDF-1.7\ndifferent body".toByteArray(Charsets.US_ASCII)
        val document = upload(token, projectId, "v2.pdf", other)
        assertThat(document["version"].asInt()).isEqualTo(2)
    }

    /**
     * 정리 delete가 403 등으로 실패해도 응답은 여전히 409여야 한다.
     * S3 IAM 정책에서 `s3:DeleteObject`가 빠져 이 delete가 막혔던 실 배포 사례를 재현한다.
     */
    @Test
    fun `rejects a duplicate upload with 409 even when the cleanup delete fails`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        upload(token, projectId, "기획서.pdf", PDF_BYTES)
        fakeStorage.failDeletes()

        val objectKey = ticketFor(token, projectId, "기획서-복사본.pdf").also { fakeStorage.put(it, PDF_BYTES) }
        val response = errorBodyOf { register(token, projectId, objectKey) }

        assertThat(response.status).isEqualTo(HttpStatus.CONFLICT)
        assertThat(response.body["code"].asText()).isEqualTo("duplicate_document")
    }

    @Test
    fun `deletes a document and removes its row`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES)["id"].asText()

        val status = delete(token, "/api/projects/$projectId/documents/$documentId")

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        assertThat(documentRepository.count()).isZero()
    }

    @Test
    fun `allows re-registering the same file after its previous version was deleted`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token)
        val documentId = upload(token, projectId, "기획서.pdf", PDF_BYTES)["id"].asText()
        delete(token, "/api/projects/$projectId/documents/$documentId")

        // 삭제로 uk_project_document_project_hash가 풀렸으므로 같은 내용을 다시 올릴 수 있다.
        val document = upload(token, projectId, "기획서.pdf", PDF_BYTES)

        assertThat(document["version"].asInt()).isEqualTo(1)
    }

    @Test
    fun `refuses to delete another project's document`(): Unit = runBlocking {
        val token = signIn()
        val mine = createProject(token)
        val other = createProject(token)
        val documentId = upload(token, other, "남의기획서.pdf", PDF_BYTES)["id"].asText()

        val status = delete(token, "/api/projects/$mine/documents/$documentId")

        assertThat(status).isEqualTo(HttpStatus.NOT_FOUND)
    }

    @Test
    fun `soft deletes the document's knowledge with a delete event carrying a null qa_try_id`(): Unit = runBlocking {
        val token = signIn()
        val projectId = createProject(token).toLong()
        val documentId = upload(token, projectId.toString(), "기획서.pdf", PDF_BYTES)["id"].asText().toLong()
        // 추출 파이프라인은 테스트에서 꺼져 있으므로(artel.agent.extract.enabled=false),
        // 문서가 만든 knowledge를 KnowledgeService.store로 직접 재현한다.
        knowledgeService.store(
            projectId = projectId,
            scope = KnowledgeScope.PRODUCTION,
            source = KnowledgeSource.DOCS,
            sourceId = documentId,
            contentHash = "hash",
            items = listOf(KnowledgeIngestItem(tag = "RULE", summary = "체력", description = "최대 100"))
        )
        val knowledgeId = knowledgeRepository.findVisible(projectId, null, null, null).toList().single().id!!

        delete(token, "/api/projects/$projectId/documents/$documentId")

        val row = knowledgeRepository.findById(knowledgeId)!!
        assertThat(row.deletedAt).isNotNull()

        val deleteEvent = knowledgeEventRepository.findByKnowledgeIdOrderByIdAsc(knowledgeId).toList()
            .single { it.event == "DELETE" }
        assertThat(deleteEvent.qaTryId).isNull()
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

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    /** DELETE는 성공하면 본문 없는 204다. 실패도 상태코드로만 확인한다. */
    private fun delete(token: String, uri: String): HttpStatus =
        try {
            val response = client().delete().uri(uri).cookie("artel_access_token", token)
                .retrieve().toBodilessEntity().block()!!
            HttpStatus.valueOf(response.statusCode.value())
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun client() = WebClient.create("http://localhost:$port")

    private fun statusOf(call: () -> Any?): HttpStatus =
        try {
            call()
            HttpStatus.OK
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    /** 오류 응답의 상태코드와 본문을 함께 확인해야 하는 테스트용. [call]이 성공하면 실패시킨다. */
    private fun errorBodyOf(call: () -> Any?): ErrorResponse {
        try {
            call()
        } catch (error: WebClientResponseException) {
            return ErrorResponse(
                HttpStatus.valueOf(error.statusCode.value()),
                objectMapper.readTree(error.responseBodyAsString)
            )
        }
        error("호출이 성공했다 — 오류 응답을 기대했다.")
    }

    private data class ErrorResponse(val status: HttpStatus, val body: JsonNode)
}
