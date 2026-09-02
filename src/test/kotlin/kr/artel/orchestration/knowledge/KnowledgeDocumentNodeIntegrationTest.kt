package kr.artel.orchestration.knowledge

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeLinkRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.repository.KnowledgeEdgeRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeGraphMutation
import kr.artel.orchestration.knowledge.service.KnowledgeGraphService
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicLong

/**
 * 문서 node와 `PART_OF` edge 적재 검증(ARTEL-748).
 *
 * 여기서 지키는 성질은 넷이다.
 *
 * 1. **DOCS 배치는 항목마다 문서 node로 향하는 `PART_OF` edge를 만든다.** 문서 node는
 *    `source=DOCS`, `source_id=documentId`, `summary`=파일 이름인 knowledge 행이다.
 * 2. **재적재해도 문서 node는 하나다.** 문서 node와 그 배치의 항목은 `source`/`source_id`가
 *    같아 그 값만으로는 구분할 수 없으므로, 그래프 구조(살아있는 `PART_OF` edge의 도착점)로
 *    식별한 결과가 재적재에도 안정적인지가 이 파일의 핵심이다.
 * 3. **유효 항목이 없는 배치는 문서 node도 만들지 않는다.** 매달린 항목이 하나도 없는 node는
 *    그래프의 외톨이 점이다.
 * 4. **문서 삭제는 문서 node와 그 `PART_OF` edge까지 함께 소프트삭제한다**(ARTEL-728과의 접점).
 *    QA 런이 별도로 만든 다른 relation의 edge는 건드리지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeDocumentNodeIntegrationTest {

    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var edgeRepository: KnowledgeEdgeRepository
    @Autowired private lateinit var eventRepository: KnowledgeEventRepository
    @Autowired private lateinit var graphService: KnowledgeGraphService

    companion object {
        // 다른 knowledge 통합 테스트와 같은 이유로 static이다(JUnit이 메서드마다 인스턴스를 새로
        // 만들어 인스턴스 필드면 projectId/documentId가 겹친다). 다른 파일들의 대역과 겹치지 않게
        // 50000번대를 쓴다.
        private val projectSeq = AtomicLong(50_000)
        private val documentSeq = AtomicLong(60_000)
    }

    private fun item(tag: String, summary: String, description: String = "$summary 설명") =
        KnowledgeIngestItem(tag = tag, summary = summary, description = description)

    private suspend fun store(
        projectId: Long,
        documentId: Long,
        items: List<KnowledgeIngestItem>,
        fileName: String = "기획서.pdf",
        contentHash: String? = null
    ) = knowledgeService.store(
        projectId = projectId,
        scope = KnowledgeScope.PRODUCTION,
        source = KnowledgeSource.DOCS,
        sourceId = documentId,
        contentHash = contentHash,
        items = items,
        documentFileName = fileName
    )

    @Test
    fun `DOCS 배치는 문서 node 하나와 항목마다 PART_OF edge를 만든다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val documentId = documentSeq.incrementAndGet()

        store(
            projectId, documentId,
            listOf(item("CONTROL", "이동"), item("RULE", "체력")),
            fileName = "기획서.pdf",
            contentHash = "hash-1"
        )

        val rows = knowledgeRepository.findVisible(projectId, null, null, null).toList()
        assertThat(rows).hasSize(3)
        val documentNode = rows.single { it.summary == "기획서.pdf" }
        assertThat(documentNode.source).isEqualTo("DOCS")
        assertThat(documentNode.sourceId).isEqualTo(documentId)
        assertThat(documentNode.tag).isEqualTo("MISC")
        assertThat(documentNode.contentHash).isEqualTo("hash-1")
        val items = rows.filter { it.id != documentNode.id }
        assertThat(items).hasSize(2)

        // 항목마다 문서 node로 향하는 PART_OF edge가 하나씩 있다.
        val edges = edgeRepository.findBaselinePartOfEdgesTo(projectId, requireNotNull(documentNode.id)).toList()
        assertThat(edges).hasSize(2)
        assertThat(edges.map { it.fromKnowledgeId }).containsExactlyInAnyOrderElementsOf(items.map { it.id })
        edges.forEach { edge ->
            assertThat(edge.toKnowledgeId).isEqualTo(documentNode.id)
            assertThat(edge.scopeId).isNull()
            assertThat(edge.createdByQaTryId).isNull()
            assertThat(edge.note).isNotBlank()
        }

        // 문서 node도 여느 knowledge 행처럼 qa_try_id 없는 CREATE 이벤트를 남긴다.
        val documentNodeEvents = eventRepository.findByKnowledgeIdOrderByIdAsc(requireNotNull(documentNode.id))
            .toList()
        assertThat(documentNodeEvents).hasSize(1)
        assertThat(documentNodeEvents.single().event).isEqualTo("CREATE")
        assertThat(documentNodeEvents.single().qaTryId).isNull()
    }

    @Test
    fun `같은 문서를 두 번 적재해도 문서 node는 하나다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val documentId = documentSeq.incrementAndGet()

        store(projectId, documentId, listOf(item("CONTROL", "1차 항목")))
        val firstDocumentNodeId = requireNotNull(
            knowledgeRepository.findDocumentNode(projectId, documentId)?.id
        )

        store(projectId, documentId, listOf(item("RULE", "2차 항목 A"), item("UI", "2차 항목 B")))
        val secondDocumentNodeId = requireNotNull(
            knowledgeRepository.findDocumentNode(projectId, documentId)?.id
        )

        assertThat(secondDocumentNodeId).isEqualTo(firstDocumentNodeId)
        // 문서 node summary=파일 이름인 행이 정확히 하나여야 한다 — 둘이면 재적재가 새 node를 만든 것이다.
        val documentNodeRows = knowledgeRepository.findVisible(projectId, null, null, null).toList()
            .filter { it.id == firstDocumentNodeId }
        assertThat(documentNodeRows).hasSize(1)

        // 두 번째 배치의 항목들도 같은 문서 node를 향한 edge를 갖는다 — 첫 배치의 edge 1개 + 두
        // 번째 배치의 edge 2개 = 3개, 전부 같은 도착점이다.
        val edges = edgeRepository.findBaselinePartOfEdgesTo(projectId, firstDocumentNodeId).toList()
        assertThat(edges).hasSize(3)
        assertThat(edges).allMatch { it.toKnowledgeId == firstDocumentNodeId }
    }

    @Test
    fun `유효 항목이 없는 배치는 문서 node도 만들지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val documentId = documentSeq.incrementAndGet()

        store(projectId, documentId, emptyList())
        store(projectId, documentId, listOf(item("NOPE", "무효 tag")))

        assertThat(knowledgeRepository.findVisible(projectId, null, null, null).toList()).isEmpty()
        assertThat(knowledgeRepository.findDocumentNode(projectId, documentId)).isNull()
    }

    @Test
    fun `문서 삭제는 문서 node와 그 PART_OF edge를 함께 소프트삭제하되 다른 relation은 건드리지 않는다`():
        Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val documentId = documentSeq.incrementAndGet()

        store(projectId, documentId, listOf(item("CONTROL", "이동"), item("RULE", "체력")))
        val rows = knowledgeRepository.findVisible(projectId, null, null, null).toList()
        val documentNodeId = requireNotNull(rows.single { it.summary == "기획서.pdf" }.id)
        val itemIds = rows.filter { it.id != documentNodeId }.map { requireNotNull(it.id) }
        val partOfEdgeIds = edgeRepository.findBaselinePartOfEdgesTo(projectId, documentNodeId)
            .toList()
            .map { requireNotNull(it.id) }

        // 이 문서와 무관한 QA 런이 두 항목 사이에 REFINES를 걸어 둔다 — 문서 삭제가 이것까지
        // 지우면 안 된다.
        val unrelatedEdge = graphService.link(
            projectId, KnowledgeScope.PRODUCTION, qaTryId = 999L,
            request = KnowledgeLinkRequest(
                fromKnowledgeId = itemIds[0].toString(),
                toKnowledgeId = itemIds[1].toString(),
                relation = "REFINES",
                note = "QA 런이 관측한 관계"
            )
        )
        val unrelatedEdgeId = (unrelatedEdge as KnowledgeGraphMutation.Applied).edgeId

        knowledgeService.softDeleteForDocument(projectId, documentId)

        (itemIds + documentNodeId).forEach { id ->
            assertThat(knowledgeRepository.findById(id)!!.deletedAt)
                .describedAs("knowledge id=${id} 는 소프트삭제돼야 한다")
                .isNotNull()
        }
        partOfEdgeIds.forEach { id ->
            assertThat(edgeRepository.findById(id)!!.deletedAt)
                .describedAs("PART_OF edge id=${id} 는 소프트삭제돼야 한다")
                .isNotNull()
        }
        assertThat(edgeRepository.findById(unrelatedEdgeId)!!.deletedAt)
            .describedAs("문서와 무관한 QA 런의 REFINES edge는 그대로 남아야 한다")
            .isNull()
    }
}
