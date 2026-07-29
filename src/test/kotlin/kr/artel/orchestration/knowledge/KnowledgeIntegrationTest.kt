package kr.artel.orchestration.knowledge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.entity.KnowledgeTag
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
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
 * 검증: 배치 저장(유효 항목만), 무효 항목 스킵(잘못된 tag/빈 필드), source·tag 필터 조회, 프로젝트 격리,
 * 그리고 개별 생성·수정·소프트삭제(ARTEL-188) — 삭제가 조회에서 사라지되 행과 출처는 남는지,
 * 수정이 준 필드만 바꾸는지, 다른 프로젝트를 건드릴 수 없는지, 잘못된 요청이 throw 없이 거절되는지.
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

    // ------------------------------------------------------ 개별 생성·수정·삭제 (ARTEL-188)

    @Test
    fun testCreateFromQaTryLandsAsQaSourcedItem(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val qaTryId = 7_001L

        val result = knowledgeService.createFromQaTry(
            projectId, qaTryId,
            KnowledgeMutationRequest(tag = "rule", summary = " 낙하 데미지 ", description = " 5m부터 ")
        )

        assertThat(result).isInstanceOf(KnowledgeMutation.Applied::class.java)
        val created = listByFilters(projectId).items.single()
        assertThat(created.source).isEqualTo("QA")
        // 출처는 그 런이다 — 나중에 "이 항목은 어느 런이 만들었나"를 배치 인입과 같은 방식으로 읽는다.
        assertThat(created.sourceId).isEqualTo(qaTryId.toString())
        assertThat(created.tag).isEqualTo("RULE")
        // 앞뒤 공백은 배치 인입과 같은 규칙으로 다듬는다.
        assertThat(created.summary).isEqualTo("낙하 데미지")
        assertThat(created.description).isEqualTo("5m부터")
    }

    @Test
    fun testSoftDeleteHidesItemButKeepsRowAndProvenance(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val doomed = applied(
            knowledgeService.createFromQaTry(projectId, 10L, mutation(tag = "RULE", summary = "지워질 것"))
        )
        knowledgeService.createFromQaTry(projectId, 10L, mutation(tag = "CONTROL", summary = "살아있음"))

        val result = knowledgeService.softDeleteFromQaTry(projectId, 11L, KnowledgeMutationRequest(knowledgeId = "$doomed"))

        assertThat(result).isInstanceOf(KnowledgeMutation.Applied::class.java)
        assertThat(listByFilters(projectId).items.map { it.summary }).containsExactly("살아있음")

        // 하드 삭제가 아니다: 행은 남고, 누가 지웠는지가 함께 남는다.
        val row = knowledgeRepository.findById(doomed)!!
        assertThat(row.deletedAt).isNotNull()
        assertThat(row.deletedByQaTryId).isEqualTo(11L)

        // 되살리기는 이번 범위가 아니지만, deleted_at을 되돌리는 것만으로 가능해야 한다.
        knowledgeRepository.save(row.copy(deletedAt = null))
        assertThat(listByFilters(projectId).items.map { it.summary })
            .containsExactlyInAnyOrder("살아있음", "지워질 것")
    }

    @Test
    fun testSoftDeleteIsIdempotentAndRejectsUnknownItem(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(knowledgeService.createFromQaTry(projectId, 10L, mutation()))

        assertThat(knowledgeService.softDeleteFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Applied::class.java)
        // 이미 지워진 항목을 다시 지우는 것은 대상 없음으로 거절된다 — 삭제 시각을 덮어써
        // 원래 언제 지워졌는지를 잃지 않는다.
        assertThat(knowledgeService.softDeleteFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat(knowledgeService.softDeleteFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "999999999")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
    }

    @Test
    fun testUpdateChangesOnlyGivenFieldsAndRecordsProvenance(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(
            knowledgeService.createFromQaTry(projectId, 10L, mutation(tag = "RULE", summary = "옛 요약", description = "옛 설명"))
        )

        val result = knowledgeService.updateFromQaTry(
            projectId, 12L,
            KnowledgeMutationRequest(knowledgeId = "$id", summary = "새 요약")
        )

        assertThat(result).isInstanceOf(KnowledgeMutation.Applied::class.java)
        val row = knowledgeRepository.findById(id)!!
        assertThat(row.summary).isEqualTo("새 요약")
        // 주지 않은 필드는 그대로다.
        assertThat(row.description).isEqualTo("옛 설명")
        assertThat(row.tag).isEqualTo("RULE")
        assertThat(row.updatedByQaTryId).isEqualTo(12L)
    }

    /** 다른 프로젝트의 지식창고를 건드릴 수 없어야 한다. 범위는 payload가 아니라 런에서 나온다. */
    @Test
    fun testMutationsCannotReachAnotherProject(): Unit = runBlocking {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        val id = applied(knowledgeService.createFromQaTry(projectA, 10L, mutation(summary = "A의 지식")))

        assertThat(knowledgeService.softDeleteFromQaTry(projectB, 20L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat(
            knowledgeService.updateFromQaTry(projectB, 20L, KnowledgeMutationRequest(knowledgeId = "$id", summary = "탈취"))
        ).isInstanceOf(KnowledgeMutation.Rejected::class.java)

        val row = knowledgeRepository.findById(id)!!
        assertThat(row.deletedAt).isNull()
        assertThat(row.summary).isEqualTo("A의 지식")
    }

    @Test
    fun testInvalidMutationRequestsAreRejectedAsValues(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(knowledgeService.createFromQaTry(projectId, 10L, mutation()))

        val rejections = listOf(
            knowledgeService.createFromQaTry(projectId, 10L, mutation(tag = "NOPE")),
            knowledgeService.createFromQaTry(projectId, 10L, mutation(summary = "  ")),
            knowledgeService.createFromQaTry(projectId, 10L, KnowledgeMutationRequest(tag = "RULE", summary = "s")),
            knowledgeService.updateFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "abc", summary = "s")),
            knowledgeService.updateFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = null, summary = "s")),
            // 바꿀 필드가 하나도 없는 수정은 임베딩만 헛되이 무효화하므로 거절한다.
            knowledgeService.updateFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")),
            knowledgeService.updateFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id", tag = "NOPE")),
            knowledgeService.updateFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id", summary = " ")),
            knowledgeService.softDeleteFromQaTry(projectId, 10L, KnowledgeMutationRequest(knowledgeId = null))
        )

        assertThat(rejections).allMatch { it is KnowledgeMutation.Rejected }
        // 거절은 값이라 어느 것도 저장을 바꾸지 않았다.
        assertThat(listByFilters(projectId).items).hasSize(1)
    }

    private fun mutation(
        tag: String = "RULE",
        summary: String = "요약",
        description: String = "설명"
    ) = KnowledgeMutationRequest(tag = tag, summary = summary, description = description)

    private fun applied(result: KnowledgeMutation): Long =
        (result as KnowledgeMutation.Applied).knowledgeId
}
