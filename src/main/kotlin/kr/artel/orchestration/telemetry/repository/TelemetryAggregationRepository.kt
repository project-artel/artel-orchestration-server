package kr.artel.orchestration.telemetry.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/** `qa_try` 를 묶는 네 축. 어느 것이든 null 일 수 있다(V25 이전 행, `run_config` 를 안 돌려주는 agent-server). */
data class QaStructureKey(
    val agentArch: String?,
    val agentFingerprint: String?,
    val model: String?,
    val reasoningEffort: String?
)

/** [QaStructureKey] 한 묶음의 합계. 상태별 수와 tool 호출 수는 따로 읽는다. */
data class QaStructureTotals(
    val key: QaStructureKey,
    val tries: Long,
    val stepsTotal: Long?,
    val stepsPassed: Long?,
    val casesTotal: Long?,
    val casesPassed: Long?,
    val durationSecondsSum: Long,
    val durationSecondsMax: Long
)

data class QaStructureCount(val key: QaStructureKey, val name: String, val count: Long)

data class LlmUsageTotals(
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
    val costUsdSum: BigDecimal?,
    val latencyMsSum: Long
)

/**
 * 오류 한 묶음. [code] 와 [exceptionType] 은 DB 에 있는 그대로라 검사하지 않은 값이다. [firstFramesJson] 은 그 묶음에서
 * 가장 먼저 생긴 행의 `frames` JSON 이고 없으면 null 이다.
 */
data class ErrorGroup(
    val code: String?,
    val exceptionType: String?,
    val count: Long,
    val firstId: Long,
    val firstFramesJson: String?
)

/**
 * telemetry report 하나를 만드는 집계 쿼리. 행을 하나씩 읽지 않고 DB 에서 GROUP BY 로 접는다.
 *
 * **하루의 기준.** QA 쪽은 `qa_try.completed_at` 이 그날인 try 다. 끝난 try 만 걸음 수와 걸린 시간을 말할 수 있고,
 * 한 try 를 정확히 하루에 넣어야 같은 try 가 두 report 에 나뉘지 않는다. tool 호출과 ERROR 행도 그 try 를 따라간다 —
 * 자정을 넘긴 try 의 호출이 두 날로 갈리지 않는다. LLM 사용량은 `llm_usage.called_at` 이 기준이다(V24 의 규칙).
 *
 * **`qa_log` 를 날짜로 거르지 않는다.** 이 표는 가장 크고 `created_at` 이나 `type` index 가 없다(V35 주석). 그래서
 * 그날의 `qa_try` id 를 먼저 고르고 `idx_qa_log_try_id_desc`(`qa_try_id` 선두) 로 그 try 의 행만 읽는다.
 * `qa_try.completed_at` 에도 index 가 없지만 `qa_try` 는 `qa_log` 보다 몇 자릿수 작다.
 */
