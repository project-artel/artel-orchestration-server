package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.qa.dto.QaRunChangeListResponse
import kr.artel.orchestration.qa.dto.QaRunChangeResponse
import kr.artel.orchestration.qa.entity.QaRunEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/** revert 할 수 없는 이유. 응답의 `notRevertibleReason` 과 409 응답의 `code` 로 나간다. */
enum class NotRevertibleReason(val code: String, val message: String) {
    /** run 의 마지막 event 뒤에 다른 event 가 있다. 나중 변경을 덮어쓰지 않는다. */
    SUPERSEDED("superseded", "이 run 뒤에 다른 변경이 있어 되돌릴 수 없습니다."),

    /** 이미 revert 했다. */
    ALREADY_REVERTED("already_reverted", "이미 되돌린 변경입니다."),

    /**
     * run 이전 내용이 이력에 없다. V27 이전에 만들어진 knowledge 를 V100 전에 고친 경우다 — 버전 1 의
     * 내용은 snapshot 된 적이 없다.
     */
    BEFORE_UNKNOWN("before_unknown", "이 run 이전의 내용이 기록돼 있지 않아 되돌릴 수 없습니다."),
}

class QaRunChangeNotRevertibleException(val reason: NotRevertibleReason) :
    ConflictException(reason.message, code = reason.code)

/**
 * QA run 하나가 knowledge 와 macro 에 한 변경을 보여 주고 revert 한다(V100).
 *
 * ## 합치는 규칙
 *
 * run 의 모든 `qa_try` 가 남긴 event 를 대상별로 모아 한 줄로 합친다.
 * - before 는 run 의 첫 event 직전 상태다. 첫 event 가 CREATE · RESTORE 면 없던 대상이고, UPDATE 면
 *   직전 버전의 내용, DELETE 면 그 event 버전의 내용이다.
 * - after 는 run 의 마지막 event 직후 상태다. DELETE 면 없고, 그 밖에는 그 event 버전의 내용이다.
 * - 둘이 같으면 뺀다. 만들고 같은 run 에서 지운 대상도 둘 다 없으므로 빠진다.
 *
 * ## revert 가 허용되는 때
 *
 * run 의 마지막 event 가 그 대상의 최신 event 일 때뿐이다. 뒤에 다른 event 가 있으면 다른 run 이나
 * 사람이 그 뒤에 바꾼 것이고, 그것을 덮어쓰지 않는다. revert 는 이력을 고치지 않고 run 의 마지막
 * event 를 가리키는 event 를 덧붙인다 — 그래서 revert 한 뒤에는 그 revert event 가 최신이 된다.
 *
 * revert 는 한 트랜잭션이다. 대상 행을 `FOR UPDATE` 로 잠근 뒤 최신 event 를 다시 확인하므로, 목록을
 * 읽은 뒤 끼어든 쓰기도 덮지 않는다.
 */
