package kr.artel.orchestration.qa.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.MacroEntity
import kr.artel.orchestration.contentmap.entity.MacroEventEntity
import kr.artel.orchestration.contentmap.macro.MacroDefinitionContent
import kr.artel.orchestration.contentmap.macro.MacroDefinitionService
import kr.artel.orchestration.contentmap.repository.MacroEventRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.knowledge.entity.KnowledgeEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventEntity
import kr.artel.orchestration.knowledge.entity.KnowledgeEventType
import kr.artel.orchestration.knowledge.repository.KnowledgeEventRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.knowledge.service.KnowledgeContent
import kr.artel.orchestration.knowledge.service.KnowledgeService
import org.springframework.stereotype.Component
import java.time.Instant

/** QA run 이 바꾼 대상의 종류. [pathSegment] 는 revert 경로의 `{kind}` 자리에 오는 값이다. */
enum class QaRunChangeKind(val pathSegment: String) {
    KNOWLEDGE("knowledge"),
    MACRO("macro");

    companion object {
        fun fromPath(segment: String): QaRunChangeKind? = entries.firstOrNull { it.pathSegment == segment }
    }
}

/**
 * `knowledge_event` 와 `macro_event` 의 한 행을 같은 모양으로 옮긴 것. 합치는 규칙은 두 표에 똑같이
 * 적용되므로 [QaRunChangeService] 는 이것만 본다.
 */
data class RunChangeEvent(
    val id: Long,
    val targetId: Long,
    val qaTryId: Long?,
    val type: String,
    val version: Int,
    val createdAt: Instant,
    val revertsEventId: Long?,
)

/**
 * 이력 표 하나를 [QaRunChangeService] 에 맞춰 읽고 쓰는 쪽.
 *
 * @param C 대상의 content. before · after 비교와 diff 텍스트가 여기서 나온다.
 * @param R 대상의 현재 행.
 */
interface RunChangeLedger<C : Any, R : Any> {
    val kind: QaRunChangeKind

    /** 그 run 의 모든 `qa_try` 가 남긴 event. id 오름차순. */
    suspend fun eventsOfRun(qaRunId: Long): List<RunChangeEvent>

    /** 대상마다 가장 나중의 event. 비어 있지 않은 [targetIds] 만 받는다. */
    suspend fun latestEvents(targetIds: Collection<Long>): Map<Long, RunChangeEvent>

    /** [eventIds] 가운데 revert event 가 가리키는 것. 비어 있지 않은 [eventIds] 만 받는다. */
    suspend fun revertedEventIds(eventIds: Collection<Long>): Set<Long>

    /** 아직 남아 있는 대상 행. 비어 있지 않은 [targetIds] 만 받는다. */
    suspend fun rows(targetIds: Collection<Long>): Map<Long, R>

    /** 그 버전의 content. 알 수 없으면 null. */
    suspend fun contentAt(targetId: Long, version: Int, row: R?): C?

    fun sameContent(left: C, right: C): Boolean

    /** line diff 에 넣을 텍스트. */
    fun render(content: C): String

    fun name(content: C): String

    /**
     * 대상 행을 잠그고, 그 run 의 마지막 event([expectedLatestEventId])가 아직 최신인지 확인한 뒤
     * [before] 상태로 되돌린다. **호출자의 트랜잭션 안에서 돈다.** 최신이 아니면
     * [QaRunChangeNotRevertibleException] 을 던져 트랜잭션을 되돌린다.
     *
     * @param before run 이전 상태. null 이면 run 이전에 없던 대상이다.
     * @param first 그 run 의 첫 event. 그림자 행을 run 이 만들었는지 판단할 때 쓴다.
     */
    suspend fun lockAndRevert(
        targetId: Long,
        expectedLatestEventId: Long,
        first: RunChangeEvent,
        before: C?,
        appUserId: Long,
    )
}

/**
 * 잠근 뒤 읽은 최신 event 가 그 run 의 마지막 event 가 아니면 던진다. 두 표가 같은 판단을 쓴다.
 * 최신 event 가 그 event 를 가리키는 revert 면 이미 되돌린 것이고, 그 밖에는 다른 쓰기가 덮은 것이다.
 */
internal fun ensureStillLatest(latest: RunChangeEvent?, expectedLatestEventId: Long) {
    if (latest == null || latest.id == expectedLatestEventId) return
    val reason = if (latest.revertsEventId == expectedLatestEventId) {
        NotRevertibleReason.ALREADY_REVERTED
    } else {
        NotRevertibleReason.SUPERSEDED
    }
    throw QaRunChangeNotRevertibleException(reason)
}

