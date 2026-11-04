package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.dto.QaVerdictTrendBucket
import kr.artel.orchestration.qa.dto.QaVerdictTrendBucketResponse
import kr.artel.orchestration.qa.dto.QaVerdictTrendBuildResponse
import kr.artel.orchestration.qa.dto.QaVerdictTrendCaseResponse
import kr.artel.orchestration.qa.dto.QaVerdictTrendResponse
import kr.artel.orchestration.qa.repository.QaVerdictTrendRepository
import org.springframework.stereotype.Service
import java.time.DateTimeException
import java.time.Duration
import java.time.Instant
import java.time.ZoneId

/**
 * 대시보드의 QA 판정 추이(ARTEL-1386). 네 집계(판정 · 미판정 · 실행 수 · 가장 많이 실패한 Test Case)를 칸 열쇠로
 * 합친다. 숫자의 뜻은 [QaVerdictTrendBucketResponse] 에 있다.
 */
@Service
class QaVerdictTrendService(
    private val repository: QaVerdictTrendRepository,
    private val access: ProjectAccessService,
) {
    suspend fun trend(
        projectId: Long,
        appUserId: Long,
        from: Instant,
        to: Instant,
        bucket: QaVerdictTrendBucket,
        timeZone: String,
        gameBuildId: Long?
    ): QaVerdictTrendResponse {
        if (!from.isBefore(to)) throw BadRequestException("from must be before to")
        if (Duration.between(from, to) > MAX_RANGE) throw BadRequestException("the range must be at most 366 days")
        val zone = try {
            ZoneId.of(timeZone).id
        } catch (_: DateTimeException) {
            throw BadRequestException("unknown timeZone: $timeZone")
        }
        access.requireMember(projectId, appUserId)

        val verdicts = repository.verdictCounts(projectId, from, to, bucket, zone, gameBuildId).associateBy { it.key }
        val undecided = repository.undecidedCounts(projectId, from, to, bucket, zone, gameBuildId).associateBy { it.key }
        val runs = repository.runCounts(projectId, from, to, bucket, zone, gameBuildId).associateBy { it.key }
        val top = repository.topFailing(projectId, from, to, bucket, zone, gameBuildId).associateBy { it.key }

        val keys = (verdicts.keys + undecided.keys + runs.keys).sortedWith(keyOrder(bucket))
        val buckets = keys.map { key ->
            val v = verdicts[key]
            val r = runs[key]
            QaVerdictTrendBucketResponse(
                key = key,
                startedAt = if (bucket == QaVerdictTrendBucket.RUN) r?.startedAt else null,
                runs = r?.runs ?: 0,
                passed = v?.passed ?: 0,
                failed = v?.failed ?: 0,
                regression = v?.regression ?: 0,
                undecided = undecided[key]?.undecided ?: 0,
                topFailing = top[key]?.let { QaVerdictTrendCaseResponse(it.testCaseId.toString(), it.caseNumber, it.title, it.failures) }
            )
        }
        val builds = repository.buildsIn(projectId, from, to).map {
            QaVerdictTrendBuildResponse(it.id.toString(), it.version, it.isEditor, it.createdAt)
        }
        return QaVerdictTrendResponse(bucket, from, to, zone, buckets, builds)
    }

    private companion object {
        val MAX_RANGE: Duration = Duration.ofDays(366)

        /** 날 · 주 열쇠는 `yyyy-MM-dd` 라 글자 순서가 곧 시간 순서다. 실행 열쇠는 id 라 수로 줄 세운다. */
        fun keyOrder(bucket: QaVerdictTrendBucket): Comparator<String> =
            if (bucket == QaVerdictTrendBucket.RUN) compareBy { it.toLongOrNull() ?: Long.MAX_VALUE } else naturalOrder()
    }
}
