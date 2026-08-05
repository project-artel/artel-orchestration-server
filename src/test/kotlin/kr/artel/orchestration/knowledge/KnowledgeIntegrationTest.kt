package kr.artel.orchestration.knowledge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeListResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.dto.KnowledgeResponse
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
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
 * 개별 생성·수정·소프트삭제(ARTEL-188) — 삭제가 조회에서 사라지되 행과 출처는 남는지, 수정이 준 필드만
 * 바꾸는지, 다른 프로젝트를 건드릴 수 없는지, 잘못된 요청이 throw 없이 거절되는지 — 그리고 스코프
 * 격리(ARTEL-256, 파일 아래쪽 절).
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

        /** 스코프 런 두 개. 값 자체는 아무 의미가 없다 — 서로 다르고 NULL이 아니면 된다. */
        private val SCOPE_A = KnowledgeScope.of(4_001L)
        private val SCOPE_B = KnowledgeScope.of(4_002L)
    }

    private fun item(tag: String, summary: String = "s", description: String = "d") =
        KnowledgeIngestItem(tag = tag, summary = summary, description = description)

    // 서비스는 스코프를 기본값 없이 요구한다(그래야 프로덕션 코드에서 빠뜨릴 수 없다). 테스트에서까지
    // 매번 적으면 정작 스코프가 다른 자리가 눈에 띄지 않으므로, 여기서만 운영을 기본값으로 준다.

    private suspend fun store(
        projectId: Long,
        source: KnowledgeSource,
        sourceId: Long?,
        items: List<KnowledgeIngestItem>,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION,
        contentHash: String? = null
    ) = knowledgeService.store(projectId, scope, source, sourceId, contentHash, items)

    private suspend fun create(
        projectId: Long,
        qaTryId: Long,
        request: KnowledgeMutationRequest,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) = knowledgeService.createFromQaTry(projectId, scope, qaTryId, request)

    private suspend fun update(
        projectId: Long,
        qaTryId: Long,
        request: KnowledgeMutationRequest,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) = knowledgeService.updateFromQaTry(projectId, scope, qaTryId, request)

    private suspend fun remove(
        projectId: Long,
        qaTryId: Long,
        request: KnowledgeMutationRequest,
        scope: KnowledgeScope = KnowledgeScope.PRODUCTION
    ) = knowledgeService.softDeleteFromQaTry(projectId, scope, qaTryId, request)

    /** 한 스코프가 실제로 보는 목록. HTTP 조회는 언제나 운영이라 스코프 검증에는 서비스를 쓴다. */
    private suspend fun visible(projectId: Long, scope: KnowledgeScope): List<KnowledgeResponse> =
        knowledgeService.findForProject(projectId, scope, null, null).items

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
        store(
            projectId, KnowledgeSource.DOCS, 10,
            listOf(item("CONTROL", "이동", "WASD로 이동"), item("RULE", "체력", "최대 100")),
            contentHash = "abc123"
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
        store(
            projectId, KnowledgeSource.QA, 20,
            listOf(
                item("RULE", "유효", "유효 설명"),
                item("NOPE", "잘못된 태그", "무효"),      // 무효 tag → 스킵
                item("MISC", "  ", "빈 summary"),          // 빈 summary → 스킵
                item("CONTROL", "빈 설명", "")             // 빈 description → 스킵
            )
        )

        val rows = knowledgeRepository.findVisible(projectId, null, null, null).toList()
        assertThat(rows).hasSize(1)
        assertThat(rows[0].tag).isEqualTo("RULE")
        assertThat(rows[0].source).isEqualTo("QA")
    }

    @Test
    fun testFiltersBySourceAndTag(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL")))
        store(projectId, KnowledgeSource.QA, 20, listOf(item("RULE"), item("CONTROL")))

        assertThat(listByFilters(projectId, source = "qa").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "docs").items).hasSize(1)
        assertThat(listByFilters(projectId, tag = "control").items).hasSize(2)
        assertThat(listByFilters(projectId, source = "qa", tag = "rule").items).hasSize(1)
    }

    @Test
    fun testProjectIsolation(): Unit = runBlocking {
        val projectA = projectSeq.incrementAndGet()
        val projectB = projectSeq.incrementAndGet()
        store(projectA, KnowledgeSource.DOCS, 10, listOf(item("CONTROL")))

        assertThat(listByFilters(projectA).items).hasSize(1)
        assertThat(listByFilters(projectB).items).isEmpty()
    }

    @Test
    fun testEmptyOrAllInvalidBatchStoresNothing(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, emptyList())
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("BAD")))

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
        store(
            projectId, KnowledgeSource.DOCS, 10,
            listOf(item("CONTROL", "살아있음"), item("RULE", "지워질 것"))
        )
        assertThat(listByFilters(projectId).items).hasSize(2)

        val doomed = knowledgeRepository.findVisible(projectId, null, null, null)
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

        val result = create(
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
        val doomed = applied(create(projectId, 10L, mutation(tag = "RULE", summary = "지워질 것")))
        create(projectId, 10L, mutation(tag = "CONTROL", summary = "살아있음"))

        val result = remove(projectId, 11L, KnowledgeMutationRequest(knowledgeId = "$doomed"))

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
        val id = applied(create(projectId, 10L, mutation()))

        assertThat(remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Applied::class.java)
        // 이미 지워진 항목을 다시 지우는 것은 대상 없음으로 거절된다 — 삭제 시각을 덮어써
        // 원래 언제 지워졌는지를 잃지 않는다.
        assertThat(remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat(remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "999999999")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
    }

    @Test
    fun testUpdateChangesOnlyGivenFieldsAndRecordsProvenance(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(create(projectId, 10L, mutation(tag = "RULE", summary = "옛 요약", description = "옛 설명")))

        val result = update(projectId, 12L, KnowledgeMutationRequest(knowledgeId = "$id", summary = "새 요약"))

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
        val id = applied(create(projectA, 10L, mutation(summary = "A의 지식")))

        assertThat(remove(projectB, 20L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat(update(projectB, 20L, KnowledgeMutationRequest(knowledgeId = "$id", summary = "탈취")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)

        val row = knowledgeRepository.findById(id)!!
        assertThat(row.deletedAt).isNull()
        assertThat(row.summary).isEqualTo("A의 지식")
    }

    @Test
    fun testInvalidMutationRequestsAreRejectedAsValues(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(create(projectId, 10L, mutation()))

        val rejections = listOf(
            create(projectId, 10L, mutation(tag = "NOPE")),
            create(projectId, 10L, mutation(summary = "  ")),
            create(projectId, 10L, KnowledgeMutationRequest(tag = "RULE", summary = "s")),
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "abc", summary = "s")),
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = null, summary = "s")),
            // 바꿀 필드가 하나도 없는 수정은 임베딩만 헛되이 무효화하므로 거절한다.
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id")),
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id", tag = "NOPE")),
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$id", summary = " ")),
            remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = null))
        )

        assertThat(rejections).allMatch { it is KnowledgeMutation.Rejected }
        // 거절은 값이라 어느 것도 저장을 바꾸지 않았다.
        assertThat(listByFilters(projectId).items).hasSize(1)
    }

    // -------------------------------------------------------------- 스코프 격리 (ARTEL-256)

    /**
     * copy-on-write의 첫 절반: 스코프 런은 운영 지식(baseline)을 그대로 읽는다. 복사하지 않는
     * 설계라 baseline은 한 벌뿐이고, 스코프 런은 그것을 자기 것과 겹쳐 본다.
     */
    @Test
    fun `스코프 런은 baseline을 읽는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL", "운영 지식")))

        assertThat(visible(projectId, SCOPE_A).map { it.summary }).containsExactly("운영 지식")
    }

    /**
     * 두 번째 절반: 스코프 런의 쓰기는 운영으로 새지 않는다. 이것이 없으면 실험이 운영 지식창고를
     * 오염시키고, 실험이 끝나도 되돌릴 방법이 없다.
     */
    @Test
    fun `스코프 런이 쓴 것은 운영에 보이지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        create(projectId, 10L, mutation(summary = "A만의 지식"), scope = SCOPE_A)

        assertThat(visible(projectId, SCOPE_A).map { it.summary }).containsExactly("A만의 지식")
        assertThat(visible(projectId, KnowledgeScope.PRODUCTION)).isEmpty()
        // HTTP 조회도 운영만 본다.
        assertThat(listByFilters(projectId).items).isEmpty()
    }

    /**
     * 순서 효과를 막는 조건이다. 이것이 깨지면 나중에 돈 arm이 앞선 arm의 지식을 물려받아, 점수
     * 차이가 설정 때문인지 실행 순서 때문인지 갈리지 않는다.
     */
    @Test
    fun `스코프 A가 쓴 것은 스코프 B에 보이지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        create(projectId, 10L, mutation(summary = "A의 발견"), scope = SCOPE_A)
        create(projectId, 20L, mutation(summary = "B의 발견"), scope = SCOPE_B)

        assertThat(visible(projectId, SCOPE_A).map { it.summary }).containsExactly("A의 발견")
        assertThat(visible(projectId, SCOPE_B).map { it.summary }).containsExactly("B의 발견")
    }

    /** 배치 인입(`KNOWLEDGE` 프레임)도 개별 생성과 같은 스코프 규칙을 따라야 한다. */
    @Test
    fun `배치 인입도 스코프를 따른다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.QA, 10, listOf(item("RULE", "A의 배치")), scope = SCOPE_A)

        assertThat(visible(projectId, SCOPE_A).map { it.summary }).containsExactly("A의 배치")
        assertThat(visible(projectId, KnowledgeScope.PRODUCTION)).isEmpty()
        assertThat(visible(projectId, SCOPE_B)).isEmpty()
    }

    /**
     * 그림자 삭제. 스코프 런이 baseline을 지우면 **그 런에서만** 사라지고 운영 행은 그대로 남아야
     * 한다. 원본을 직접 지우면 운영 지식창고가 실험 때문에 깎여나가고 되돌아오지 않는다.
     */
    @Test
    fun `스코프 런이 baseline을 지우면 그 런에서만 사라진다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL", "운영 지식")))
        val baseline = knowledgeRepository.findVisible(projectId, null, null, null).toList().single()
        val baselineId = requireNotNull(baseline.id)

        val result = remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$baselineId"), scope = SCOPE_A)
        assertThat(result).isInstanceOf(KnowledgeMutation.Applied::class.java)

        // A에서는 사라진다. 운영과 다른 스코프에는 그대로 있다.
        assertThat(visible(projectId, SCOPE_A)).isEmpty()
        assertThat(visible(projectId, KnowledgeScope.PRODUCTION).map { it.summary }).containsExactly("운영 지식")
        assertThat(visible(projectId, SCOPE_B).map { it.summary }).containsExactly("운영 지식")

        // 원본 행 자체는 손대지 않았다 — 툼스톤이 별도 행으로 생겼을 뿐이다.
        assertThat(knowledgeRepository.findById(baselineId)!!.deletedAt).isNull()
        val tombstone = knowledgeRepository.findShadow(requireNotNull(SCOPE_A.id), baselineId)!!
        assertThat(tombstone.deletedAt).isNotNull()
        assertThat(tombstone.deletedByQaTryId).isEqualTo(10L)
    }

    /**
     * 그림자 수정. 이 테스트의 요점은 **개수**다 — 읽기 질의가 가려진 baseline을 빼지 않으면
     * 원본과 수정본이 둘 다 나오고, 그 증상은 "결과가 두 개"라 오류로 드러나지 않는다.
     */
    @Test
    fun `스코프 런이 baseline을 고치면 그 런에는 수정본 하나만 보인다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL", "옛 요약", "옛 설명")))
        val baselineId = requireNotNull(
            knowledgeRepository.findVisible(projectId, null, null, null).toList().single().id
        )

        update(
            projectId, 10L,
            KnowledgeMutationRequest(knowledgeId = "$baselineId", summary = "A가 고친 요약"),
            scope = SCOPE_A
        )

        val inScope = visible(projectId, SCOPE_A)
        assertThat(inScope).hasSize(1)
        assertThat(inScope.single().summary).isEqualTo("A가 고친 요약")
        // 그림자는 원본을 통째로 대신한다. 주지 않은 필드가 사라지면 안 된다.
        assertThat(inScope.single().description).isEqualTo("옛 설명")
        assertThat(inScope.single().tag).isEqualTo("CONTROL")

        // 운영과 다른 스코프는 원본 그대로다.
        assertThat(visible(projectId, KnowledgeScope.PRODUCTION).single().summary).isEqualTo("옛 요약")
        assertThat(visible(projectId, SCOPE_B).single().summary).isEqualTo("옛 요약")
        assertThat(knowledgeRepository.findById(baselineId)!!.summary).isEqualTo("옛 요약")
    }

    /**
     * 그림자를 한 번 만든 뒤에는 그 그림자가 대상이 된다. 다시 그림자를 만들면 같은 baseline이
     * 두 번 가려져 읽기가 중복을 낸다.
     */
    @Test
    fun `그림자를 다시 고치면 새 그림자를 만들지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL", "옛 요약")))
        val baselineId = requireNotNull(
            knowledgeRepository.findVisible(projectId, null, null, null).toList().single().id
        )

        val shadowId = applied(
            update(
                projectId, 10L,
                KnowledgeMutationRequest(knowledgeId = "$baselineId", summary = "1차 수정"),
                scope = SCOPE_A
            )
        )
        // 두 번째 수정은 그림자의 id를 대상으로 한다 — 검색이 돌려주는 것이 그 id다.
        update(
            projectId, 10L,
            KnowledgeMutationRequest(knowledgeId = "$shadowId", summary = "2차 수정"),
            scope = SCOPE_A
        )

        val inScope = visible(projectId, SCOPE_A)
        assertThat(inScope).hasSize(1)
        assertThat(inScope.single().summary).isEqualTo("2차 수정")
    }

    /**
     * 옛 baseline id로 다시 부르면 거절하되, **새 id를 알려 준다.** 그냥 "없음"으로 답하면 Agent가
     * 방금 자기가 고친 항목을 옛 id로 계속 다시 부르며 런을 태운다.
     */
    @Test
    fun `가려진 baseline을 다시 건드리면 새 id를 알려 주며 거절한다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        store(projectId, KnowledgeSource.DOCS, 10, listOf(item("CONTROL", "원본"), item("RULE", "지울 것")))
        val rows = knowledgeRepository.findVisible(projectId, null, null, null).toList()
        val edited = requireNotNull(rows.first { it.summary == "원본" }.id)
        val doomed = requireNotNull(rows.first { it.summary == "지울 것" }.id)

        val shadowId = applied(
            update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$edited", summary = "수정본"), SCOPE_A)
        )
        remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$doomed"), scope = SCOPE_A)

        val reEdit = update(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$edited", summary = "또"), SCOPE_A)
        assertThat((reEdit as KnowledgeMutation.Rejected).reason).contains("$shadowId")

        val reDelete = remove(projectId, 10L, KnowledgeMutationRequest(knowledgeId = "$doomed"), scope = SCOPE_A)
        assertThat((reDelete as KnowledgeMutation.Rejected).reason).contains("already deleted")
    }

    /** 스코프 런은 다른 스코프의 항목을 볼 수도, 고칠 수도 없다. */
    @Test
    fun `스코프 런은 다른 스코프의 항목을 건드릴 수 없다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(create(projectId, 10L, mutation(summary = "A의 것"), scope = SCOPE_A))

        assertThat(update(projectId, 20L, KnowledgeMutationRequest(knowledgeId = "$id", summary = "탈취"), SCOPE_B))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        assertThat(remove(projectId, 20L, KnowledgeMutationRequest(knowledgeId = "$id"), scope = SCOPE_B))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)
        // 운영 런도 마찬가지다 — 실험 지식은 운영에 존재하지 않는 것으로 보여야 한다.
        assertThat(remove(projectId, 30L, KnowledgeMutationRequest(knowledgeId = "$id")))
            .isInstanceOf(KnowledgeMutation.Rejected::class.java)

        assertThat(knowledgeRepository.findById(id)!!.summary).isEqualTo("A의 것")
    }

    /**
     * 회귀 방어. 운영 런(scope NULL)의 수정·삭제는 **그림자를 만들지 않고** 원본을 직접 바꾼다.
     * 이 변경 전과 동작이 완전히 같아야 한다.
     */
    @Test
    fun `운영 런의 수정과 삭제는 그림자를 만들지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = applied(create(projectId, 10L, mutation(summary = "옛 요약")))

        assertThat(applied(update(projectId, 11L, KnowledgeMutationRequest(knowledgeId = "$id", summary = "새 요약"))))
            .isEqualTo(id)
        assertThat(knowledgeRepository.findById(id)!!.summary).isEqualTo("새 요약")

        assertThat(applied(remove(projectId, 12L, KnowledgeMutationRequest(knowledgeId = "$id")))).isEqualTo(id)
        val row = knowledgeRepository.findById(id)!!
        assertThat(row.deletedAt).isNotNull()
        assertThat(row.shadowsId).isNull()
        assertThat(row.scopeId).isNull()
    }

    private fun mutation(
        tag: String = "RULE",
        summary: String = "요약",
        description: String = "설명"
    ) = KnowledgeMutationRequest(tag = tag, summary = summary, description = description)

    private fun applied(result: KnowledgeMutation): Long =
        (result as KnowledgeMutation.Applied).knowledgeId
}
