package kr.artel.orchestration

import kr.artel.orchestration.auth.entity.AppUserEntity
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.project.entity.ParseStatus
import kr.artel.orchestration.project.entity.ProjectDocumentEntity
import kr.artel.orchestration.project.entity.ProjectEntity
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.referencecontext.dto.GameContextPayload
import kr.artel.orchestration.referencecontext.dto.ReferenceContextListResponse
import kr.artel.orchestration.referencecontext.repository.ReferenceContextRepository
import kr.artel.orchestration.referencecontext.service.ReferenceContextService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * reference_context 통합 테스트: 저장은 서비스([ReferenceContextService])로, 조회는 내부 GET
 * 엔드포인트(permitAll)로 검증한다. (저장 PUT은 제거됐고 적재는 업로드 pull 파이프라인이 담당)
 *
 * 검증: 타입별 저장·조회, 문서 재추출 시 교체(멱등), 다른 문서는 별개 행으로 공존, 프로젝트 격리,
 * 빈 섹션은 저장 안 함. project/문서는 논리참조라 임의 id로 검증한다(FK 없음).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ReferenceContextIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var referenceContextRepository: ReferenceContextRepository

    @Autowired
    private lateinit var projectDocumentRepository: ProjectDocumentRepository

    @Autowired
    private lateinit var projectRepository: ProjectRepository

    @Autowired
    private lateinit var appUserRepository: AppUserRepository

    @Autowired
    private lateinit var referenceContextService: ReferenceContextService

    @Autowired
    private lateinit var objectMapper: ObjectMapper

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val projectSeq = AtomicLong(5000)

    /** 저장은 이제 API가 아니라 서비스 호출이다(pull 파이프라인이 실제로 부르는 지점과 동일). */
    private fun store(projectId: Long, sourceDocumentId: Long, gameContextJson: String) {
        val gameContext = objectMapper.readValue(gameContextJson, GameContextPayload::class.java)
        referenceContextService
            .replaceDocumentReferenceContext(projectId, sourceDocumentId, gameContext)
            .block(Duration.ofSeconds(5))
    }

    private fun listByType(projectId: Long, type: String): ReferenceContextListResponse =
        webClient().get()
            .uri("/api/orchestration/reference-context?projectId=$projectId&type=$type")
            .retrieve()
            .bodyToMono(ReferenceContextListResponse::class.java)
            .block(Duration.ofSeconds(5))!!

    private val fullContext = """
        {
          "overview": {"title":"Word Online","genre":"card battle","core_loop":"select-combine-cast"},
          "mechanics": [
            {"name":"Card System","description":"combine cards","rules":["one magic + one attribute"],"preconditions":["hand not empty"]},
            {"name":"Object Interaction","rules":["ground collides with terrain"]}
          ],
          "entities": [
            {"name":"Magic Card","type":"item","attributes":["action: summon"]}
          ]
        }
    """.trimIndent()

    @Test
    fun testStoreAndQueryByType() {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, sourceDocumentId = 10, gameContextJson = fullContext)

        // mechanics 타입 조회 → 문서 10의 mechanics 배열
        val mechanics = listByType(projectId, "mechanics")
        assertThat(mechanics.items).hasSize(1)
        assertThat(mechanics.items[0].sourceDocumentId).isEqualTo(10)
        assertThat(mechanics.items[0].type).isEqualTo("mechanics")
        assertThat(mechanics.items[0].content.isArray).isTrue()
        assertThat(mechanics.items[0].content).hasSize(2)
        assertThat(mechanics.items[0].content[0].get("name").asText()).isEqualTo("Card System")

        // overview는 객체
        val overview = listByType(projectId, "overview")
        assertThat(overview.items).hasSize(1)
        assertThat(overview.items[0].content.get("title").asText()).isEqualTo("Word Online")

        // 빈 섹션(screens)은 저장 안 함
        assertThat(listByType(projectId, "screens").items).isEmpty()
    }

    @Test
    fun testReExtractReplacesDocumentRows() {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, sourceDocumentId = 10, gameContextJson = fullContext)

        // 같은 문서 재추출(mechanics 1개로 축소) → 교체(중복 아님)
        val reExtracted = """{"mechanics":[{"name":"Revised System","rules":["new rule"]}]}"""
        store(projectId, sourceDocumentId = 10, gameContextJson = reExtracted)

        val mechanics = listByType(projectId, "mechanics")
        assertThat(mechanics.items).hasSize(1)
        assertThat(mechanics.items[0].content).hasSize(1)
        assertThat(mechanics.items[0].content[0].get("name").asText()).isEqualTo("Revised System")

        // 이전 overview/entities는 재추출 시 사라짐(문서분 통째 교체)
        assertThat(listByType(projectId, "overview").items).isEmpty()
        assertThat(listByType(projectId, "entities").items).isEmpty()
    }

    @Test
    fun testMultipleDocumentsCoexistPerType() {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, sourceDocumentId = 10, gameContextJson = fullContext)
        store(
            projectId, sourceDocumentId = 20,
            gameContextJson = """{"mechanics":[{"name":"Doc20 Mechanic"}]}"""
        )

        // 같은 mechanics 타입에 두 문서의 항목이 공존
        val mechanics = listByType(projectId, "mechanics")
        assertThat(mechanics.items).hasSize(2)
        assertThat(mechanics.items.map { it.sourceDocumentId }).containsExactlyInAnyOrder(10, 20)
    }

    @Test
    fun testStoreMarksSourceDocumentExtracted() {
        val now = Instant.parse("2026-07-24T00:00:00Z")
        // project_document는 project/app_user FK를 가지므로 실제 행을 먼저 만든다.
        val uploader = appUserRepository.save(
            AppUserEntity(displayName = "Uploader", createdAt = now, updatedAt = now)
        ).block(Duration.ofSeconds(5))!!
        val project = projectRepository.save(
            ProjectEntity(name = "ref-ctx-project", genre = "RPG", createdAt = now, updatedAt = now)
        ).block(Duration.ofSeconds(5))!!
        // 업로드 직후 상태(PENDING)의 문서
        val document = projectDocumentRepository.save(
            ProjectDocumentEntity(
                projectId = project.id!!,
                version = 1,
                objectKey = "projects/${project.id}/doc.pdf",
                fileName = "doc.pdf",
                contentType = "application/pdf",
                sizeBytes = 1024,
                uploadedBy = uploader.id!!,
                uploadedAt = now,
                parseStatus = ParseStatus.PENDING.name
            )
        ).block(Duration.ofSeconds(5))!!

        store(project.id!!, sourceDocumentId = document.id!!, gameContextJson = fullContext)

        // 적재가 끝나면 문서 상태가 EXTRACTED로 올라간다.
        val reloaded = projectDocumentRepository.findById(document.id!!).block(Duration.ofSeconds(5))!!
        assertThat(reloaded.parseStatus).isEqualTo(ParseStatus.EXTRACTED.name)
    }

    @Test
    fun testProjectIsolation() {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        store(projectA, sourceDocumentId = 10, gameContextJson = fullContext)

        assertThat(listByType(projectA, "mechanics").items).hasSize(1)
        assertThat(listByType(projectB, "mechanics").items).isEmpty()
    }
}
