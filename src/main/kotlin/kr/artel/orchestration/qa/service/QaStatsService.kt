package kr.artel.orchestration.qa.service

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.qa.dto.QaRunConfigStatsCell
import kr.artel.orchestration.qa.dto.QaStatsLabelsResponse
import kr.artel.orchestration.qa.dto.QaStatsResponse
import kr.artel.orchestration.qa.dto.QaStatsTotals
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

/**
 * `label` 목록 상한. 선택기 `<select>` 하나에 들어가는 수라 상한이 필요하고, 이 값을 넘길 만큼
 * 실험이 쌓이면 고르는 자리 자체를 다시 설계해야 한다(검색·최근 목록). 그때까지의 방지선이다.
 */
private const val MAX_LABELS = 200

@Service
class QaStatsService(
    private val statsRepository: QaStatsRepository,
    private val platformAccessService: PlatformAccessService,
    private val clock: Clock
) {

    /**
     * QA 런을 실행 설정 축으로 접는다.
     *
     * [projectId]를 생략하면 호출한 사람이 볼 수 있는 전 프로젝트를 합산한다. 프로젝트 하나의
     * 표본으로는 model·prompt version·agent 구조가 갈리지 않는 일이 잦고, 그때 축을 비교하려면
     * 프로젝트를 하나씩 골라 눈으로 더해야 한다. `GET /api/llm-usage/stats`가 이미 그 모양이다.
     *
     * 참여자가 아니면 예외가 아니라 빈 집계다 — 멤버십 판정을 쿼리 안에서 하는
     * `QaTryRepository.findByProject`와 같은 동작이고, 여기만 403을 주면 프로젝트의 존재
     * 여부가 새어 나간다.
     *
     * `DEVELOPER` 등급은 그 판정을 통과한다. 이 서비스가 등급을 직접 읽지 않고
     * [PlatformAccessService]에 묻는 이유는, 등급을 여는 자리가 늘어날 때 그 판단이 한 곳에만
     * 남아 있어야 무엇이 열렸는지 셀 수 있기 때문이다.
     *
     * [testRunId]는 그 멤버십 판정에 **더해지는** 술어다. 남의 프로젝트 test run id 를 넣어도 빈
     * 집계가 나오고, 없는 id 라고 갈라 답하지 않는다 — 갈라 답하면 그 test run 의 존재 여부가
     * 새어 나간다. 생략하면 단독 실행 런(`qa_run_id IS NULL`)까지 포함해 지금까지처럼 전부 센다.
     *
     * [label]도 같은 자리에 더해지고 [testRunId]와 **독립이다.** 둘을 함께 걸 수 있어야 "1차 실험의
     * 9013 런" 을 물을 수 있다.
     */
    suspend fun stats(
        projectId: Long?,
        userId: Long,
        from: Instant?,
        to: Instant?,
        cellLimit: Int,
        testRunId: Long? = null,
        label: String? = null
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
            seesAllProjects = platformAccessService.seesAllProjects(userId),
            from = start,
            to = end,
            limit = cellLimit,
            testRunId = testRunId,
            label = label
        )
        return QaStatsResponse(
            projectId = projectId?.toString(),
            testRunId = testRunId?.toString(),
            label = label,
            from = start,
            to = end,
            total = aggregate.total.toTotals(),
            cells = aggregate.cells.map { it.toCell() },
            truncated = aggregate.truncated,
            cellLimit = cellLimit
        )
    }

    /**
     * 이미 쓰인 실험 묶음 이름의 목록.
     *
     * 화면이 `label` 을 자유 입력이 아니라 고르는 자리로 만들 수 있게 하려고 있다. 새 이름을 여기서
     * 만들지 않는다 — 이름은 run 을 걸 때 정하고, 이 목록은 그렇게 이미 쓰인 것만 돌려준다.
     *
     * 가시성은 [stats]와 **같은 술어**다. 남의 프로젝트 `label` 이 새면 안 되고, 목록이 집계보다
     * 넓으면 고른 순간 0건이 나오는 이름이 선택기에 선다.
     */
    suspend fun labels(projectId: Long?, userId: Long): QaStatsLabelsResponse =
        QaStatsLabelsResponse(
            projectId = projectId?.toString(),
            labels = statsRepository.listLabels(
                projectId = projectId,
                userId = userId,
                seesAllProjects = platformAccessService.seesAllProjects(userId),
                limit = MAX_LABELS
            )
        )
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
