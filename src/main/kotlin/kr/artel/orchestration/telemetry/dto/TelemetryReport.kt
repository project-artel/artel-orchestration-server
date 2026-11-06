package kr.artel.orchestration.telemetry.dto

import java.math.BigDecimal
import java.time.LocalDate

/** 지금 보내는 report 의 모양. 필드를 빼거나 뜻을 바꾸면 올린다. receiver 는 이 값이 아닌 report 를 400 으로 거절한다. */
const val TELEMETRY_SCHEMA_VERSION = 1

/** [ErrorSummary.source] 값. agent-server 가 qa_log 에 남긴 ERROR 행이다. */
const val ERROR_SOURCE_QA_RUN = "qa_run"

/** [ErrorSummary.source] 값. orchestration 이 5xx 로 답한 예외다(`telemetry_error_event`). */
const val ERROR_SOURCE_ORCHESTRATION = "orchestration"

/** [QaStructureUsage.toolCalls] 에서 agent-server 가 내장한 tool 이 아닌 이름을 모두 합친 key. */
const val OTHER_TOOL_NAME = "other"

/**
 * 직접 설치한 서버가 UTC 하루에 한 번 보내는 telemetry report. 보내는 쪽이 만들고, receiver 가 같은 타입으로 읽어
 * 검증한다.
 *
 * 무엇이 들어가지 않는지가 이 타입의 계약이다. qa_log 의 message, agent 의 생각과 action, screen capture, 게임
 * 상태, content map, 지식 문서, 시나리오와 테스트 케이스 문구, 프로젝트와 게임 이름, 사용자 정보는 어느 필드에도
 * 없다. 새 필드를 더할 때 이 목록에 걸리는지 먼저 본다.
 */
data class TelemetryReport(
    val schemaVersion: Int,
    val install: TelemetryInstall,
    val qaStructures: List<QaStructureUsage>,
    val llmUsage: List<LlmUsageSummary>,
    val errors: List<ErrorSummary>
)

data class TelemetryInstall(
    /**
     * `telemetry.install_id`. 사람이 아니라 설치에 붙은 무작위 UUID 다. consent 가 한 번도 켜진 적 없는 설치의
     * 미리보기에서만 null 이고, 보내는 report 와 receiver 가 받는 report 에서는 늘 있다.
     */
    val installId: String?,
    /** `ARTEL_VERSION`. 없으면 null 이다. */
    val orchestrationVersion: String?,
    /** 그날 끝난 QA run 의 `run_config` 에 있던 agent-server `image_tag`, 없으면 `git_sha`. 중복 없이 정렬한다. */
    val agentServerVersions: List<String>,
    /** report 가 다루는 UTC 날짜. */
    val reportDate: LocalDate
)

/**
 * 그날 끝난 `qa_try` 를 `(agentArch, agentFingerprint, model, reasoningEffort)` 로 묶은 한 줄.
 *
 * 합계 네 개는 요약을 받은 try 가 하나도 없으면 null 이다. 0 과 다르다 — 0 은 "통과한 step 이 없었다" 이고 null 은
 * "모른다" 다(`qa_try.steps_total` 과 같은 규율).
 */
data class QaStructureUsage(
    val agentArch: String?,
    val agentFingerprint: String?,
    val model: String?,
    val reasoningEffort: String?,
    val tries: Long,
    /** `qa_try.status` 값마다 try 수. */
    val triesByStatus: Map<String, Long>,
    val stepsTotal: Long?,
    val stepsPassed: Long?,
    val casesTotal: Long?,
    val casesPassed: Long?,
    /** `completed_at - started_at` 의 합(초). */
    val durationSecondsSum: Long,
    val durationSecondsMax: Long,
    /** tool 이름마다 호출 수. 그 try 의 `run_config.tools` 에 없는 이름은 [OTHER_TOOL_NAME] 에 합친다. */
    val toolCalls: Map<String, Long>
)

/** 그날 `called_at` 인 `llm_usage` 를 `(service, provider, model, keySource)` 로 묶은 한 줄. */
data class LlmUsageSummary(
    val service: String,
    val provider: String,
    val model: String,
    val keySource: String,
    val calls: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val cacheWriteTokens: Long,
    /** 단가를 모르는 호출만 있으면 null 이다. */
    val costUsdSum: BigDecimal?,
    val latencyMsSum: Long
)

/**
 * 오류를 `(source, code, exceptionType)` 로 묶은 한 줄. message 는 싣지 않는다.
 *
 * [frames] 는 그 묶음에서 처음 생긴 오류의 것이다. agent-server 쪽은 `module:function:line`, orchestration 쪽은
 * `class:method:line` 이고, 어느 쪽도 파일 시스템 경로를 담지 않는다.
 */
data class ErrorSummary(
    val source: String,
    val code: String?,
    val exceptionType: String?,
    val count: Long,
    val frames: List<String>
)
