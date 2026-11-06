package kr.artel.orchestration.telemetry.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.telemetry.config.ArtelVersionProperties
import kr.artel.orchestration.telemetry.dto.ERROR_SOURCE_ORCHESTRATION
import kr.artel.orchestration.telemetry.dto.ERROR_SOURCE_QA_RUN
import kr.artel.orchestration.telemetry.dto.ErrorSummary
import kr.artel.orchestration.telemetry.dto.LlmUsageSummary
import kr.artel.orchestration.telemetry.dto.OTHER_TOOL_NAME
import kr.artel.orchestration.telemetry.dto.QaStructureUsage
import kr.artel.orchestration.telemetry.dto.TELEMETRY_SCHEMA_VERSION
import kr.artel.orchestration.telemetry.dto.TelemetryInstall
import kr.artel.orchestration.telemetry.dto.TelemetryReport
import kr.artel.orchestration.telemetry.repository.ErrorGroup
import kr.artel.orchestration.telemetry.repository.QaStructureCount
import kr.artel.orchestration.telemetry.repository.QaStructureKey
import kr.artel.orchestration.telemetry.repository.TelemetryAggregationRepository
import org.springframework.stereotype.Service
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset

/** frame 한 줄에 실을 수 있는 최대 개수. agent-server 와 orchestration 이 같은 상한을 쓴다. */
const val MAX_TELEMETRY_FRAMES = 10

/**
 * 오류 code 와 예외 이름에 허용하는 모양. ERROR 행의 payload 는 agent-server 만 쓰는 것이 아니라(SDK 가 보낸 payload 가
 * 그대로 남는 행도 있다) 이 두 key 에 문장이 들어 있을 수 있다. 이 모양이 아니면 null 로 바꿔 보낸다.
 */
private val IDENTIFIER_PATTERN = Regex("^[A-Za-z0-9_.$-]{1,200}$")

/** `module:function:line` 또는 `class:method:line`. 경로 구분자와 공백이 들어갈 수 없다. */
private val FRAME_PATTERN = Regex("^[A-Za-z0-9_.$<>-]{1,200}:[A-Za-z0-9_.$<>-]{1,200}:[0-9]{1,7}$")

/** agent-server 버전으로 받는 모양. docker image tag 와 git sha 를 다 받는다. */
private val VERSION_PATTERN = Regex("^[A-Za-z0-9_.:+-]{1,128}$")

/**
 * 하루치 telemetry report 를 만든다. 집계는 [TelemetryAggregationRepository] 가 DB 에서 하고, 여기서는 묶음을 합치고
 * 보낼 수 없는 값을 걸러 낸다.
 *
 * 같은 날과 같은 install id 로 두 번 만들면 같은 report 가 나온다. 목록은 모두 정렬해 순서가 DB 의 반환 순서를 따르지
 * 않게 한다 — 미리보기와 실제로 보낸 report 를 나란히 놓고 비교할 수 있어야 한다.
 */