@Repository
class TelemetryAggregationRepository(
    private val databaseClient: DatabaseClient
) {

    suspend fun qaStructureTotals(from: Instant, to: Instant): List<QaStructureTotals> =
        databaseClient.sql(
            """
            SELECT t.agent_arch, t.agent_fingerprint, t.model, t.reasoning_effort,
                   COUNT(*)::bigint                AS tries,
                   SUM(t.steps_total)::bigint      AS steps_total,
                   SUM(t.steps_passed)::bigint     AS steps_passed,
                   SUM(t.cases_total)::bigint      AS cases_total,
                   SUM(t.cases_passed)::bigint     AS cases_passed,
                   COALESCE(ROUND(SUM(EXTRACT(EPOCH FROM (t.completed_at - t.started_at)))), 0)::bigint
                                                   AS duration_seconds_sum,
                   COALESCE(ROUND(MAX(EXTRACT(EPOCH FROM (t.completed_at - t.started_at)))), 0)::bigint
                                                   AS duration_seconds_max
              FROM qa_try t
             WHERE t.completed_at >= :from AND t.completed_at < :to
             GROUP BY t.agent_arch, t.agent_fingerprint, t.model, t.reasoning_effort
            """
        )
            .bind("from", from)
            .bind("to", to)
            .map { row, _ ->
                QaStructureTotals(
                    key = structureKey(row),
                    tries = row.long("tries"),
                    stepsTotal = row.nullableLong("steps_total"),
                    stepsPassed = row.nullableLong("steps_passed"),
                    casesTotal = row.nullableLong("cases_total"),
                    casesPassed = row.nullableLong("cases_passed"),
                    durationSecondsSum = row.long("duration_seconds_sum"),
                    durationSecondsMax = row.long("duration_seconds_max")
                )
            }
            .flow().toList()

    suspend fun qaStructureStatusCounts(from: Instant, to: Instant): List<QaStructureCount> =
        databaseClient.sql(
            """
            SELECT t.agent_arch, t.agent_fingerprint, t.model, t.reasoning_effort,
                   t.status          AS name,
                   COUNT(*)::bigint  AS occurrences
              FROM qa_try t
             WHERE t.completed_at >= :from AND t.completed_at < :to
             GROUP BY t.agent_arch, t.agent_fingerprint, t.model, t.reasoning_effort, t.status
            """
        )
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> QaStructureCount(structureKey(row), row.text("name"), row.long("occurrences")) }
            .flow().toList()

    /**
     * tool 이름마다 호출 수. TOOL 행의 tool 이름은 `payload.tool` 이다(agent-server 의 `ToolCallPayload`).
     *
     * 그 try 의 `run_config.tools` 에 없는 이름은 [otherToolName] 으로 바꾼 뒤 센다. 게임이 등록한 함수 이름이
     * 설치 밖으로 나가지 않게 하려는 것이다. `run_config.tools` 가 없는 옛 try 의 호출은 전부 [otherToolName] 이다.
     */
    suspend fun qaStructureToolCalls(from: Instant, to: Instant, otherToolName: String): List<QaStructureCount> =
        databaseClient.sql(
            """
            SELECT t.agent_arch, t.agent_fingerprint, t.model, t.reasoning_effort,
                   CASE
                       WHEN jsonb_typeof(t.run_config -> 'tools') = 'array'
                        AND (t.run_config -> 'tools') @> jsonb_build_array(l.payload ->> 'tool')
                       THEN l.payload ->> 'tool'
                       ELSE :otherToolName
                   END               AS name,
                   COUNT(*)::bigint  AS occurrences
              FROM qa_try t
              JOIN qa_log l ON l.qa_try_id = t.id
             WHERE t.completed_at >= :from AND t.completed_at < :to
               AND l.type = 'TOOL'
             GROUP BY 1, 2, 3, 4, 5
            """
        )
            .bind("from", from)
            .bind("to", to)
            .bind("otherToolName", otherToolName)
            .map { row, _ -> QaStructureCount(structureKey(row), row.text("name"), row.long("occurrences")) }
            .flow().toList()

    suspend fun llmUsageTotals(from: Instant, to: Instant): List<LlmUsageTotals> =
        databaseClient.sql(
            """
            SELECT u.service, u.provider, u.model, u.key_source,
                   COUNT(*)::bigint                    AS calls,
                   SUM(u.input_tokens)::bigint         AS input_tokens,
                   SUM(u.output_tokens)::bigint        AS output_tokens,
                   SUM(u.cached_input_tokens)::bigint  AS cached_input_tokens,
                   SUM(u.reasoning_tokens)::bigint     AS reasoning_tokens,
                   SUM(u.cache_write_tokens)::bigint   AS cache_write_tokens,
                   SUM(u.cost_usd)                     AS cost_usd_sum,
                   SUM(u.latency_ms)::bigint           AS latency_ms_sum
              FROM llm_usage u
             WHERE u.called_at >= :from AND u.called_at < :to
             GROUP BY u.service, u.provider, u.model, u.key_source
            """
        )
            .bind("from", from)
            .bind("to", to)
            .map { row, _ ->
                LlmUsageTotals(
                    service = row.text("service"),
                    provider = row.text("provider"),
                    model = row.text("model"),
                    keySource = row.text("key_source"),
                    calls = row.long("calls"),
                    inputTokens = row.long("input_tokens"),
                    outputTokens = row.long("output_tokens"),
                    cachedInputTokens = row.long("cached_input_tokens"),
                    reasoningTokens = row.long("reasoning_tokens"),
                    cacheWriteTokens = row.long("cache_write_tokens"),
                    costUsdSum = row.get("cost_usd_sum", BigDecimal::class.java),
                    latencyMsSum = row.long("latency_ms_sum")
                )
            }
            .flow().toList()

    /**
     * 그날 끝난 try 의 ERROR 행을 `payload.code` 와 `payload.exceptionType` 으로 묶는다. 두 key 와 `payload.frames`
     * 는 agent-server 의 `ErrorPayload` 가 싣는다. orchestration 이 남긴 ERROR 행에는 없어서 null 로 묶인다.
     */
    suspend fun qaRunErrorGroups(from: Instant, to: Instant): List<ErrorGroup> =
        databaseClient.sql(
            """
            SELECT l.payload ->> 'code'                                  AS code,
                   l.payload ->> 'exceptionType'                         AS exception_type,
                   COUNT(*)::bigint                                      AS occurrences,
                   MIN(l.id)                                             AS first_id,
                   ((ARRAY_AGG(l.payload -> 'frames' ORDER BY l.id))[1])::text AS first_frames
              FROM qa_try t
              JOIN qa_log l ON l.qa_try_id = t.id
             WHERE t.completed_at >= :from AND t.completed_at < :to
               AND l.type = 'ERROR'
             GROUP BY 1, 2
            """
        )
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> errorGroup(row) }
            .flow().toList()

    suspend fun orchestrationErrorGroups(from: Instant, to: Instant): List<ErrorGroup> =
        databaseClient.sql(
            """
            SELECT e.code,
                   e.exception_type,
                   COUNT(*)::bigint                                AS occurrences,
                   MIN(e.id)                                       AS first_id,
                   ((ARRAY_AGG(e.frames ORDER BY e.id))[1])::text  AS first_frames
              FROM telemetry_error_event e
             WHERE e.created_at >= :from AND e.created_at < :to
             GROUP BY e.code, e.exception_type
            """
        )
            .bind("from", from)
            .bind("to", to)
            .map { row, _ -> errorGroup(row) }
            .flow().toList()

    /**
     * 그날 끝난 try 를 돌린 agent-server 의 버전. `image_tag` 가 있으면 그것, 없으면 `git_sha` 다. 둘 다 없는 run 은
     * 빠진다. run 경로는 `qa_run.run_config` 에, 단일 시나리오 경로는 `qa_try.run_config` 에만 있어서 둘을 합친다.
     */
    suspend fun agentServerVersions(from: Instant, to: Instant): List<String> =
        databaseClient.sql(
            """
            WITH finished AS (
                SELECT t.qa_run_id, t.run_config
                  FROM qa_try t
                 WHERE t.completed_at >= :from AND t.completed_at < :to
            ),
            configs AS (
                SELECT f.run_config FROM finished f
                UNION ALL
                SELECT r.run_config FROM qa_run r WHERE r.id IN (SELECT qa_run_id FROM finished)
            )
            SELECT DISTINCT COALESCE(NULLIF(c.run_config ->> 'image_tag', ''), NULLIF(c.run_config ->> 'git_sha', ''))
                       AS version
              FROM configs c
            """
        )
            .bind("from", from)
            .bind("to", to)
            // 둘 다 없는 run 의 NULL 은 빈 문자열로 읽어 버린다. `flow()` 는 null 원소를 받지 않는다.
            .map { row, _ -> row.get("version", String::class.java).orEmpty() }
            .flow().toList()
            .filter { it.isNotEmpty() }

    private fun structureKey(row: Readable) = QaStructureKey(
        agentArch = row.get("agent_arch", String::class.java),
        agentFingerprint = row.get("agent_fingerprint", String::class.java),
        model = row.get("model", String::class.java),
        reasoningEffort = row.get("reasoning_effort", String::class.java)
    )

    private fun errorGroup(row: Readable) = ErrorGroup(
        code = row.get("code", String::class.java),
        exceptionType = row.get("exception_type", String::class.java),
        count = row.long("occurrences"),
        firstId = row.long("first_id"),
        firstFramesJson = row.get("first_frames", String::class.java)
    )

    private fun Readable.long(name: String): Long = requireNotNull(nullableLong(name)) { "$name is null" }

    private fun Readable.nullableLong(name: String): Long? = get(name, java.lang.Long::class.java)?.toLong()

    private fun Readable.text(name: String): String = requireNotNull(get(name, String::class.java)) { "$name is null" }
}
