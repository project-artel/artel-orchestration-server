package kr.artel.orchestration.knowledge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
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
import java.time.Instant
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
            append("/api/knowledge?projectId=").append(projectId)
            if (source != null) append("&source=").append(source)
            if (tag != null) append("&tag=").append(tag)
        }
        return webClient().get().uri(query)
            .retrieve()
            .bodyToMono(KnowledgeListResponse::class.java)
            .block(Duration.ofSeconds(5))!!
    }

    @Test
    fun testStoresBatchAndQueriesByProject(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(
            projectId = projectId,
            source = KnowledgeSource.DOCS,
            sourceId = 10,
            contentHash = "abc123",
            items = listOf(item("CONTROL", "이동", "WASD로 이동"), item("RULE", "체력", "최대 100"))
        )

        val all = listByFilters(projectId)
        assertThat(all.items).hasSize(2)
        assertThat(all.items.map { it.tag }).containsExactlyInAnyOrder("CONTROL", "RULE")
        assertThat(all.items.first { it.tag == "CONTROL" }.source).isEqualTo("DOCS")
        assertThat(all.items.first { it.tag == "CONTROL" }.sourceId).isEqualTo("10")
        assertThat(all.items.first { it.tag == "CONTROL" }.contentHash).isEqualTo("abc123")
    }

    @Test
    fun testSkipsInvalidItems(): Unit = runBlocking {
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
        )

        val rows = knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(projectId)
            .toList()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].tag).isEqualTo("RULE")
        assertThat(rows[0].source).isEqualTo("QA")
    }

    @Test
    fun testFiltersBySourceAndTag(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, listOf(item("CONTROL")))
        knowledgeService.store(projectId, KnowledgeSource.QA, 20, null, listOf(item("RULE"), item("CONTROL")))

        assertThat(listByFilters(projectId, source = "qa").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "docs").items).hasSize(1)
        assertThat(listByFilters(projectId, tag = "control").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "qa", tag = "rule").items).hasSize(1)
    }

    @Test
    fun testProjectIsolation(): Unit = runBlocking {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        knowledgeService.store(projectA, KnowledgeSource.DOCS, 10, null, listOf(item("CONTROL")))

        assertThat(listByFilters(projectA).items).hasSize(1)
        assertThat(listByFilters(projectB).items).isEmpty()
    }

    @Test
    fun testEmptyOrAllInvalidBatchStoresNothing(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, emptyList())
        knowledgeService.store(projectId, KnowledgeSource.DOCS, 10, null, listOf(item("BAD")))

        assertThat(listByFilters(projectId).items).isEmpty()
        // enum 값 존재 확인(회귀 방지)
        assertThat(KnowledgeTag.NAMES).containsExactlyInAnyOrder("CONTROL", "RULE", "OBJECTIVE", "UI", "MISC")
        assertThat(KnowledgeSource.NAMES).containsExactlyInAnyOrder("DOCS", "QA")
    }

    /**
     * 소프트삭제(ARTEL-188)가 쓸 `deleted_at`은 ARTEL-185의 마이그레이션에서 미리 만들었다.
     * 삭제 API는 아직 없지만, **읽기 경로가 그 컬럼을 존중하는지는 지금 고정해 둔다** — 나중에
     * 붙이면 그 사이에 머지되는 검색이 삭제된 항목을 계속 돌려주고 그 결함이 조용히 지나간다.
     */
    @Test
    fun testSoftDeletedItemsDisappearFromReads(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        knowledgeService.store(
            projectId, KnowledgeSource.DOCS, 10, null,
            listOf(item("CONTROL", "살아있음"), item("RULE", "지워질 것"))
        )
        assertThat(listByFilters(projectId).items).hasSize(2)

        val doomed = knowledgeRepository.findByProjectIdAndDeletedAtIsNullOrderByIdDesc(projectId)
            .toList()
            .first { it.summary == "지워질 것" }
        knowledgeRepository.save(doomed.copy(deletedAt = Instant.now()))

        val remaining = listByFilters(projectId).items
        assertThat(remaining).hasSize(1)
        assertThat(remaining.single().summary).isEqualTo("살아있음")
        // 필터를 건 조회도 마찬가지여야 한다. 한 곳이라도 빠지면 삭제가 삭제가 아니게 된다.
        assertThat(listByFilters(projectId, tag = "rule").items).isEmpty()
        assertThat(listByFilters(projectId, source = "docs").items).hasSize(1)
    }
}