@Service
class QaRunChangeService(
    private val qaRuns: QaRunRepository,
    private val knowledgeLedger: KnowledgeRunChangeLedger,
    private val macroLedger: MacroRunChangeLedger,
    private val transactionalOperator: TransactionalOperator,
    private val gameInstances: GameInstanceRepository,
    private val projectChanges: ProjectChangeStreamManager,
) {
    private val logger = LoggerFactory.getLogger(QaRunChangeService::class.java)

    suspend fun list(qaRunId: Long, appUserId: Long): QaRunChangeListResponse {
        requireAccessibleRun(qaRunId, appUserId)
        val items = collapse(knowledgeLedger, qaRunId) + collapse(macroLedger, qaRunId)
        return QaRunChangeListResponse(items.sortedWith(CHANGE_ORDER))
    }

    /**
     * 한 대상의 변경을 revert 하고 그 뒤의 항목을 돌려준다.
     *
     * run 이 그 대상을 바꾸지 않았거나 바꾼 결과가 run 이전과 같으면 404 다. 목록에 없는 항목이다.
     */
    suspend fun revert(qaRunId: Long, kind: QaRunChangeKind, targetId: Long, appUserId: Long): QaRunChangeResponse {
        val run = requireAccessibleRun(qaRunId, appUserId)
        val response = when (kind) {
            QaRunChangeKind.KNOWLEDGE -> revert(knowledgeLedger, qaRunId, targetId, appUserId)
            QaRunChangeKind.MACRO -> revert(macroLedger, qaRunId, targetId, appUserId)
        }
        // revert transaction 이 commit 된 뒤다. 실험 scope 의 knowledge 를 되돌린 경우에도 알리지만, 다시 읽을 뿐이라 해가 없다.
        gameInstances.findById(run.gameInstanceId)?.projectId?.let { projectId ->
            val topic = when (kind) {
                QaRunChangeKind.KNOWLEDGE -> ProjectChangeTopic.KNOWLEDGE
                QaRunChangeKind.MACRO -> ProjectChangeTopic.MACROS
            }
            projectChanges.notify(projectId, topic)
        }
        return response
    }

    private suspend fun <C : Any, R : Any> revert(
        ledger: RunChangeLedger<C, R>,
        qaRunId: Long,
        targetId: Long,
        appUserId: Long,
    ): QaRunChangeResponse {
        val change = collapsed(ledger, qaRunId, targetId) ?: throw NotFoundException("이 run 이 바꾼 대상이 아닙니다.")
        change.notRevertibleReason?.let { throw QaRunChangeNotRevertibleException(it) }
        val before = (change.before as? ChangeSide.Present<C>)?.content
        transactionalOperator.executeAndAwait {
            ledger.lockAndRevert(targetId, change.last.id, change.first, before, appUserId)
        }
        logger.info(
            "QA run 변경 revert: qaRun={}, kind={}, target={}, revertsEvent={}, appUser={}",
            qaRunId, ledger.kind, targetId, change.last.id, appUserId
        )
        // 방금 쓴 revert event 까지 반영해 다시 합친다. 응답의 `decision` 이 REVERTED 여야 한다.
        return collapsed(ledger, qaRunId, targetId)?.toResponse(ledger)
            ?: error("revert 뒤에 항목이 사라졌다: kind=${ledger.kind}, target=$targetId")
    }

    private suspend fun requireAccessibleRun(qaRunId: Long, appUserId: Long): QaRunEntity =
        qaRuns.findAccessibleById(qaRunId, appUserId) ?: throw NotFoundException()

    private suspend fun <C : Any, R : Any> collapse(
        ledger: RunChangeLedger<C, R>,
        qaRunId: Long,
    ): List<QaRunChangeResponse> =
        collapseEvents(ledger, ledger.eventsOfRun(qaRunId)).map { it.toResponse(ledger) }

    private suspend fun <C : Any, R : Any> collapsed(
        ledger: RunChangeLedger<C, R>,
        qaRunId: Long,
        targetId: Long,
    ): CollapsedChange<C>? =
        collapseEvents(ledger, ledger.eventsOfRun(qaRunId).filter { it.targetId == targetId }).singleOrNull()

    private suspend fun <C : Any, R : Any> collapseEvents(
        ledger: RunChangeLedger<C, R>,
        runEvents: List<RunChangeEvent>,
    ): List<CollapsedChange<C>> {
        if (runEvents.isEmpty()) return emptyList()
        val eventsByTarget = runEvents.groupBy { it.targetId }
        val targetIds = eventsByTarget.keys
        val latest = ledger.latestEvents(targetIds)
        val rows = ledger.rows(targetIds)
        val lastEvents = eventsByTarget.values.map { it.last() }
        val reverted = ledger.revertedEventIds(lastEvents.map { it.id })

        return eventsByTarget.mapNotNull { (targetId, events) ->
            val first = events.first()
            val last = events.last()
            val row = rows[targetId]
            val before = beforeOf(ledger, first, row)
            val after = afterOf(ledger, last, row)
            if (after is ChangeSide.Unknown) {
                // event 가 content 를 싣지 않고 행도 그 버전이 아니다. 쓰기 경로의 버그라 보여 줄 것이 없다.
                logger.warn("QA run 변경의 after 를 읽을 수 없음: kind={}, target={}, event={}", ledger.kind, targetId, last.id)
                return@mapNotNull null
            }
            if (isUnchanged(ledger, before, after)) return@mapNotNull null
            val reason = when {
                last.id in reverted -> NotRevertibleReason.ALREADY_REVERTED
                latest[targetId]?.id != last.id -> NotRevertibleReason.SUPERSEDED
                // event 로는 아직 최신인데 행이 없다. `content_map` CASCADE 처럼 event 를 남기지 않는
                // 삭제가 run 뒤에 있었다는 뜻이다.
                row == null && after is ChangeSide.Present -> NotRevertibleReason.SUPERSEDED
                before is ChangeSide.Unknown -> NotRevertibleReason.BEFORE_UNKNOWN
                else -> null
            }
            CollapsedChange(
                targetId = targetId,
                first = first,
                last = last,
                before = before,
                after = after,
                reverted = last.id in reverted,
                notRevertibleReason = reason,
            )
        }
    }

    private suspend fun <C : Any, R : Any> beforeOf(ledger: RunChangeLedger<C, R>, first: RunChangeEvent, row: R?): ChangeSide<C> {
        val version = when (first.type) {
            CREATE, RESTORE -> return ChangeSide.Absent
            UPDATE -> first.version - 1
            else -> first.version
        }
        return ledger.contentAt(first.targetId, version, row)?.let { ChangeSide.Present(it) } ?: ChangeSide.Unknown
    }

    private suspend fun <C : Any, R : Any> afterOf(ledger: RunChangeLedger<C, R>, last: RunChangeEvent, row: R?): ChangeSide<C> {
        if (last.type == DELETE) return ChangeSide.Absent
        return ledger.contentAt(last.targetId, last.version, row)?.let { ChangeSide.Present(it) } ?: ChangeSide.Unknown
    }

    private fun <C : Any> isUnchanged(ledger: RunChangeLedger<C, *>, before: ChangeSide<C>, after: ChangeSide<C>) =
        when {
            before is ChangeSide.Absent && after is ChangeSide.Absent -> true
            before is ChangeSide.Present && after is ChangeSide.Present -> ledger.sameContent(before.content, after.content)
            else -> false
        }

    private fun <C : Any> CollapsedChange<C>.toResponse(ledger: RunChangeLedger<C, *>): QaRunChangeResponse {
        val beforeContent = (before as? ChangeSide.Present<C>)?.content
        val afterContent = (after as? ChangeSide.Present<C>)?.content
        val change = when {
            before is ChangeSide.Absent -> CREATE
            after is ChangeSide.Absent -> DELETE
            else -> UPDATE
        }
        return QaRunChangeResponse(
            kind = ledger.kind.name,
            targetId = targetId.toString(),
            name = ledger.name(requireNotNull(afterContent ?: beforeContent) { "합친 변경에 content 가 없다" }),
            change = change,
            before = beforeContent?.let(ledger::render),
            after = afterContent?.let(ledger::render),
            // run 의 event 만 모았으므로 `qa_try_id` 가 비어 있을 수 없다.
            qaTryId = requireNotNull(last.qaTryId).toString(),
            changedAt = last.createdAt,
            decision = if (reverted) REVERTED else APPLIED,
            revertible = notRevertibleReason == null,
            notRevertibleReason = notRevertibleReason?.name,
        )
    }

    /** 대상 하나의 합친 결과. */
    private data class CollapsedChange<C : Any>(
        val targetId: Long,
        val first: RunChangeEvent,
        val last: RunChangeEvent,
        val before: ChangeSide<C>,
        val after: ChangeSide<C>,
        val reverted: Boolean,
        val notRevertibleReason: NotRevertibleReason?,
    )

    /**
     * before 나 after 한쪽의 상태. [Unknown] 은 대상이 있었지만 그 내용이 이력에 없다는 뜻이고,
     * 없던 대상([Absent])과 구분해야 UPDATE 를 CREATE 로 잘못 보여 주지 않는다.
     */
    private sealed interface ChangeSide<out C> {
        data object Absent : ChangeSide<Nothing>
        data object Unknown : ChangeSide<Nothing>
        data class Present<C>(val content: C) : ChangeSide<C>
    }

    private companion object {
        const val CREATE = "CREATE"
        const val UPDATE = "UPDATE"
        const val DELETE = "DELETE"
        const val RESTORE = "RESTORE"
        const val APPLIED = "APPLIED"
        const val REVERTED = "REVERTED"

        /** 계약이 정한 순서: `changedAt` 오름차순, 다음 `kind`, 다음 `targetId`. */
        val CHANGE_ORDER = compareBy<QaRunChangeResponse>({ it.changedAt }, { it.kind }, { it.targetId.toLong() })
    }
}
