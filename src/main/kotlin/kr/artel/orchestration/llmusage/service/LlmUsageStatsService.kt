package kr.artel.orchestration.llmusage.service

import kr.artel.orchestration.auth.service.PlatformAccessService
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.llmusage.dto.LlmUsageDailyCell
import kr.artel.orchestration.llmusage.dto.LlmUsageModelCell
import kr.artel.orchestration.llmusage.dto.LlmUsageProjectCell
import kr.artel.orchestration.llmusage.dto.LlmUsageServiceCell
import kr.artel.orchestration.llmusage.dto.LlmUsageStatsResponse
import kr.artel.orchestration.llmusage.dto.LlmUsageTotals
import kr.artel.orchestration.llmusage.dto.QaRunUsageResponse
import kr.artel.orchestration.llmusage.repository.LlmUsageGroupingSet
import kr.artel.orchestration.llmusage.repository.LlmUsageStatsRepository
import kr.artel.orchestration.llmusage.repository.LlmUsageStatsRow
import kr.artel.orchestration.llmusage.repository.QaRunUsageRow
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/** 기간을 안 주면 보는 창. "이번 달 얼마 썼나"가 이 안에 들어간다. */
private val DEFAULT_WINDOW: Duration = Duration.ofDays(30)

/**
 * 조회 창 상한.
 *
 * 응답 크기를 자르는 대신 창을 자른다 — 일별 줄 수가 곧 창의 날짜 수라, 창을 366일로 묶으면
 * 나머지 축(service 5개, model 수십 개, 참여 프로젝트 수십 개)까지 함께 유한해진다. 잘림 표시를
 * 응답에 넣고 화면이 그것을 설명하게 만드는 것보다, 애초에 못 자르게 하는 편이 낫다.
 */
private val MAX_WINDOW: Duration = Duration.ofDays(366)

/** QA 런 목록 상한. 이 화면은 훑어보는 자리이고, 더 필요하면 기간을 좁히는 것이 맞다. */
private const val MAX_QA_RUNS = 200

