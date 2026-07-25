package kr.artel.orchestration

import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ParseStatus
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.storage.PresignedDownload
import kr.artel.orchestration.project.storage.PresignedUpload
import kr.artel.orchestration.project.storage.StoredObject
import kr.artel.orchestration.referencecontext.repository.ReferenceContextRepository
import kr.artel.orchestration.referencecontext.service.ReferenceContextExtractionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterAll
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.test.context.DynamicPropertyRegistry
import org.springframework.test.context.DynamicPropertySource
import reactor.core.publisher.Mono
import reactor.netty.DisposableServer
import reactor.netty.http.server.HttpServer
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * pull 파이프라인 통합 테스트: 코디네이터가 mock Agent `/extract`를 호출해 game_context를 받아
 * reference_context에 적재하고 parse_status를 갱신하는지 검증.
 *
 * mock Agent는 filename에 "fail"이 있으면 500을 돌려준다(실패 경로 검증용).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceContextExtractionIntegrationTest {

    @Autowired
    private lateinit var extractionService: ReferenceContextExtractionService

    @Autowired
    private lateinit var projectDocumentRepository: ProjectDocumentRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var referenceContextRepository: ReferenceContextRepository

    private val seq = AtomicLong(7000)

    private fun seedDocument(fileName: String): ProjectDocumentEntity {
        val now = Instant.parse("2026-07-25T00:00:00Z")
        val uploader = appUserRepository.save(
            AppUserEntity(displayName = "Uploader", createdAt = now, updatedAt = now)
        ).block(Duration.ofSeconds(5))!!
        val project = projectRepository.save(
            ProjectEntity(name = "extract-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        ).block(Duration.ofSeconds(5))!!
        return projectDocumentRepository.save(
            ProjectDocumentEntity(
                projectId = project.id!!,
                version = 1,
                objectKey = "projects/${project.id}/$fileName",
                fileName = fileName,
                contentType = "application/pdf",
                sizeBytes = 2048,
                uploadedBy = uploader.id!!,
                uploadedAt = now,
                parseStatus = ParseStatus.PENDING.name
            )
        ).block(Duration.ofSeconds(5))!!
    }

    @Test
    fun testExtractStoresReferenceContextAndMarksExtracted() {
        val document = seedDocument("design.pdf")

        extractionService.extractAndStoreForDocument(document).block(Duration.ofSeconds(30))

        // mock이 준 mechanics가 타입별로 적재됐는지
        val rows = referenceContextRepository
            .findByProjectIdAndType(document.projectId, "mechanics")
            .collectList().block(Duration.ofSeconds(5))!!
        assertThat(rows).hasSize(1)
        assertThat(rows[0].sourceDocumentId).isEqualTo(document.id)

        val reloaded = projectDocumentRepository.findById(document.id!!).block(Duration.ofSeconds(5))!!
        assertThat(reloaded.parseStatus).isEqualTo(ParseStatus.EXTRACTED.name)
    }

    @Test
    fun testExtractFailureMarksFailedAndStoresNothing() {
        val document = seedDocument("fail.pdf") // mock이 500 반환

        extractionService.extractAndStoreForDocument(document).block(Duration.ofSeconds(30))

        val rows = referenceContextRepository
            .findByProjectId(document.projectId)
            .collectList().block(Duration.ofSeconds(5))!!
        assertThat(rows).isEmpty()

        val reloaded = projectDocumentRepository.findById(document.id!!).block(Duration.ofSeconds(5))!!
        assertThat(reloaded.parseStatus).isEqualTo(ParseStatus.FAILED.name)
    }

    /** 실제 S3 대신 서명 URL만 흉내내는 저장소(코디네이터는 URL 문자열만 필요). */
    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = object : DocumentStorage {
            override fun presignUpload(objectKey: String, contentType: String, contentLength: Long) =
                PresignedUpload("https://fake/$objectKey", emptyMap(), Instant.parse("2030-01-01T00:00:00Z"))

            override fun presignDownload(objectKey: String, fileName: String) =
                PresignedDownload("https://fake/$objectKey?sig=x", Instant.parse("2030-01-01T00:00:00Z"))

            override fun head(objectKey: String): Mono<StoredObject> = Mono.empty()
            override fun readPrefix(objectKey: String, length: Int): Mono<ByteArray> = Mono.empty()
            override fun delete(objectKey: String): Mono<Void> = Mono.empty()
        }
    }

    companion object {
        private lateinit var mockAgent: DisposableServer

        private const val GAME_CONTEXT_JSON =
            """{"filename":"design.pdf","game_context":{"overview":{"title":"T"},"mechanics":[{"name":"Card System","rules":["r1"]}]}}"""

        @JvmStatic
        @DynamicPropertySource
        fun agentProps(registry: DynamicPropertyRegistry) {
            mockAgent = HttpServer.create().port(0).route { routes ->
                routes.post("/extract") { request, response ->
                    request.receive().aggregate().asString().defaultIfEmpty("").flatMap { body ->
                        if (body.contains("fail")) {
                            response.status(500).send().then()
                        } else {
                            response.header("Content-Type", "application/json")
                                .sendString(Mono.just(GAME_CONTEXT_JSON)).then()
                        }
                    }
                }
            }.bindNow()
            registry.add("artel.agent.base-url") { "http://localhost:${mockAgent.port()}" }
            registry.add("artel.agent.extract.enabled") { "true" }
            registry.add("artel.agent.extract.timeout") { "PT20S" }
        }

        @JvmStatic
        @AfterAll
        fun tearDown() {
            mockAgent.disposeNow()
        }
    }
}
