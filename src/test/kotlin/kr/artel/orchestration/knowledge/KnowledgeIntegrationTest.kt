package kr.artel.orchestration.knowledge

import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Duration
import java.util.concurrent.atomic.AtomicLong

/**
 * knowledge 통합 테스트: 저장은 서비스([KnowledgeService.store] — docs/QA 파이프라인이 실제로 부르는
 * 지점)로, 조회는 내부 GET 엔드포인트(permitAll)로 검증한다.
 *
 * 검증: 배치 저장(유효 항목만), 무효 항목 스킵(잘못된 tag/빈 필드), source·tag 필터 조회, 프로젝트 격리.
 * project/문서/런은 논리참조라 임의 id로 검증한다(FK 없음).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class KnowledgeIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired
    private lateinit var knowledgeService: KnowledgeService

    @Autowired
    private lateinit var knowledgeRepository: KnowledgeRepository

    private fun webClient() = WebClient.create("http://localhost:$port")

    companion object {
        // JUnit은 테스트 메서드마다 새 인스턴스를 만든다. 인스턴스 필드면 매번 초기화돼 메서드 간
        // projectId가 겹치고, DB는 메서드 사이에 안 비워져 데이터가 섞인다. static으로 두어 실행
        // 전체에서 projectId를 유일하게 만든다.
        private val projectSeq = AtomicLong(9000)
    }

    private fun item(tag: String, summary: String = "s", description: String = "d") =
        KnowledgeIngestItem(tag = tag, summary = summary, description = description)

    private fun listByFilters(projectId: Long, source: String? = null, tag: String? = null): KnowledgeListResponse {
        val query = buildString {
            append("/api/orchestration/knowledge?projectId=").append(projectId)
            if (source != null) append("&source=").append(source)
            if (tag != null) append("&tag=").append(tag)
        }
        return webClient().get().uri(query)
            .retrieve()
            .bodyToMono(KnowledgeListResponse::class.java)
            .block(Duration.ofSeconds(5))!!
    }

    @Test
    fun testStoresBatchAndQueriesByProject() {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(
            projectId = projectId,
            source = KnowledgeSource.DOCS,
            sourceId = 10,
            contentHash = "abc123",
            items = listOf(item("CONTROL", "이동", "WASD로 이동"), item("RULE", "체력", "최대 100"))
        ).block(Duration.ofSeconds(5))

        val all = listByFilters(projectId)
        assertThat(all.items).hasSize(2)
        assertThat(all.items.map { it.tag }).containsExactlyInAnyOrder("CONTROL", "RULE")
        assertThat(all.items.first { it.tag == "CONTROL" }.source).isEqualTo("DOCS")
        assertThat(all.items.first { it.tag == "CONTROL" }.sourceId).isEqualTo("10")
        assertThat(all.items.first { it.tag == "CONTROL" }.contentHash).isEqualTo("abc123")
    }

    @Test
    fun testSkipsInvalidItems() {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(
            projectId = projectId,
            source = KnowledgeSource.QA,
            sourceId = 20,
            contentHash = null,
            items = listOf(
                item("RULE", "유효", "유효 설명"),
                item("NOPE", "잘못된 태그", "무효"),      // 무효 tag → 스킵
                item("MISC", "  ", "빈 summary"),          // 빈 summary → 스킵
                item("CONTROL", "빈 설명", "")             // 빈 description → 스킵
            )
        ).block(Duration.ofSeconds(5))

        val rows = knowledgeRepository.findByProjectIdOrderByIdDesc(projectId)
            .collectList().block(Duration.ofSeconds(5))!!
        assertThat(rows).hasSize(1)
        assertThat(rows[0].tag).isEqualTo("RULE")
        assertThat(rows[0].source).isEqualTo("QA")
    }

    @Test
    fun testFiltersBySourceAndTag() {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, listOf(item("CONTROL"))).block(Duration.ofSeconds(5))
        knowledgeService.store(projectId, KnowledgeSource.QA, 20, null, listOf(item("RULE"), item("CONTROL"))).block(Duration.ofSeconds(5))

        assertThat(listByFilters(projectId, source = "qa").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "docs").items).hasSize(1)
        assertThat(listByFilters(projectId, tag = "control").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "qa", tag = "rule").items).hasSize(1)
    }

    @Test
    fun testProjectIsolation() {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        knowledgeService.store(projectA, KnowledgeSource.DOCS, 10, null, listOf(item("CONTROL"))).block(Duration.ofSeconds(5))

        assertThat(listByFilters(projectA).items).hasSize(1)
        assertThat(listByFilters(projectB).items).isEmpty()
    }

    @Test
    fun testEmptyOrAllInvalidBatchStoresNothing() {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, emptyList()).block(Duration.ofSeconds(5))
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, listOf(item("BAD"))).block(Duration.ofSeconds(5))

        assertThat(listByFilters(projectId).items).isEmpty()
        // enum 값 존재 확인(회귀 방지)
        assertThat(KnowledgeTag.NAMES).containsExactlyInAnyOrder("CONTROL", "RULE", "OBJECTIVE", "UI", "MISC")
        assertThat(KnowledgeSource.NAMES).containsExactlyInAnyOrder("DOCS", "QA")
    }
}