@Service
class LlmUsageStatsService(
    private val statsRepository: LlmUsageStatsRepository,
    private val platformAccessService: PlatformAccessService,
    private val clock: Clock
) {

    /**
     * 기간 지출을 service·model·project·일자 네 축으로 접는다.
     *
     * 참여자가 아닌 프로젝트를 지정하면 예외가 아니라 빈 집계다 — 여기만 403을 주면 프로젝트의
     * 존재 여부가 새어 나간다([kr.artel.orchestration.qa.service.QaStatsService]와 같은 규율).
     *
     * `DEVELOPER` 등급은 그 판정을 통과한다(ARTEL-742). 등급을 이 서비스가 직접 읽지 않고
     * [PlatformAccessService]에 묻는 것은, 등급을 여는 자리가 늘어날 때 그 판단이 한 곳에만 남아
     * 있어야 무엇이 열렸는지 셀 수 있기 때문이다.
     */
    suspend fun stats(
        userId: Long,
        projectId: Long?,
        from: Instant?,
        to: Instant?,
        zone: String?
    ): LlmUsageStatsResponse {
        val window = window(from, to)
        val zoneId = parseZone(zone)

        val rows = statsRepository.aggregate(
            userId = userId,
            projectId = projectId,
            seesAllProjects = platformAccessService.seesAllProjects(userId),
            from = window.first,
            to = window.second,
            zone = zoneId.id
        )

        return LlmUsageStatsResponse(
            projectId = projectId?.toString(),
            from = window.first,
            to = window.second,
            zone = zoneId.id,
            // 스코프에 호출이 하나도 없으면 GROUPING SETS의 `()`가 0건 행 하나를 내지만, 호출부가
            // 그 가정에 기대지 않도록 없을 때는 0으로 채운다.
            total = rows.firstOrNull { it.groupingMask == LlmUsageGroupingSet.TOTAL }.toTotals(),
            byService = rows.filter { it.groupingMask == LlmUsageGroupingSet.SERVICE }
                .map { LlmUsageServiceCell(service = it.service.orEmpty(), totals = it.toTotals()) },
            byModel = rows.filter { it.groupingMask == LlmUsageGroupingSet.MODEL }
                .map {
                    LlmUsageModelCell(
                        provider = it.provider.orEmpty(),
                        model = it.model.orEmpty(),
                        totals = it.toTotals()
                    )
                },
            byProject = rows.filter { it.groupingMask == LlmUsageGroupingSet.PROJECT }
                .map {
                    LlmUsageProjectCell(
                        projectId = it.projectId.toString(),
                        projectName = it.projectName.orEmpty(),
                        totals = it.toTotals()
                    )
                },
            daily = rows.filter { it.groupingMask == LlmUsageGroupingSet.DAY }
                .mapNotNull { row -> row.calledOn?.let { LlmUsageDailyCell(it, row.toTotals()) } },
            unattributedCalls = statsRepository.countUnattributedCalls(window.first, window.second)
        )
    }

    /**
     * QA 런 한 건씩의 지출. "QA 실행마다 몇 토큰"에 답하는 목록이다.
     *
     * 기간 기준이 [stats]와 다르다 — 여기는 `qa_try.started_at`, 저기는 `llm_usage.called_at`이다.
     * 런에 귀속시키는 목록이라 그 런이 **시작된** 구간에 들어가야 하고, 자정을 넘겨 도는 런의
     * 호출이 다음 날 줄로 새면 런 하나가 두 날에 걸친다.
     */
    suspend fun qaRuns(
        userId: Long,
        projectId: Long?,
        from: Instant?,
        to: Instant?,
        size: Int
    ): List<QaRunUsageResponse> {
        if (size !in 1..MAX_QA_RUNS) {
            throw BadRequestException("size must be between 1 and $MAX_QA_RUNS")
        }
        val window = window(from, to)
        return statsRepository.listQaRunUsage(
            userId = userId,
            projectId = projectId,
            qaTryId = null,
            seesAllProjects = platformAccessService.seesAllProjects(userId),
            from = window.first,
            to = window.second,
            limit = size
        ).map { it.toResponse() }
    }

    /**
     * 런 하나의 지출. 접근 권한이 없거나 없는 런이면 null이다.
     *
     * 창을 주지 않는다 — 특정 런을 이름으로 찾는 것이라 기간이 의미가 없고, 오래된 런을 열었을 때
     * 기본 창 밖이라 0으로 보이면 그것이 "안 썼다"로 읽힌다.
     *
     * `DEVELOPER` 등급은 [qaRuns]와 같이 통과한다. 목록에 낸 런을 단건으로 열면 없다고 답하는 것은
     * 같은 행을 두고 두 가지로 대답하는 것이라, 화면이 그 차이를 설명할 방법이 없다.
     */
    suspend fun qaRun(userId: Long, qaTryId: Long): QaRunUsageResponse? =
        statsRepository.listQaRunUsage(
            userId = userId,
            projectId = null,
            qaTryId = qaTryId,
            seesAllProjects = platformAccessService.seesAllProjects(userId),
            from = Instant.EPOCH,
            to = Instant.now(clock).plus(Duration.ofDays(1)),
            limit = 1
        ).firstOrNull()?.toResponse()

    /** 조회 창. [from] 생략은 [to]에서 [DEFAULT_WINDOW] 뒤로, [to] 생략은 지금이다. */
    private fun window(from: Instant?, to: Instant?): Pair<Instant, Instant> {
        val end = to ?: Instant.now(clock)
        val start = from ?: end.minus(DEFAULT_WINDOW)
        if (!start.isBefore(end)) {
            throw BadRequestException("from must be earlier than to")
        }
        if (Duration.between(start, end) > MAX_WINDOW) {
            throw BadRequestException("the window must not exceed ${MAX_WINDOW.toDays()} days")
        }
        return start to end
    }

    /**
     * 일별 버킷의 시간대.
     *
     * Postgres에 그대로 넘기기 전에 여기서 판정한다 — 모르는 이름을 `AT TIME ZONE`에 넣으면 DB가
     * 던져 이 조회가 문맥 없는 500이 되고, 어느 파라미터가 문제였는지 응답에 남지 않는다.
     */
    private fun parseZone(zone: String?): ZoneId {
        val name = zone?.takeIf { it.isNotBlank() } ?: return ZoneId.of("UTC")
        return try {
            ZoneId.of(name)
        } catch (_: DateTimeException) {
            throw BadRequestException("zone must be an IANA time zone name")
        }
    }
}

/** 합계 행이 없는 경우는 빈 스코프뿐이라 0으로 채운다. 화면이 null 총계를 다루지 않아도 되게 한다. */
private fun LlmUsageStatsRow?.toTotals(): LlmUsageTotals =
    LlmUsageTotals(
        inputTokens = this?.inputTokens ?: 0,
        outputTokens = this?.outputTokens ?: 0,
        cachedInputTokens = this?.cachedInputTokens ?: 0,
        reasoningTokens = this?.reasoningTokens ?: 0,
        // 0으로 떨어뜨리지 않는다. "단가 미상"이 "공짜"로 읽히면 비용 비교가 조용히 틀린다.
        costUsd = this?.costUsd,
        calls = this?.calls ?: 0,
        pricedCalls = this?.pricedCalls ?: 0
    )

private fun QaRunUsageRow.toResponse() = QaRunUsageResponse(
    qaTryId = qaTryId.toString(),
    projectId = projectId.toString(),
    status = status,
    startedAt = startedAt,
    completedAt = completedAt,
    model = model,
    reasoningEffort = reasoningEffort,
    promptVersion = promptVersion,
    agentArch = agentArch,
    totals = LlmUsageTotals(
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cachedInputTokens,
        reasoningTokens = reasoningTokens,
        costUsd = costUsd,
        calls = calls,
        pricedCalls = pricedCalls
    )
)