@Component
class KnowledgeRunChangeLedger(
    private val events: KnowledgeEventRepository,
    private val knowledgeRepository: KnowledgeRepository,
    private val knowledgeService: KnowledgeService,
) : RunChangeLedger<KnowledgeContent, KnowledgeEntity> {

    override val kind = QaRunChangeKind.KNOWLEDGE

    override suspend fun eventsOfRun(qaRunId: Long) =
        events.findByQaRunId(qaRunId).map { it.toRunChangeEvent() }.toList()

    override suspend fun latestEvents(targetIds: Collection<Long>) =
        events.findLatestByKnowledgeIds(targetIds).toList().associate { it.knowledgeId to it.toRunChangeEvent() }

    override suspend fun revertedEventIds(eventIds: Collection<Long>) =
        events.findRevertsOf(eventIds).toList().mapNotNull { it.revertsEventId }.toSet()

    override suspend fun rows(targetIds: Collection<Long>) =
        knowledgeRepository.findAllById(targetIds).toList().associateBy { requireNotNull(it.id) }

    override suspend fun contentAt(targetId: Long, version: Int, row: KnowledgeEntity?) =
        knowledgeService.contentAt(targetId, version, row)

    override fun sameContent(left: KnowledgeContent, right: KnowledgeContent) = left == right

    /** 계약이 정한 모양 그대로다: `tag: <tag>\nsummary: <summary>\n\n<description>`. */
    override fun render(content: KnowledgeContent) =
        "tag: ${content.tag}\nsummary: ${content.summary}\n\n${content.description}"

    override fun name(content: KnowledgeContent) = content.summary.ifBlank { content.tag }

    override suspend fun lockAndRevert(
        targetId: Long,
        expectedLatestEventId: Long,
        first: RunChangeEvent,
        before: KnowledgeContent?,
        appUserId: Long,
    ) {
        val current = knowledgeRepository.findByIdForUpdate(targetId)
            ?: throw QaRunChangeNotRevertibleException(NotRevertibleReason.SUPERSEDED)
        ensureStillLatest(events.findLatestByKnowledgeId(targetId)?.toRunChangeEvent(), expectedLatestEventId)
        if (before == null) {
            // 그림자를 이 run 이 만들었을 때만 `shadows_id` 를 비운다. run 이전부터 있던 그림자를 run 이
            // 되살렸다면 그 그림자는 run 이전에도 baseline 을 가리고 있었다.
            val releaseShadow = current.shadowsId != null && first.type == KnowledgeEventType.CREATE.name
            knowledgeService.revertToAbsent(current, expectedLatestEventId, appUserId, releaseShadow)
        } else {
            knowledgeService.revertToContent(current, before, expectedLatestEventId, appUserId)
        }
    }

    private fun KnowledgeEventEntity.toRunChangeEvent() = RunChangeEvent(
        id = requireNotNull(id),
        targetId = knowledgeId,
        qaTryId = qaTryId,
        type = event,
        version = version,
        createdAt = createdAt ?: Instant.EPOCH,
        revertsEventId = revertsEventId,
    )
}

/** macro 의 content. 이름은 바뀌지 않지만 DELETE 만 남은 macro 도 이름이 나와야 해서 함께 든다. */
data class MacroSnapshot(
    val name: String,
    val definition: MacroDefinitionContent,
)

@Component
class MacroRunChangeLedger(
    private val events: MacroEventRepository,
    private val macros: MacroRepository,
    private val macroDefinitionService: MacroDefinitionService,
) : RunChangeLedger<MacroSnapshot, MacroEntity> {

    override val kind = QaRunChangeKind.MACRO

    override suspend fun eventsOfRun(qaRunId: Long) =
        events.findByQaRunId(qaRunId).map { it.toRunChangeEvent() }.toList()

    override suspend fun latestEvents(targetIds: Collection<Long>) =
        events.findLatestByMacroIds(targetIds).toList().associate { it.macroId to it.toRunChangeEvent() }

    override suspend fun revertedEventIds(eventIds: Collection<Long>) =
        events.findRevertsOf(eventIds).toList().mapNotNull { it.revertsEventId }.toSet()

    override suspend fun rows(targetIds: Collection<Long>) =
        macros.findAllById(targetIds).toList().associateBy { requireNotNull(it.id) }

    /** V100 이 기존 macro 모두에 버전 1 event 를 만들었으므로 행으로 대신 읽을 일이 없다. */
    override suspend fun contentAt(targetId: Long, version: Int, row: MacroEntity?): MacroSnapshot? {
        val event = events.findContentAt(targetId, version) ?: return null
        val source = event.source ?: return null
        val definitionJson = event.definitionJson ?: return null
        val parameterNames = event.parameterNames ?: return null
        return MacroSnapshot(event.name, MacroDefinitionContent(source, definitionJson, parameterNames))
    }

    /** jsonb 가 내보내는 텍스트는 key 순서와 공백이 정해져 있어 텍스트 비교가 곧 값 비교다. */
    override fun sameContent(left: MacroSnapshot, right: MacroSnapshot) =
        left.definition.source == right.definition.source &&
            left.definition.definitionJson.asString() == right.definition.definitionJson.asString() &&
            left.definition.parameterNames.asString() == right.definition.parameterNames.asString()

    /** 계약이 정한 대로 `source` 를 그대로 낸다. 사람이 읽고 diff 하는 몫이 그 칸이다(V98). */
    override fun render(content: MacroSnapshot) = content.definition.source

    override fun name(content: MacroSnapshot) = content.name

    override suspend fun lockAndRevert(
        targetId: Long,
        expectedLatestEventId: Long,
        first: RunChangeEvent,
        before: MacroSnapshot?,
        appUserId: Long,
    ) {
        // 행이 없으면 run 뒤에 누가 지운 것이다. `content_map` 이 지워지면 macro 가 CASCADE 로 사라지고
        // 그 길은 event 를 남기지 않는다.
        val current = macros.findByIdForUpdate(targetId)
            ?: throw QaRunChangeNotRevertibleException(NotRevertibleReason.SUPERSEDED)
        ensureStillLatest(events.findLatestByMacroId(targetId)?.toRunChangeEvent(), expectedLatestEventId)
        if (before == null) {
            macroDefinitionService.revertToAbsent(current, expectedLatestEventId, appUserId)
        } else {
            macroDefinitionService.revertToDefinition(current, before.definition, expectedLatestEventId, appUserId)
        }
    }

    private fun MacroEventEntity.toRunChangeEvent() = RunChangeEvent(
        id = requireNotNull(id),
        targetId = macroId,
        qaTryId = qaTryId,
        type = event,
        version = version,
        createdAt = createdAt ?: Instant.EPOCH,
        revertsEventId = revertsEventId,
    )
}
