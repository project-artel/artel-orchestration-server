package kr.artel.orchestration.qa.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal
import java.time.Instant

/**
 * QA 런을 실행 설정 축으로 집계한다(ARTEL-239 후속).
 *
 * `qa_try`를 읽지만 [QaTryRepository]와 분리했다 — 저쪽은 런 하나의 생명주기를 쓰고, 이쪽은
 * 여러 런을 접어 읽기만 한다. GROUPING SETS와 사전 집계 조인은 파생 쿼리로 표현되지 않아
 * [DatabaseClient]를 쓴다(`TestCaseVectorSearchRepository`와 같은 이유).
 */
@Repository
class QaStatsRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * [projectId]의 런을 `(model, reasoning_effort, prompt_version, agent_arch)` 4-튜플로 접는다.
     *
     * **왜 축 이름을 파라미터로 받지 않나.** 런은 이 4-튜플로 분할되므로, 전체 조합을 한 번 접어
     * 두면 단일 축 분해도 두 축 매트릭스도 전체 합계도 클라이언트에서 부분합으로 나온다. 축을
     * 파라미터로 받으면 컬럼 이름을 SQL에 끼워 넣는 자리가 생기고, 화이트리스트를 한 번 틀리면
     * 그대로 주입 지점이 된다.
     *
     * **왜 `llm_usage`를 미리 접나.** `qa_try`에 바로 조인하면 런 하나가 그 런이 부른 LLM 호출
     * 수만큼 복제돼 `COUNT(*)`가 런 수가 아니라 호출 수를 센다. 런당 한 줄로 접은 뒤 LEFT JOIN
     * 해야 런 수가 보존된다.
     *
     * **왜 GROUPING SETS인가.** 전체 합계 행을 같은 문장에서 받는다. 셀은 [limit]에 잘릴 수 있는데
     * 잘린 셀의 합으로 총계를 내면 대시보드가 프로젝트 실제 런 수보다 적은 수를 보여주면서 그
     * 차이를 설명하지 못한다. 총계는 자르기 전 전체에서 나온다.
     *
     * NULL 축은 버리지 않는다. 마이그레이션 이전 런과 `run_config`를 안 돌려주는 구버전 Agent가
     * 모두 NULL이고, 이들을 빼면 셀 합계와 총계가 어긋난다. 실제 NULL 값과 GROUPING SETS가 접어
     * 버린 NULL은 `GROUPING()` 마스크로 갈린다.
     *
     * @param from 포함. 기준은 `qa_try.started_at`이다 — 런에 귀속시키는 집계라 비용도 그 런이
     *   시작된 구간에 계산된다(`llm_usage.called_at`이 아니다).
     * @param to 배타.
     * @param limit 셀 최대 개수. 한 줄 더 읽어 잘림 여부를 판정한다.
     */
    suspend fun aggregateByRunConfig(
        projectId: Long,
        userId: Long,
        from: Instant,
        to: Instant,
        limit: Int
    ): QaStatsAggregate {
        val rows = databaseClient.sql(
            """
            WITH scoped AS (
                SELECT qt.id,
                       qt.model,
                       qt.reasoning_effort,
                       qt.prompt_version,
                       qt.agent_arch,
                       qt.status,
                       qt.steps_total,
                       qt.steps_passed,
                       qt.cases_total,
                       qt.cases_passed,
                       qt.started_at,
                       qt.completed_at
                  FROM qa_try qt
                  JOIN test_scenario ts ON ts.id = qt.test_scenario_id
                  JOIN project_member pm
                    ON pm.project_id = ts.project_id AND pm.app_user_id = :userId
                 WHERE ts.project_id = :projectId
                   AND qt.started_at >= :from
                   AND qt.started_at < :to
            ),
            run_usage AS (
                SELECT u.reference_id                AS qa_try_id,
                       SUM(u.input_tokens)           AS input_tokens,
                       SUM(u.output_tokens)          AS output_tokens,
                       SUM(u.cached_input_tokens)    AS cached_input_tokens,
                       SUM(u.reasoning_tokens)       AS reasoning_tokens,
                       SUM(u.cost_usd)               AS cost_usd,
                       COUNT(*)                      AS calls
                  FROM llm_usage u
                 WHERE u.service = 'QA_RUN'
                   AND u.reference_id IN (SELECT id FROM scoped)
                 GROUP BY u.reference_id
            )
            SELECT GROUPING(s.model, s.reasoning_effort, s.prompt_version, s.agent_arch) AS grouping_mask,
                   s.model                                                    AS model,
                   s.reasoning_effort                                         AS reasoning_effort,
                   s.prompt_version                                           AS prompt_version,
                   s.agent_arch                                               AS agent_arch,
                   COUNT(*)                                                   AS runs,
                   COUNT(*) FILTER (WHERE s.status = 'COMPLETED')             AS completed,
                   COUNT(*) FILTER (WHERE s.status = 'FAILED')                AS failed,
                   COUNT(*) FILTER (WHERE s.status = 'CANCELLED')             AS cancelled,
                   COUNT(*) FILTER (WHERE s.status IN ('STARTING', 'RUNNING')) AS active,
                   COALESCE(SUM(ru.input_tokens), 0)                          AS input_tokens,
                   COALESCE(SUM(ru.output_tokens), 0)                         AS output_tokens,
                   COALESCE(SUM(ru.cached_input_tokens), 0)                   AS cached_input_tokens,
                   COALESCE(SUM(ru.reasoning_tokens), 0)                      AS reasoning_tokens,
                   -- 0으로 뭉개지 않는다. 단가를 안 주는 provider가 있어 NULL은 "공짜"가 아니라
                   -- "모른다"이고, 둘을 같은 0으로 보여주면 비용 비교가 조용히 틀린다.
                   SUM(ru.cost_usd)                                           AS cost_usd,
                   COALESCE(SUM(ru.calls), 0)                                 AS llm_calls,
                   AVG(EXTRACT(EPOCH FROM (s.completed_at - s.started_at))::double precision * 1000)
                       FILTER (WHERE s.status = 'COMPLETED')                  AS avg_completed_duration_ms,
                   -- 판정 커버리지의 분모. 승격 값이 있는 런만 세면 그 셀의 합격률은 "깔끔하게
                   -- 종료된 런"에 조건부라 위로 편향되고, 잘 죽는 모델일수록 자기 최악 런이 빠져
                   -- 편향 크기가 축마다 다르다. 이 값을 같은 줄에 실어 그 차이를 숨길 수 없게 한다.
                   -- 네 컬럼은 함께 쓰이거나 함께 비므로 steps_total 하나가 기준이 된다.
                   COUNT(*) FILTER (WHERE s.steps_total IS NOT NULL)          AS verdict_known,
                   -- 평균이 아니라 합계를 낸다. 평균은 분모를 구조적으로 숨기지만, 합계는
                   -- verdict_known 없이는 아무 비율도 낼 수 없어 둘을 함께 읽게 만든다.
                   COALESCE(SUM(s.steps_total), 0)                            AS steps_total,
                   COALESCE(SUM(s.steps_passed), 0)                           AS steps_passed,
                   COALESCE(SUM(s.cases_total), 0)                            AS cases_total,
                   COALESCE(SUM(s.cases_passed), 0)                           AS cases_passed
              FROM scoped s
              LEFT JOIN run_usage ru ON ru.qa_try_id = s.id
             GROUP BY GROUPING SETS (
                 (s.model, s.reasoning_effort, s.prompt_version, s.agent_arch),
                 ()
             )
             -- 총계(마스크 15)를 먼저 놓아 LIMIT이 셀만 자르게 한다. 동점 셀의 순서가 실행마다
             -- 흔들리면 잘리는 셀도 흔들리므로 축 값으로 못박는다.
             ORDER BY grouping_mask DESC,
                      runs DESC,
                      s.model NULLS LAST,
                      s.reasoning_effort NULLS LAST,
                      s.prompt_version NULLS LAST,
                      s.agent_arch NULLS LAST
             LIMIT :limit
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("userId", userId)
            .bind("from", from)
            .bind("to", to)
            // 셀 한 줄과 총계 한 줄을 더 읽는다. 셀이 limit + 1개 나오면 잘린 것이다.
            .bind("limit", limit + 2)
            .map { row: Readable -> row.toStatsRow() }
            .flow()
            .toList()

        val total = rows.firstOrNull { it.groupingMask != 0 }
        val cells = rows.filter { it.groupingMask == 0 }
        return QaStatsAggregate(
            total = total,
            cells = cells.take(limit),
            truncated = cells.size > limit
        )
    }

    private fun Readable.toStatsRow() = QaStatsRow(
        groupingMask = get("grouping_mask", java.lang.Integer::class.java)!!.toInt(),
        model = get("model", String::class.java),
        reasoningEffort = get("reasoning_effort", String::class.java),
        promptVersion = get("prompt_version", String::class.java),
        agentArch = get("agent_arch", String::class.java),
        runs = longAt("runs"),
        completed = longAt("completed"),
        failed = longAt("failed"),
        cancelled = longAt("cancelled"),
        active = longAt("active"),
        inputTokens = longAt("input_tokens"),
        outputTokens = longAt("output_tokens"),
        cachedInputTokens = longAt("cached_input_tokens"),
        reasoningTokens = longAt("reasoning_tokens"),
        costUsd = get("cost_usd", BigDecimal::class.java),
        llmCalls = longAt("llm_calls"),
        avgCompletedDurationMs = get("avg_completed_duration_ms", java.lang.Double::class.java)?.toDouble(),
        verdictKnown = longAt("verdict_known"),
        stepsTotal = longAt("steps_total"),
        stepsPassed = longAt("steps_passed"),
        casesTotal = longAt("cases_total"),
        casesPassed = longAt("cases_passed")
    )

    private fun Readable.longAt(name: String): Long =
        get(name, java.lang.Long::class.java)!!.toLong()
}

/**
 * 접힌 결과 한 묶음.
 *
 * @param total 전체 합계 행. 스코프에 런이 하나도 없으면 GROUPING SETS의 `()`가 0건 행 하나를
 *   내므로 보통 null이 아니지만, 호출부가 그 가정에 기대지 않도록 nullable로 둔다.
 * @param cells 자르기가 적용된 조합별 행.
 * @param truncated 셀이 상한에 잘렸는지. true면 셀의 합은 [total]보다 작다.
 */
data class QaStatsAggregate(
    val total: QaStatsRow?,
    val cells: List<QaStatsRow>,
    val truncated: Boolean
)

/**
 * 집계 한 줄.
 *
 * @param groupingMask `GROUPING()` 마스크. 0이면 4축이 모두 실제 값(또는 실제 NULL)인 셀이고,
 *   0이 아니면 축이 접힌 합계 행이다. 실제 NULL 축과 접힌 축을 이 값 없이는 구분할 수 없다.
 * @param costUsd 단가를 아는 호출이 하나도 없으면 null. 0과 다르다.
 * @param avgCompletedDurationMs 완주한 런만의 평균 소요. 실패·취소는 중단 시점이 제각각이라
 *   같이 평균 내면 "빠른 실패"가 성능 개선으로 읽힌다.
 * @param verdictKnown 판정을 승격받은 런 수(ARTEL-299). 아래 네 합계의 분모이고, [runs]와의 차이가
 *   곧 판정을 모르는 런이다. 요약 없이 끝난 런(소켓 사망·취소)이 여기서 빠지므로, 이 값을 보지
 *   않고 합격률을 내면 그 비율은 깔끔하게 끝난 런에 조건부다.
 * @param stepsTotal 판정을 아는 런들의 스텝 수 합. 평균이 아니라 합계인 것은 [verdictKnown] 없이는
 *   비율을 낼 수 없게 하기 위해서다.
 */
data class QaStatsRow(
    val groupingMask: Int,
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val runs: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val active: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val llmCalls: Long,
    val avgCompletedDurationMs: Double?,
    val verdictKnown: Long,
    val stepsTotal: Long,
    val stepsPassed: Long,
    val casesTotal: Long,
    val casesPassed: Long
)