@Service
class TelemetryReportBuilder(
    private val aggregationRepository: TelemetryAggregationRepository,
    private val versionProperties: ArtelVersionProperties,
    private val objectMapper: ObjectMapper
) {

    suspend fun build(reportDate: LocalDate, installId: String?): TelemetryReport {
        val from = reportDate.atStartOfDay().toInstant(ZoneOffset.UTC)
        val to = reportDate.plusDays(1).atStartOfDay().toInstant(ZoneOffset.UTC)
        return TelemetryReport(
            schemaVersion = TELEMETRY_SCHEMA_VERSION,
            install = TelemetryInstall(
                installId = installId,
                orchestrationVersion = versionProperties.reportedVersion,
                agentServerVersions = aggregationRepository.agentServerVersions(from, to)
                    .filter { VERSION_PATTERN.matches(it) }
                    .distinct()
                    .sorted(),
                reportDate = reportDate
            ),
            qaStructures = qaStructures(from, to),
            llmUsage = llmUsage(from, to),
            errors = errors(ERROR_SOURCE_QA_RUN, aggregationRepository.qaRunErrorGroups(from, to)) +
                errors(ERROR_SOURCE_ORCHESTRATION, aggregationRepository.orchestrationErrorGroups(from, to))
        )
    }

    private suspend fun qaStructures(from: Instant, to: Instant): List<QaStructureUsage> {
        val statusCounts = aggregationRepository.qaStructureStatusCounts(from, to).groupedByKey()
        val toolCalls = aggregationRepository.qaStructureToolCalls(from, to, OTHER_TOOL_NAME).groupedByKey()
        return aggregationRepository.qaStructureTotals(from, to)
            .map { totals ->
                QaStructureUsage(
                    agentArch = totals.key.agentArch,
                    agentFingerprint = totals.key.agentFingerprint,
                    model = totals.key.model,
                    reasoningEffort = totals.key.reasoningEffort,
                    tries = totals.tries,
                    triesByStatus = statusCounts[totals.key].orEmpty(),
                    stepsTotal = totals.stepsTotal,
                    stepsPassed = totals.stepsPassed,
                    casesTotal = totals.casesTotal,
                    casesPassed = totals.casesPassed,
                    durationSecondsSum = totals.durationSecondsSum,
                    durationSecondsMax = totals.durationSecondsMax,
                    toolCalls = toolCalls[totals.key].orEmpty()
                )
            }
            .sortedWith(
                compareBy<QaStructureUsage, String?>(nullsFirst()) { it.agentArch }
                    .thenBy(nullsFirst()) { it.agentFingerprint }
                    .thenBy(nullsFirst()) { it.model }
                    .thenBy(nullsFirst()) { it.reasoningEffort }
            )
    }

    private suspend fun llmUsage(from: Instant, to: Instant): List<LlmUsageSummary> =
        aggregationRepository.llmUsageTotals(from, to)
            .map {
                LlmUsageSummary(
                    service = it.service,
                    provider = it.provider,
                    model = it.model,
                    keySource = it.keySource,
                    calls = it.calls,
                    inputTokens = it.inputTokens,
                    outputTokens = it.outputTokens,
                    cachedInputTokens = it.cachedInputTokens,
                    reasoningTokens = it.reasoningTokens,
                    cacheWriteTokens = it.cacheWriteTokens,
                    costUsdSum = it.costUsdSum,
                    latencyMsSum = it.latencyMsSum
                )
            }
            .sortedWith(compareBy({ it.service }, { it.provider }, { it.model }, { it.keySource }))

    /**
     * 모양이 맞지 않는 code 와 예외 이름을 null 로 바꾼 뒤, 그래서 같아진 묶음을 다시 합친다. frames 는 합친 묶음 중
     * 가장 먼저 생긴 행의 것이다.
     */
    private fun errors(source: String, groups: List<ErrorGroup>): List<ErrorSummary> =
        groups
            .map { it.copy(code = it.code.takeIfIdentifier(), exceptionType = it.exceptionType.takeIfIdentifier()) }
            .groupBy { it.code to it.exceptionType }
            .map { (key, merged) ->
                ErrorSummary(
                    source = source,
                    code = key.first,
                    exceptionType = key.second,
                    count = merged.sumOf { it.count },
                    frames = parseFrames(merged.minBy { it.firstId }.firstFramesJson)
                )
            }
            .sortedWith(compareBy<ErrorSummary, String?>(nullsFirst()) { it.code }.thenBy(nullsFirst()) { it.exceptionType })

    /** 문자열 배열이 아니면 빈 목록이다. 모양이 맞지 않는 frame 은 버리고, 앞에서부터 [MAX_TELEMETRY_FRAMES] 개만 둔다. */
    private fun parseFrames(framesJson: String?): List<String> {
        if (framesJson == null) return emptyList()
        val node = runCatching { objectMapper.readTree(framesJson) }.getOrNull() ?: return emptyList()
        if (!node.isArray) return emptyList()
        return node.filter { it.isTextual }
            .map { it.asText() }
            .filter { FRAME_PATTERN.matches(it) }
            .take(MAX_TELEMETRY_FRAMES)
    }

    private fun String?.takeIfIdentifier(): String? = this?.takeIf { IDENTIFIER_PATTERN.matches(it) }

    private fun List<QaStructureCount>.groupedByKey(): Map<QaStructureKey, Map<String, Long>> =
        groupBy { it.key }.mapValues { (_, counts) -> counts.associate { it.name to it.count }.toSortedMap() }
}
