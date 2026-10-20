package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.project.config.StorageProperties
import kr.artel.orchestration.qa.dto.QaRunVerdictsResponse
import kr.artel.orchestration.qa.dto.QaStepVerdict
import kr.artel.orchestration.qa.dto.QaTryVerdicts
import kr.artel.orchestration.qa.dto.QaVerdictCapture
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service

private val EVIDENCE_STATES = setOf("verified", "uncertain", "none")

/**
 * 런의 스텝 판정과 그 근거를 읽는다(ARTEL-1134). QA 가 끝난 뒤 사용자가 "왜 이 판정인가" 를 보는 자리다.
 *
 * 근거는 따로 저장하지 않는다. agent 가 보낸 스텝 STATUS 가 qa_log 에 그대로 남으므로 그것을 읽는다 — 진행 중
 * 화면(SSE)과 끝난 뒤 화면이 같은 행을 본다.
 */
@Service
class QaVerdictService(
    private val qaRuns: QaRunRepository,
    private val qaTries: QaTryRepository,
    private val qaLogs: QaLogRepository,
    private val scenarios: TestScenarioRepository,
    private val storage: DocumentStorage,
    private val storageProperties: StorageProperties,
    private val objectMapper: ObjectMapper,
) {
    /** 보이지 않는 런은 404 다([QaRunChangeService] 와 같은 규칙). */
    suspend fun list(qaRunId: Long, appUserId: Long): QaRunVerdictsResponse {
        qaRuns.findAccessibleById(qaRunId, appUserId) ?: throw NotFoundException()
        val tries = qaTries.findByQaRunId(qaRunId).toList().sortedBy { it.id }.map { qaTry ->
            val qaTryId = requireNotNull(qaTry.id)
            val scenario = scenarios.findById(qaTry.testScenarioId)
            val steps = scenario?.let { objectMapper.readTree(it.steps.asString()) }
            // 한 스텝에 판정이 둘이면(FREE 의 덮어쓰기) 나중 것이 그 스텝의 판정이다.
            val rows = qaLogs.findStepVerdicts(qaTryId).toList().associateBy { payloadOf(it).path("step").asInt() }
            val payloads = rows.mapValues { (_, row) -> payloadOf(row) }
            val captures = captureLinks(qaTryId, payloads.values.flatMap(::capturedIds).toSet())
            QaTryVerdicts(
                qaTryId = qaTryId.toString(),
                testScenarioId = qaTry.testScenarioId.toString(),
                scenarioTitle = scenario?.title.orEmpty(),
                status = qaTry.status,
                verdicts = rows.toSortedMap().map { (step, row) ->
                    verdictOf(row, payloads.getValue(step), steps, captures)
                },
            )
        }
        return QaRunVerdictsResponse(qaRunId = qaRunId.toString(), tries = tries)
    }

    private fun verdictOf(
        row: QaLogEntity,
        payload: JsonNode,
        steps: JsonNode?,
        captures: Map<String, QaVerdictCapture>,
    ): QaStepVerdict {
        val step = payload.path("step").asInt()
        val checks = payload.path("checks").takeIf { it.isArray }
        val evidence = payload.path("evidence").asText("").takeIf { it in EVIDENCE_STATES } ?: "none"
        return QaStepVerdict(
            logId = requireNotNull(row.id).toString(),
            step = step,
            stepTitle = steps?.path(step - 1)?.path("action")?.asText()?.takeIf { it.isNotBlank() },
            caseId = payload.path("case_id").takeIf { it.canConvertToLong() }?.asLong()?.toString(),
            isVerification = payload.path("is_verification").asBoolean(false),
            passed = payload.path("status").asText() == "COMPLETED",
            message = payload.path("message").asText(row.message.orEmpty()),
            reason = payload.path("reason").asText("").takeIf { it.isNotBlank() },
            evidence = evidence,
            checks = checks,
            captures = capturedIds(payload).mapNotNull(captures::get),
            judgedAt = row.createdAt,
        )
    }

    private fun payloadOf(row: QaLogEntity): JsonNode = objectMapper.readTree(row.payload.asString())

    /** 판정이 근거로 댄 캡처 id. 기록과 맞은 것만 — 이 런에 없는 id 로 주소를 만들 수는 없다. */
    private fun capturedIds(payload: JsonNode): List<String> =
        payload.path("checks").flatMap { check -> check.path("sources") }
            .filter { it.path("kind").asText() == "capture" && it.path("citation").asText() == "match" }
            .mapNotNull { it.path("key").asText("").takeIf(String::isNotBlank) }
            .distinct()

    /** 캡처 기록(SCREENSHOT 행)의 저장 위치로 내려받는 주소를 새로 발급한다. 행에 있던 주소는 이미 만료됐을 수 있다. */
    private suspend fun captureLinks(qaTryId: Long, captureIds: Set<String>): Map<String, QaVerdictCapture> {
        if (captureIds.isEmpty()) return emptyMap()
        return qaLogs.findScreenshots(qaTryId, captureIds).toList().mapNotNull { row ->
            val captureId = row.messageId ?: return@mapNotNull null
            val objectKey = payloadOf(row).path("objectKey").asText("").takeIf(String::isNotBlank) ?: return@mapNotNull null
            val download = storage.presignDownload(
                objectKey,
                objectKey.substringAfterLast('/'),
                storageProperties.captureDownloadUrlTtl,
            )
            captureId to QaVerdictCapture(captureId, download.url, download.expiresAt)
        }.toMap()
    }
}
