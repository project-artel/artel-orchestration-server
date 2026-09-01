package kr.artel.orchestration.llmusage.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingle
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * `llm_usage`를 읽어 지출을 집계한다(ARTEL-233 후속).
 *
 * [LlmUsageRepository]는 적재 전용으로 남긴다 — 저쪽은 배치 INSERT 하나가 전부이고, 이쪽은
 * 다형 참조를 프로젝트로 푸는 조인과 GROUPING SETS라 파생 쿼리로 표현되지 않는다
 * ([kr.artel.orchestration.qa.repository.QaStatsRepository]와 같은 이유로 [DatabaseClient]를 쓴다).
 *
 * **왜 조인이 service마다 다른가.** `llm_usage.reference_id`는 `service` 값에 따라 다른 테이블을
 * 가리킨다(V24 주석). 프로젝트별로 나누려면 그 다형 참조를 여기서 풀어야 한다:
 *
 * - `QA_RUN`          → `qa_try.id` → `test_scenario.project_id`
 * - `SCENARIO`        → `test_run.id` → `test_run.project_id`
 * - `GAME_CONTEXT`    → `project_document.id` → `project_document.project_id`
 * - `KNOWLEDGE_QUERY` → `project.id` (이미 프로젝트다)
 * - `EMBEDDING`       → `project.id` (이미 프로젝트다)
 *
 * **왜 기준 시각이 `called_at`인가.** agent는 배치로 모아 보내므로 `created_at`은 수집 지연만큼
 * 뒤에 있고 월 경계에서는 날짜가 바뀐다. 돈이 나간 시점은 호출 시점이다(V24 주석).
 */
