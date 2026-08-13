package kr.artel.orchestration.knowledge.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.knowledge.dto.KnowledgeRunConfigStatsCell
import kr.artel.orchestration.knowledge.dto.KnowledgeStatsResponse
import kr.artel.orchestration.knowledge.dto.KnowledgeStatsTotals
import kr.artel.orchestration.knowledge.repository.KnowledgeStatsRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeStatsRow
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/**
 * 기간을 안 주면 보는 창.
 *
 * QA 집계(30일)보다 넓다. 지식은 만들어진 뒤 후속 런이 그것을 지우거나 인용해야 신호가 생기고,
 * 그 후속 런은 같은 날 돌지 않는다. 30일로 자르면 창의 끝자락에 만들어진 지식이 평가받을 시간을
 * 갖지 못한 채 "아직 아무도 안 지웠다"로 집계된다.
 */
private val DEFAULT_WINDOW: Duration = Duration.ofDays(90)

/** 조합 상한. [kr.artel.orchestration.qa.service.QaStatsService]와 같은 폭주 방지선이다. */
private const val MAX_CELLS = 500

@Service
class KnowledgeStatsService(
    private val statsRepository: KnowledgeStatsRepository,
    private val clock: Clock
) {

    /**
     * 프로젝트의 지식 버전을 그것을 만든 런의 실행 설정 축으로 접는다.
     *
     * 참여자가 아니면 예외가 아니라 빈 집계다 — 멤버십 판정을 질의 안에서 하는 QA 집계와 같은
     * 동작이고, 여기만 403을 주면 프로젝트의 존재 여부가 새어 나간다.
     */
    suspend fun stats(
        projectId: Long,
        userId: Long,
        from: Instant?,
        to: Instant?,
        cellLimit: Int
    ): KnowledgeStatsResponse {
        if (cellLimit !in 1..MAX_CELLS) {
            throw BadRequestException("cellLimit must be between 1 and $MAX_CELLS")
        }
        val end = to ?: Instant.now(clock)
        val start = from ?: end.minus(DEFAULT_WINDOW)
        if (!start.isBefore(end)) {
            throw BadRequestException("from must be earlier than to")
        }

        val aggregate = statsRepository.aggregateByRunConfig(
            projectId = projectId,
            userId = userId,
            from = start,
            to = end,
            limit = cellLimit
        )
        return KnowledgeStatsResponse(
            projectId = projectId.toString(),
            from = start,
            to = end,
            total = aggregate.total.toTotals(),
            cells = aggregate.cells.map { it.toCell() },
            truncated = aggregate.truncated,
            cellLimit = cellLimit
        )
    }
}

/** 합계 행이 없는 경우는 빈 스코프뿐이라 0으로 채운다. 화면이 null 총계를 다루지 않아도 되게 한다. */
private fun KnowledgeStatsRow?.toTotals(): KnowledgeStatsTotals =
    KnowledgeStatsTotals(
        entryVersions = this?.entryVersions ?: 0,
        currentVersions = this?.currentVersions ?: 0,
        deletedVersions = this?.deletedVersions ?: 0,
        repudiatedVersions = this?.repudiatedVersions ?: 0,
        retrievalTotal = this?.retrievalTotal ?: 0,
        citationTotal = this?.citationTotal ?: 0,
        citationKnownTotal = this?.citationKnownTotal ?: 0
    )

private fun KnowledgeStatsRow.toCell() = KnowledgeRunConfigStatsCell(
    model = model,
    reasoningEffort = reasoningEffort,
    promptVersion = promptVersion,
    agentArch = agentArch,
    entryVersions = entryVersions,
    currentVersions = currentVersions,
    deletedVersions = deletedVersions,
    repudiatedVersions = repudiatedVersions,
    retrievalTotal = retrievalTotal,
    citationTotal = citationTotal,
    citationKnownTotal = citationKnownTotal
)
