package kr.artel.orchestration.release.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.release.dto.BuildRefResponse
import kr.artel.orchestration.release.dto.BuildVerificationDayResponse
import kr.artel.orchestration.release.dto.BuildVerificationProgressResponse
import kr.artel.orchestration.release.dto.BuildVerificationSummariesResponse
import kr.artel.orchestration.release.dto.BuildVerificationSummaryResponse
import kr.artel.orchestration.release.repository.BuildVerificationRepository
import kr.artel.orchestration.release.repository.VerificationBuildRow
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.DateTimeException
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

/**
 * 대시보드의 이번 빌드 검증 진행과 출시 판단의 재료(ARTEL-1386). 출시 판단 자체는 화면이 기준 숫자와 맞대어 정한다 —
 * 설정 화면이 저장 전 숫자로 미리 판정해야 하므로 판단 규칙을 한 곳(화면)에 둔다.
 */
@Service
class BuildVerificationService(
    private val repository: BuildVerificationRepository,
    private val access: ProjectAccessService,
    private val clock: Clock
) {
    suspend fun progress(projectId: Long, appUserId: Long, gameBuildId: Long, timeZone: String): BuildVerificationProgressResponse {
        val zone = zoneOf(timeZone)
        access.requireMember(projectId, appUserId)
        val build = repository.build(projectId, gameBuildId) ?: throw NotFoundException("게임 빌드를 찾을 수 없습니다.")
        val previous = repository.previousBuild(projectId, build)
        val timeline = timeline(projectId, build, previous)
        val now = clock.instant()

        val today = LocalDate.ofInstant(now, zone)
        val first = maxOf(LocalDate.ofInstant(build.createdAt, zone), today.minusDays(MAX_DAYS - 1))
        val runsByDay = repository.runStarts(projectId, build.id).groupingBy { LocalDate.ofInstant(it, zone) }.eachCount()
        val days = generateSequence(first) { it.plusDays(1) }.takeWhile { !it.isAfter(today) }.map { day ->
            val counts = timeline.countsBefore(day.plusDays(1).atStartOfDay(zone).toInstant())
            BuildVerificationDayResponse(day.toString(), counts.passed, counts.failed, counts.regression, counts.inconclusive, runsByDay[day] ?: 0)
        }.toList()

        return BuildVerificationProgressResponse(
            summary = summary(projectId, build, timeline, now),
            previousBuild = previous?.let(::ref),
            timeZone = zone.id,
            days = days
        )
    }

    /** 설정의 출시 기준 화면이 지난 빌드를 같은 숫자로 다시 판정하는 데 쓴다. 최근에 올라온 것부터. */
    suspend fun summaries(projectId: Long, appUserId: Long, limit: Int): BuildVerificationSummariesResponse {
        if (limit !in 1..MAX_SUMMARIES) throw BadRequestException("limit must be between 1 and $MAX_SUMMARIES")
        access.requireMember(projectId, appUserId)
        val now = clock.instant()
        val items = repository.libraryBuilds(projectId, limit).map { build ->
            summary(projectId, build, timeline(projectId, build, repository.previousBuild(projectId, build)), now)
        }
        return BuildVerificationSummariesResponse(items)
    }

    private suspend fun timeline(projectId: Long, build: VerificationBuildRow, previous: VerificationBuildRow?) = VerificationTimeline(
        cases = repository.cases(build.id),
        attempts = repository.attempts(projectId, build.id),
        previouslyPassed = previous?.let { repository.passedCaseIds(it.id) } ?: emptySet(),
        buildCreatedAt = build.createdAt
    )

    private suspend fun summary(projectId: Long, build: VerificationBuildRow, timeline: VerificationTimeline, now: Instant): BuildVerificationSummaryResponse {
        val counts = timeline.countsBefore(now.plusMillis(1))
        return BuildVerificationSummaryResponse(
            build = ref(build),
            total = counts.total,
            passed = counts.passed,
            failed = counts.failed,
            regression = counts.regression,
            inconclusive = counts.inconclusive,
            pending = counts.pending,
            carried = counts.carried,
            openBlockers = repository.openBlockers(projectId, build.id),
            lastJudgedAt = timeline.lastJudgedAt
        )
    }

    private fun ref(build: VerificationBuildRow) = BuildRefResponse(build.id.toString(), build.version, build.isEditor, build.createdAt)

    private fun zoneOf(timeZone: String): ZoneId =
        try {
            ZoneId.of(timeZone)
        } catch (_: DateTimeException) {
            throw BadRequestException("unknown timeZone: $timeZone")
        }

    private companion object {
        const val MAX_DAYS = 90L
        const val MAX_SUMMARIES = 20
    }
}