@Repository
class LlmUsageStatsRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * 기간의 지출을 네 축으로 동시에 접는다: service, (provider, model), project, 일자.
     *
     * **왜 한 문장인가.** 네 축은 같은 행 집합을 다르게 접은 것뿐이라 따로 쏘면 왕복이 넷이고,
     * 그 사이에 새 배치가 들어오면 화면의 축별 합계가 서로 어긋난다. GROUPING SETS는 넷과 총계를
     * 한 스냅샷에서 낸다.
     *
     * **왜 클라이언트에서 부분합을 내지 않나.** [QaStatsRepository]는 런이 4-튜플로 분할되므로
     * 셀 하나만 주면 됐지만, 여기서는 축이 서로 직교하지 않는다 — 한 호출이 service·model·project·
     * 일자를 동시에 갖는다. 분할이 아니라 네 개의 다른 접기라 서버가 넷 다 내야 한다.
     *
     * **왜 멤버십을 조인으로 거르나.** 프로젝트를 못 푼 행(`project_id IS NULL`)은 여기서 통째로
     * 빠진다. 그 행이 실제로 존재한다는 사실은 [countUnattributedCalls]가 건수로만 알린다 —
     * 관리자 role이 없어 "내 프로젝트가 아닌 지출"을 금액으로 보여줄 근거가 없기 때문이다.
     *
     * @param projectId null이면 사용자가 속한 전 프로젝트 합산.
     * @param from 포함, [to] 배타. 둘 다 `called_at` 기준이다.
     * @param zone 일별 버킷을 자를 시간대(IANA 이름). 호출부가 [java.time.ZoneId]로 이미 검증했다 —
     *   Postgres는 모르는 이름에 예외를 던지므로 여기 닿기 전에 걸러야 한다.
     */
    suspend fun aggregate(
        userId: Long,
        projectId: Long?,
        from: Instant,
        to: Instant,
        zone: String
    ): List<LlmUsageStatsRow> {
        val projectFilter = if (projectId == null) "" else "AND s.project_id = :projectId"
        val sql = """
            WITH scoped AS (
                SELECT u.service,
                       u.provider,
                       u.model,
                       u.input_tokens,
                       u.output_tokens,
                       u.cached_input_tokens,
                       u.reasoning_tokens,
                       u.cost_usd,
                       (u.called_at AT TIME ZONE :zone)::date AS called_on,
                       CASE u.service
                           WHEN 'QA_RUN'       THEN ts.project_id
                           WHEN 'SCENARIO'     THEN tr.project_id
                           WHEN 'GAME_CONTEXT' THEN pd.project_id
                           ELSE u.reference_id
                       END AS project_id
                  FROM llm_usage u
                  LEFT JOIN qa_try qt
                         ON u.service = 'QA_RUN' AND qt.id = u.reference_id
                  LEFT JOIN test_scenario ts
                         ON ts.id = qt.test_scenario_id
                  LEFT JOIN test_run tr
                         ON u.service = 'SCENARIO' AND tr.id = u.reference_id
                  LEFT JOIN project_document pd
                         ON u.service = 'GAME_CONTEXT' AND pd.id = u.reference_id
                 WHERE u.called_at >= :from
                   AND u.called_at < :to
            ),
            visible AS (
                SELECT s.*, p.name AS project_name
                  FROM scoped s
                  JOIN project_member pm
                    ON pm.project_id = s.project_id AND pm.app_user_id = :userId
                  JOIN project p
                    ON p.id = s.project_id AND p.deleted_at IS NULL
                 WHERE TRUE $projectFilter
            )
            SELECT GROUPING(v.service, v.provider, v.model, v.project_id, v.called_on) AS grouping_mask,
                   v.service                                    AS service,
                   v.provider                                   AS provider,
                   v.model                                      AS model,
                   v.project_id                                 AS project_id,
                   -- 이름은 id에 함수 종속이라 축에 넣지 않는다. 축을 하나 늘리면 GROUPING 마스크가
                   -- 한 비트 길어져 집합 판별만 복잡해지고, 얻는 것은 없다.
                   MAX(v.project_name)                          AS project_name,
                   v.called_on                                  AS called_on,
                   COALESCE(SUM(v.input_tokens), 0)             AS input_tokens,
                   COALESCE(SUM(v.output_tokens), 0)            AS output_tokens,
                   COALESCE(SUM(v.cached_input_tokens), 0)      AS cached_input_tokens,
                   COALESCE(SUM(v.reasoning_tokens), 0)         AS reasoning_tokens,
                   -- 0으로 뭉개지 않는다. 단가를 안 주는 provider가 있어 NULL은 "공짜"가 아니라
                   -- "모른다"이고, 둘을 같은 0으로 보여주면 비용 비교가 조용히 틀린다(V24 주석).
                   SUM(v.cost_usd)                              AS cost_usd,
                   COUNT(*)                                     AS calls,
                   -- 단가를 아는 호출 수. cost_usd 합계가 몇 건 위에 얹힌 값인지를 같은 줄에 실어,
                   -- 절반이 단가 미상인 합계를 전체 지출로 읽지 못하게 한다.
                   COUNT(v.cost_usd)                            AS priced_calls
              FROM visible v
             GROUP BY GROUPING SETS (
                 (v.service),
                 (v.provider, v.model),
                 (v.project_id),
                 (v.called_on),
                 ()
             )
             -- 일별은 시간순이어야 추이로 읽히고, 나머지 축은 큰 지출이 위에 와야 한다. 동점의
             -- 순서가 실행마다 흔들리지 않도록 축 값으로 못박는다.
             ORDER BY grouping_mask,
                      v.called_on,
                      calls DESC,
                      v.service NULLS LAST,
                      v.provider NULLS LAST,
                      v.model NULLS LAST,
                      v.project_id NULLS LAST
        """.trimIndent()

        var spec = databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("from", from)
            .bind("to", to)
            .bind("zone", zone)
        if (projectId != null) spec = spec.bind("projectId", projectId)

        return spec.map { row: Readable -> row.toStatsRow() }.flow().toList()
    }

    /**
     * 프로젝트를 못 푼 호출 수.
     *
     * `reference_id`가 비었거나(agent가 무엇의 호출인지 모르는 경로) 가리키던 행이 지워진 경우다.
     * 이 건수는 [aggregate]의 어느 합계에도 안 들어가므로, 알리지 않으면 화면의 "전체"가 조용히
     * 실제보다 작아진다.
     *
     * **왜 건수만인가.** 이 행들은 멤버십으로 거를 수 없어(어느 프로젝트인지 모른다) 토큰이나
     * 금액을 실으면 배포 전체의 지출이 아무 로그인 사용자에게나 나간다. 관리자 role이 생기면
     * 그때 이 자리에 금액을 붙인다.
     */
    suspend fun countUnattributedCalls(from: Instant, to: Instant): Long =
        databaseClient.sql(
            """
            SELECT COUNT(*) AS calls
              FROM llm_usage u
              LEFT JOIN qa_try qt
                     ON u.service = 'QA_RUN' AND qt.id = u.reference_id
              LEFT JOIN test_scenario ts
                     ON ts.id = qt.test_scenario_id
              LEFT JOIN test_run tr
                     ON u.service = 'SCENARIO' AND tr.id = u.reference_id
              LEFT JOIN project_document pd
                     ON u.service = 'GAME_CONTEXT' AND pd.id = u.reference_id
             WHERE u.called_at >= :from
               AND u.called_at < :to
               AND CASE u.service
                       WHEN 'QA_RUN'       THEN ts.project_id
                       WHEN 'SCENARIO'     THEN tr.project_id
                       WHEN 'GAME_CONTEXT' THEN pd.project_id
                       ELSE u.reference_id
                   END IS NULL
            """.trimIndent()
        )
            .bind("from", from)
            .bind("to", to)
            .map { row: Readable -> row.longAt("calls") }
            .awaitSingle()

    /**
     * QA 런 한 건씩의 토큰과 비용. 집계가 아니라 "이 실행이 얼마 썼나"에 답하는 목록이다.
     *
     * **왜 `llm_usage`를 미리 접지 않고 `GROUP BY qt.id`로 가나.** 여기서는 런당 한 줄이 결과 자체라
     * 복제가 생길 자리가 없다. 런 수를 세는 [QaStatsRepository]와 달리 `COUNT(*)`를 쓰지 않고
     * `COUNT(u.id)`를 쓰는 것이 그 차이다 — LEFT JOIN에서 호출이 없는 런은 0건이어야 한다.
     *
     * @param qaTryId 주면 그 런 하나만. artel-home의 실행 화면이 쓰는 경로다.
     * @param from,to `qa_try.started_at` 기준이다 — 런에 귀속시키는 목록이라 그 런이 시작된 구간에
     *   들어간다(`llm_usage.called_at`이 아니다).
     */
    suspend fun listQaRunUsage(
        userId: Long,
        projectId: Long?,
        qaTryId: Long?,
        from: Instant,
        to: Instant,
        limit: Int
    ): List<QaRunUsageRow> {
        val filters = buildString {
            if (projectId != null) append("\n               AND ts.project_id = :projectId")
            if (qaTryId != null) append("\n               AND qt.id = :qaTryId")
        }
        val sql = """
            SELECT qt.id                                       AS qa_try_id,
                   ts.project_id                               AS project_id,
                   qt.status                                   AS status,
                   qt.started_at                               AS started_at,
                   qt.completed_at                             AS completed_at,
                   qt.model                                    AS model,
                   qt.reasoning_effort                         AS reasoning_effort,
                   qt.prompt_version                           AS prompt_version,
                   qt.agent_arch                               AS agent_arch,
                   COALESCE(SUM(u.input_tokens), 0)            AS input_tokens,
                   COALESCE(SUM(u.output_tokens), 0)           AS output_tokens,
                   COALESCE(SUM(u.cached_input_tokens), 0)     AS cached_input_tokens,
                   COALESCE(SUM(u.reasoning_tokens), 0)        AS reasoning_tokens,
                   SUM(u.cost_usd)                             AS cost_usd,
                   COUNT(u.id)                                 AS calls,
                   COUNT(u.cost_usd)                           AS priced_calls
              FROM qa_try qt
              JOIN test_scenario ts ON ts.id = qt.test_scenario_id
              JOIN project_member pm
                ON pm.project_id = ts.project_id AND pm.app_user_id = :userId
              LEFT JOIN llm_usage u
                     ON u.service = 'QA_RUN' AND u.reference_id = qt.id
             WHERE qt.started_at >= :from
               AND qt.started_at < :to$filters
             GROUP BY qt.id, ts.project_id
             ORDER BY qt.started_at DESC
             LIMIT :limit
        """.trimIndent()

        var spec = databaseClient.sql(sql)
            .bind("userId", userId)
            .bind("from", from)
            .bind("to", to)
            .bind("limit", limit)
        if (projectId != null) spec = spec.bind("projectId", projectId)
        if (qaTryId != null) spec = spec.bind("qaTryId", qaTryId)

        return spec.map { row: Readable -> row.toQaRunUsageRow() }.flow().toList()
    }

    private fun Readable.toStatsRow() = LlmUsageStatsRow(
        groupingMask = get("grouping_mask", java.lang.Integer::class.java)!!.toInt(),
        service = get("service", String::class.java),
        provider = get("provider", String::class.java),
        model = get("model", String::class.java),
        projectId = get("project_id", java.lang.Long::class.java)?.toLong(),
        projectName = get("project_name", String::class.java),
        calledOn = get("called_on", LocalDate::class.java),
        inputTokens = longAt("input_tokens"),
        outputTokens = longAt("output_tokens"),
        cachedInputTokens = longAt("cached_input_tokens"),
        reasoningTokens = longAt("reasoning_tokens"),
        costUsd = get("cost_usd", BigDecimal::class.java),
        calls = longAt("calls"),
        pricedCalls = longAt("priced_calls")
    )

    private fun Readable.toQaRunUsageRow() = QaRunUsageRow(
        qaTryId = get("qa_try_id", java.lang.Long::class.java)!!.toLong(),
        projectId = get("project_id", java.lang.Long::class.java)!!.toLong(),
        status = get("status", String::class.java)!!,
        startedAt = get("started_at", Instant::class.java)!!,
        completedAt = get("completed_at", Instant::class.java),
        model = get("model", String::class.java),
        reasoningEffort = get("reasoning_effort", String::class.java),
        promptVersion = get("prompt_version", String::class.java),
        agentArch = get("agent_arch", String::class.java),
        inputTokens = longAt("input_tokens"),
        outputTokens = longAt("output_tokens"),
        cachedInputTokens = longAt("cached_input_tokens"),
        reasoningTokens = longAt("reasoning_tokens"),
        costUsd = get("cost_usd", BigDecimal::class.java),
        calls = longAt("calls"),
        pricedCalls = longAt("priced_calls")
    )

    private fun Readable.longAt(name: String): Long =
        get(name, java.lang.Long::class.java)!!.toLong()
}

