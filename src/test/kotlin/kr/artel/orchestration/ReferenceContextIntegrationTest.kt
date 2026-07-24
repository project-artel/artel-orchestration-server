package kr.artel.orchestration

import kr.artel.orchestration.referencecontext.dto.ReferenceContextListResponse
import kr.artel.orchestration.referencecontext.repository.ReferenceContextRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * reference_context 통합 테스트: 내부 엔드포인트(permitAll)로 문서 game_context를 타입별로 저장/조회.
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

    private fun webClient() = WebClient.create("http://localhost:$port")

    private val projectSeq = AtomicLong(5000)

    private fun store(projectId: Long, sourceDocumentId: Long, gameContextJson: String) {
        webClient().put()
            .uri("/api/orchestration/reference-context")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                """{"projectId":$projectId,"sourceDocumentId":$sourceDocumentId,"gameContext":$gameContextJson}"""
            )
            .retrieve()
            .toBodilessEntity()
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
    fun testProjectIsolation() {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        store(projectA, sourceDocumentId = 10, gameContextJson = fullContext)

        assertThat(listByType(projectA, "mechanics").items).hasSize(1)
        assertThat(listByType(projectB, "mechanics").items).isEmpty()
    }
}
