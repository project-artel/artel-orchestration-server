package kr.artel.orchestration.knowledge

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.knowledge.dto.KnowledgeIngestItem
import kr.artel.orchestration.knowledge.dto.KnowledgeMutationRequest
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeSource
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeMutation
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.util.concurrent.atomic.AtomicLong

/**
 * knowledge 버전 이력 검증(ARTEL-255).
 *
 * 이 이력이 성립하려면 세 가지가 사실이어야 하고, 셋 다 조용히 틀릴 수 있는 종류다.
 *
 * 1. **`version`은 content가 바뀔 때만 오른다.** 삭제가 버전을 올리면 복원이 "어느 버전으로
 *    되돌릴까"를 묻게 되고, V18이 정한 "복원은 `deleted_at = NULL`뿐"이 깨진다.
 * 2. **`knowledge.version = max(content event version)`.** 이 불변식이 깨진 상태는 아무도
 *    알려 주지 않으므로, 행 갱신과 이벤트 삽입이 한 트랜잭션인지를 관찰 가능한 형태로 못박는다.
 * 3. **`qa_try_id`는 QA 경로에서만 채워진다.** 지표의 귀속이 전부 이 컬럼이라, 문서 경로가 여기
 *    값을 남기면 사람이 만든 지식이 어느 런의 공으로 돌아간다.
 *
 * project/문서/런은 논리참조라 임의 id로 검증한다(FK 없음) — `KnowledgeIntegrationTest`와 같다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class KnowledgeEventIntegrationTest {

    @Autowired private lateinit var knowledgeService: KnowledgeService
    @Autowired private lateinit var knowledgeRepository: KnowledgeRepository
    @Autowired private lateinit var eventRepository: KnowledgeEventRepository
    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    companion object {
        // JUnit은 메서드마다 인스턴스를 새로 만들고 DB는 메서드 사이에 안 비워진다. static이라야
        // 실행 전체에서 projectId가 유일하다(KnowledgeIntegrationTest와 같은 이유).
        private val projectSeq = AtomicLong(31_000)
        private val qaTrySeq = AtomicLong(41_000)
    }

    // ------------------------------------------------------------------ 생성

    @Test
    fun `문서 배치 저장은 qa_try_id 없는 CREATE를 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()

        knowledgeService.store(
            projectId = projectId,
            scope = KnowledgeScope.PRODUCTION,
            source = KnowledgeSource.DOCS,
            sourceId = 77,
            contentHash = "hash",
            items = listOf(item("CONTROL", "이동", "WASD"), item("RULE", "체력", "최대 100")),
            documentFileName = "기획서.pdf"
        )

        // 항목 2개 + 문서 node 1개(ARTEL-748). 문서 node도 qa_try_id 없는 CREATE 하나를 남기므로
        // 아래 루프의 단언은 셋 모두에 그대로 성립한다.
        val ids = knowledgeRepository.findVisible(projectId, null, null, null).toList()
        assertThat(ids).hasSize(3)
        ids.forEach { row ->
            val events = eventsOf(row.id!!)
            assertThat(events).hasSize(1)
            assertThat(events.single().event).isEqualTo("CREATE")
            assertThat(events.single().version).isEqualTo(1)
            assertThat(events.single().projectId).isEqualTo(projectId)
            // sourceId(77)는 문서 id다. 런 id가 아니므로 여기 실리면 안 된다.
            assertThat(events.single().qaTryId)
                .describedAs("문서 경로가 런에 귀속되면 사람이 만든 지식이 런의 공이 된다")
                .isNull()
        }
    }

    @Test
    fun `QA 배치 저장은 그 런을 CREATE에 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val qaTryId = qaTrySeq.incrementAndGet()

        knowledgeService.store(
            projectId = projectId,
            scope = KnowledgeScope.PRODUCTION,
            source = KnowledgeSource.QA,
            sourceId = qaTryId,
            contentHash = null,
            items = listOf(item("UI", "체력바", "좌상단"))
        )

        val row = knowledgeRepository.findVisible(projectId, null, null, null).toList().single()
        assertThat(eventsOf(row.id!!).single().qaTryId).isEqualTo(qaTryId)
    }

    @Test
    fun `개별 생성은 버전 1과 content 스냅샷을 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val qaTryId = qaTrySeq.incrementAndGet()

        val id = applied(
            knowledgeService.createFromQaTry(
                projectId, KnowledgeScope.PRODUCTION, qaTryId,
                KnowledgeMutationRequest(tag = "RULE", summary = "낙하 데미지", description = "5m부터 1당 2")
            )
        )

        assertThat(knowledgeRepository.findById(id)!!.version).isEqualTo(1)
        val event = eventsOf(id).single()
        assertThat(event.event).isEqualTo("CREATE")
        assertThat(event.version).isEqualTo(1)
        assertThat(event.qaTryId).isEqualTo(qaTryId)
        assertThat(snapshot(event)).isEqualTo(
            mapOf("tag" to "RULE", "summary" to "낙하 데미지", "description" to "5m부터 1당 2")
        )
    }

    // ------------------------------------------------------------------ 수정

    @Test
    fun `본문 수정은 버전을 올리고 UPDATE를 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val creator = qaTrySeq.incrementAndGet()
        val editor = qaTrySeq.incrementAndGet()
        val id = givenEntry(projectId, creator)

        val result = knowledgeService.updateFromQaTry(
            projectId, KnowledgeScope.PRODUCTION, editor,
            KnowledgeMutationRequest(knowledgeId = id.toString(), summary = "새 요약")
        )

        assertThat(result).isInstanceOf(KnowledgeMutation.Applied::class.java)
        assertThat(knowledgeRepository.findById(id)!!.version).isEqualTo(2)
        val events = eventsOf(id)
        assertThat(events.map { it.event }).containsExactly("CREATE", "UPDATE")
        assertThat(events.last().version).isEqualTo(2)
        assertThat(events.last().qaTryId).isEqualTo(editor)
        assertThat(snapshot(events.last())["summary"]).isEqualTo("새 요약")
        // 스냅샷은 행 전체다 — 안 준 필드도 그 시점의 값으로 들어 있어야 단건 조회가 성립한다.
        assertThat(snapshot(events.last())["description"]).isEqualTo("설명")
    }

    /**
     * tag만 바뀌어도 버전은 오른다. `after`가 tag까지 스냅샷하므로, 여기서 안 올리면 버전 N의
     * 스냅샷이 행과 어긋나 `knowledge.version = max(event.version)` 불변식이 깨진다.
     * (임베딩 무효화 판정은 이와 별개로 summary/description만 본다.)
     */
    @Test
    fun `tag만 바뀌어도 버전은 오른다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = givenEntry(projectId, qaTrySeq.incrementAndGet())

        knowledgeService.updateFromQaTry(
            projectId, KnowledgeScope.PRODUCTION, qaTrySeq.incrementAndGet(),
            KnowledgeMutationRequest(knowledgeId = id.toString(), tag = "UI")
        )

        assertThat(knowledgeRepository.findById(id)!!.version).isEqualTo(2)
        assertThat(snapshot(eventsOf(id).last())["tag"]).isEqualTo("UI")
    }

    /**
     * 값이 실제로 안 바뀐 요청은 이력을 만들지 않는다. 만들면 이력이 "무엇이 바뀌었나"가 아니라
     * "몇 번 호출됐나"의 기록이 되고, 같은 `after`를 가진 버전이 둘 생긴다.
     */
    @Test
    fun `같은 값으로 덮어쓰면 버전도 이벤트도 늘지 않는다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val editor = qaTrySeq.incrementAndGet()
        val id = givenEntry(projectId, qaTrySeq.incrementAndGet())

        knowledgeService.updateFromQaTry(
            projectId, KnowledgeScope.PRODUCTION, editor,
            KnowledgeMutationRequest(knowledgeId = id.toString(), summary = "요약", description = "설명")
        )

        val row = knowledgeRepository.findById(id)!!
        assertThat(row.version).isEqualTo(1)
        assertThat(eventsOf(id)).hasSize(1)
        // 누가 손댔는지는 여전히 사실이라 그대로 남는다.
        assertThat(row.updatedByQaTryId).isEqualTo(editor)
    }

    // ------------------------------------------------------------------ 삭제

    @Test
    fun `소프트삭제는 버전을 올리지 않고 after 없는 DELETE를 남긴다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val deleter = qaTrySeq.incrementAndGet()
        val id = givenEntry(projectId, qaTrySeq.incrementAndGet())

        knowledgeService.softDeleteFromQaTry(
            projectId, KnowledgeScope.PRODUCTION, deleter, KnowledgeMutationRequest(knowledgeId = id.toString())
        )

        assertThat(knowledgeRepository.findById(id)!!.version)
            .describedAs("삭제가 버전을 올리면 복원이 어느 버전으로 되돌릴지를 묻게 된다")
            .isEqualTo(1)
        val last = eventsOf(id).last()
        assertThat(last.event).isEqualTo("DELETE")
        assertThat(last.version).isEqualTo(1)
        assertThat(last.qaTryId).isEqualTo(deleter)
        assertThat(last.after)
            .describedAs("after가 있으면 부분 유니크 인덱스가 CREATE와 충돌한다")
            .isNull()
    }

    // ------------------------------------------------ 중복 쓰기와 트랜잭션 경계

    /**
     * 같은 `(knowledge_id, version)` content 이벤트가 두 번 들어가면 부분 유니크 인덱스가 막고,
     * **그 실패가 행 갱신까지 되돌린다**. 이것이 "같은 트랜잭션"의 관찰 가능한 정의다 — 쪼개져
     * 있었다면 행은 버전 2인데 이력에는 버전 2가 없는 상태로 굳는다.
     *
     * 재시도나 동시 쓰기가 만드는 상황을 여기서는 버전 2 이벤트를 미리 심어 재현한다.
     */
    @Test
    fun `버전 중복은 인덱스가 막고 행 갱신도 함께 되돌아간다`(): Unit = runBlocking {
        val projectId = projectSeq.incrementAndGet()
        val id = givenEntry(projectId, qaTrySeq.incrementAndGet())
        insertRawContentEvent(knowledgeId = id, projectId = projectId, version = 2)

        val failure = runCatching {
            knowledgeService.updateFromQaTry(
                projectId, KnowledgeScope.PRODUCTION, qaTrySeq.incrementAndGet(),
                KnowledgeMutationRequest(knowledgeId = id.toString(), summary = "충돌하는 수정")
            )
        }

        assertThat(failure.isFailure).describedAs("유니크 위반은 값이 아니라 예외로 나온다").isTrue()
        val row = knowledgeRepository.findById(id)!!
        assertThat(row.version).describedAs("이벤트가 실패했는데 행만 올랐다면 트랜잭션이 아니다").isEqualTo(1)
        assertThat(row.summary).isEqualTo("요약")
    }

    // --------------------------------------------------------------- helpers

    private fun item(tag: String, summary: String, description: String) =
        KnowledgeIngestItem(tag = tag, summary = summary, description = description)

    private suspend fun eventsOf(knowledgeId: Long): List<KnowledgeEventEntity> =
        eventRepository.findByKnowledgeIdOrderByIdAsc(knowledgeId).toList()

    private fun snapshot(event: KnowledgeEventEntity): Map<String, String> {
        val raw = requireNotNull(event.after) { "content 이벤트에는 after가 있어야 한다" }
        @Suppress("UNCHECKED_CAST")
        return objectMapper.readValue(raw.asString(), Map::class.java) as Map<String, String>
    }

    private fun applied(mutation: KnowledgeMutation): Long =
        (mutation as KnowledgeMutation.Applied).knowledgeId

    private suspend fun givenEntry(projectId: Long, qaTryId: Long): Long =
        applied(
            knowledgeService.createFromQaTry(
                projectId, KnowledgeScope.PRODUCTION, qaTryId,
                KnowledgeMutationRequest(tag = "RULE", summary = "요약", description = "설명")
            )
        )

    /** 서비스를 거치지 않고 이벤트를 심는다 — 서비스는 같은 버전을 두 번 쓰지 않기 때문이다. */
    private suspend fun insertRawContentEvent(knowledgeId: Long, projectId: Long, version: Int) {
        databaseClient.sql(
            """
            INSERT INTO knowledge_event (knowledge_id, project_id, event, version, after)
            VALUES (:knowledgeId, :projectId, 'UPDATE', :version, CAST('{}' AS jsonb))
            """.trimIndent()
        )
            .bind("knowledgeId", knowledgeId)
            .bind("projectId", projectId)
            .bind("version", version)
            .fetch()
            .rowsUpdated()
            .awaitFirstOrNull()
    }
}