/**
 * GROUPING SETS 한 줄. 어느 집합의 줄인지는 [groupingMask]가 정한다.
 *
 * 마스크 상수는 [LlmUsageGroupingSet]에 있다. 축 값이 NULL인 것만으로는 "접힌 축"과 "값이 실제로
 * NULL인 축"을 구분할 수 없어 마스크가 필요하다 — 여기서는 [projectId]가 실제 NULL이 되는 일은
 * 없지만(멤버십 조인이 이미 걸렀다), 그 사실에 기대면 조인이 바뀔 때 조용히 틀린다.
 *
 * @property costUsd 단가를 아는 호출이 하나도 없으면 null. 0(공짜)과 다르다.
 * @property pricedCalls [costUsd]가 몇 건 위에 얹힌 값인지. [calls]와 다르면 그 합계는 부분합이다.
 */
data class LlmUsageStatsRow(
    val groupingMask: Int,
    val service: String?,
    val provider: String?,
    val model: String?,
    val projectId: Long?,
    val projectName: String?,
    val calledOn: LocalDate?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val calls: Long,
    val pricedCalls: Long
)

/**
 * `GROUPING(service, provider, model, project_id, called_on)`이 각 집합에서 내는 값.
 *
 * 비트는 왼쪽 축이 최상위이고, 1이 "이 축은 접혔다"이다. 예를 들어 service 집합은 service만
 * 살아 있으므로 `01111` = 15다. 이 값들을 손으로 다시 세지 않도록 여기 한 번만 적는다.
 */
object LlmUsageGroupingSet {
    const val SERVICE = 0b01111
    const val MODEL = 0b10011
    const val PROJECT = 0b11101
    const val DAY = 0b11110
    const val TOTAL = 0b11111
}

/**
 * QA 런 한 건의 지출.
 *
 * @property calls 이 런에 귀속된 LLM 호출 수. 0이면 아직 아무 호출도 안 갔거나(막 시작한 런),
 *   agent가 보낸 배치가 아직 안 왔거나, 유실된 것이다 — 셋을 여기서 구분할 수는 없다.
 */
data class QaRunUsageRow(
    val qaTryId: Long,
    val projectId: Long,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val calls: Long,
    val pricedCalls: Long
)
