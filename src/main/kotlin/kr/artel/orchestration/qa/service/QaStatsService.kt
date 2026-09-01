package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.QaCitationStats
import kr.artel.orchestration.qa.dto.QaIssueStatsCell
import kr.artel.orchestration.qa.dto.QaRunConfigStatsCell
import kr.artel.orchestration.qa.dto.QaStatsResponse
import kr.artel.orchestration.qa.dto.QaStatsTotals
import kr.artel.orchestration.qa.dto.QaToolStatsCell
import kr.artel.orchestration.qa.dto.QaToolStatsResponse
import kr.artel.orchestration.qa.repository.QaStatsRepository
import kr.artel.orchestration.qa.repository.QaStatsRow
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Duration
import java.time.Instant

/** 기간을 안 주면 보는 창. 실험 한 사이클이 대체로 이 안에 들어간다. */
private val DEFAULT_WINDOW: Duration = Duration.ofDays(30)

/** 조합 상한. 실험 공간은 이보다 훨씬 작고, 이 값은 잘못된 축이 들어왔을 때의 폭주 방지선이다. */
private const val MAX_CELLS = 500

@Service
class QaStatsService(
    private val statsRepository: QaStatsRepository,
    private val clock: Clock
) {

    /**
     * 프로젝트의 QA 런을 실행 설정 축으로 접는다.
     *
     * 참여자가 아니면 예외가 아니라 빈 집계다 — 멤버십 판정을 쿼리 안에서 하는
     * `QaTryRepository.findByProject`와 같은 동작이고, 여기만 403을 주면 프로젝트의 존재
     * 여부가 새어 나간다.
     */
    suspend fun stats(
        projectId: Long,
        userId: Long,
        from: Instant?,
        to: Instant?,
        cellLimit: Int
    ): QaStatsResponse {
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
        return QaStatsResponse(
            projectId = projectId.toString(),
            from = start,
            to = end,
            total = aggregate.total.toTotals(),
            cells = aggregate.cells.map { it.toCell() },
            truncated = aggregate.truncated,
            cellLimit = cellLimit
        )
    }

    /**
     * 에이전트가 무엇을 했나 (ARTEL-681).
     *
     * [stats] 와 창을 맞춘다 — 기본 30 일, 같은 기준(`qa_try.started_at`). 두 응답을 한 화면에
     * 나란히 놓는 것이 이 값의 쓸모라, 창이 어긋나면 나란히 놓을 수가 없다.
     *
     * 참여자가 아니면 빈 집계다. [stats] 와 같은 이유로 403 을 주지 않는다 — 여기만 막으면
     * 프로젝트의 존재 여부가 새어 나간다.
     */
    suspend fun toolStats(
        projectId: Long,
        userId: Long,
        from: Instant?,
        to: Instant?
    ): QaToolStatsResponse {
        val end = to ?: Instant.now(clock)
        val start = from ?: end.minus(DEFAULT_WINDOW)
        if (!start.isBefore(end)) {
            throw BadRequestException("from must be earlier than to")
        }

        val rows = statsRepository.aggregateTools(projectId, userId, start, end)
        val issues = statsRepository.aggregateIssues(projectId, userId, start, end)

        return QaToolStatsResponse(
            projectId = projectId.toString(),
            from = start,
            to = end,
            tools = rows.map {
                QaToolStatsCell(
                    tool = it.tool,
                    calls = it.calls,
                    runsHeld = it.runsHeld,
                    runsCalled = it.runsCalled
                )
            },
            // 행마다 같은 값이 실려 오므로 첫 행에서 읽는다. 도구가 아니라 창 전체의 성질이라
            // 셀에 두면 같은 수가 도구 수만큼 되풀이된다. 런이 없으면 행도 없어 0 이다.
            citations = QaCitationStats(
                verdicts = rows.firstOrNull()?.verdicts ?: 0,
                withCitation = rows.firstOrNull()?.verdictsWithCitation ?: 0
            ),
            issues = issues.map { QaIssueStatsCell(severity = it.severity, issues = it.issues) }
        )
    }
}

/** 합계 행이 없는 경우는 빈 스코프뿐이라 0으로 채운다. 화면이 null 총계를 다루지 않아도 되게 한다. */
private fun QaStatsRow?.toTotals(): QaStatsTotals =
    QaStatsTotals(
        runs = this?.runs ?: 0,
        completed = this?.completed ?: 0,
        failed = this?.failed ?: 0,
        cancelled = this?.cancelled ?: 0,
        active = this?.active ?: 0,
        inputTokens = this?.inputTokens ?: 0,
        outputTokens = this?.outputTokens ?: 0,
        cachedInputTokens = this?.cachedInputTokens ?: 0,
        reasoningTokens = this?.reasoningTokens ?: 0,
        costUsd = this?.costUsd,
        llmCalls = this?.llmCalls ?: 0,
        avgCompletedDurationMs = this?.avgCompletedDurationMs,
        verdictKnown = this?.verdictKnown ?: 0,
        stepsTotal = this?.stepsTotal ?: 0,
        stepsPassed = this?.stepsPassed ?: 0,
        casesTotal = this?.casesTotal ?: 0,
        casesPassed = this?.casesPassed ?: 0,
        scoredRuns = this?.scoredRuns ?: 0,
        correctPass = this?.correctPass ?: 0,
        falseAlarm = this?.falseAlarm ?: 0,
        miss = this?.miss ?: 0,
        correctFail = this?.correctFail ?: 0,
        unreported = this?.unreported ?: 0
    )

private fun QaStatsRow.toCell() = QaRunConfigStatsCell(
    model = model,
    reasoningEffort = reasoningEffort,
    promptVersion = promptVersion,
    agentArch = agentArch,
    runs = runs,
    completed = completed,
    failed = failed,
    cancelled = cancelled,
    active = active,
    inputTokens = inputTokens,
    outputTokens = outputTokens,
    cachedInputTokens = cachedInputTokens,
    reasoningTokens = reasoningTokens,
    costUsd = costUsd,
    llmCalls = llmCalls,
    avgCompletedDurationMs = avgCompletedDurationMs,
    verdictKnown = verdictKnown,
    stepsTotal = stepsTotal,
    stepsPassed = stepsPassed,
    casesTotal = casesTotal,
    casesPassed = casesPassed,
    scoredRuns = scoredRuns,
    correctPass = correctPass,
    falseAlarm = falseAlarm,
    miss = miss,
    correctFail = correctFail,
    unreported = unreported
)
