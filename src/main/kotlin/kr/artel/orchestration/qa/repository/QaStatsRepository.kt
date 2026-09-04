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
     * @param projectId null이면 [userId]가 볼 수 있는 전 프로젝트를 합산한다. 그때 삭제된 프로젝트는
     *   빠진다 — 목록에도 없는 프로젝트가 총계에만 남으면 화면이 그 차이를 설명할 수 없다. 값을
     *   주면 그 프로젝트 하나이고, 그쪽은 삭제 여부를 따지지 않는다(id를 아는 호출부가 이미
     *   가리킨 것이라 감출 대상이 아니다).
     * @param seesAllProjects true면 멤버십을 따지지 않는다. 판단은 `PlatformAccessService`가 하고
     *   여기는 그 결과만 받는다.
     */
    suspend fun aggregateByRunConfig(
        projectId: Long?,
        userId: Long,
        seesAllProjects: Boolean,
        from: Instant,
        to: Instant,
        limit: Int
    ): QaStatsAggregate {
        val projectFilter =
            if (projectId == null) {
                "AND EXISTS (SELECT 1 FROM project p WHERE p.id = ts.project_id AND p.deleted_at IS NULL)"
            } else {
                "AND ts.project_id = :projectId"
            }
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
                 WHERE qt.started_at >= :from
                   AND qt.started_at < :to
                   $projectFilter
                   -- 참여자가 아니면 빈 집계가 된다. 예외로 갈라 답하면 프로젝트의 존재 여부가
                   -- 새어 나간다. DEVELOPER 등급은 이 조건을 통과한다(PlatformAccessService).
                   -- 프로젝트를 안 받아도 이 조건은 그대로다 — 여기가 빠지면 아무 로그인
                   -- 사용자가 배포 전체를 본다.
                   AND (:seesAllProjects OR EXISTS (
                       SELECT 1 FROM project_member pm
                        WHERE pm.project_id = ts.project_id AND pm.app_user_id = :userId
                   ))
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
            ),
            -- 기대-라벨 채점을 런당 한 줄로 접는다(ARTEL-301). 재채점하면 같은 런에
            -- grader_version이 다른 행이 여러 개 서므로, 접지 않고 조인하면 그 런이 버전 수만큼
            -- 복제돼 합계가 부풀고 scored_runs가 런 수가 아니라 채점 횟수를 센다.
            -- 최신 것 하나만 쓴다 — 옛 판정은 대조용으로 남아 있을 뿐 현재 성적이 아니다.
            -- grader 이름은 ExpectedStepsGrader의 EXPECTED_STEPS_GRADER와 같아야 한다.
            run_score AS (
                SELECT DISTINCT ON (sc.qa_try_id)
                       sc.qa_try_id,
                       (sc.detail -> 'matrix' ->> 'correct_pass')::int AS correct_pass,
                       (sc.detail -> 'matrix' ->> 'false_alarm')::int  AS false_alarm,
                       (sc.detail -> 'matrix' ->> 'miss')::int         AS miss,
                       (sc.detail -> 'matrix' ->> 'correct_fail')::int AS correct_fail,
                       (sc.detail ->> 'unreported')::int               AS unreported
                  FROM qa_try_score sc
                 WHERE sc.grader = 'expected-steps'
                   AND sc.qa_try_id IN (SELECT id FROM scoped)
                   -- 캐스트가 던지지 않도록 모양을 먼저 본다. `detail`은 JSONB라 스키마가 강제되지
                   -- 않으므로, 모양이 다른 행 **하나가** 이 프로젝트의 대시보드 전체를 500으로
                   -- 만든다. 걸러진 행은 scored_runs에도 안 들어가 "채점을 모른다"로 남고,
                   -- "0점"이 되지 않는다 — 이 파일이 verdict_known에서 지키는 규율과 같다.
                   -- ⚠️ detail 모양을 바꾸는 grader_version은 여기서 **조용히** 빠진다. 모양을
                   -- 바꾸는 쪽이 이 투영을 같이 고쳐야 한다. 던지게 두는 대안은 그 순간 화면이
                   -- 통째로 죽는 것이라 택하지 않았다.
                   AND jsonb_typeof(sc.detail -> 'matrix' -> 'correct_pass') = 'number'
                   AND jsonb_typeof(sc.detail -> 'matrix' -> 'false_alarm') = 'number'
                   AND jsonb_typeof(sc.detail -> 'matrix' -> 'miss') = 'number'
                   AND jsonb_typeof(sc.detail -> 'matrix' -> 'correct_fail') = 'number'
                   AND jsonb_typeof(sc.detail -> 'unreported') = 'number'
                 ORDER BY sc.qa_try_id, sc.id DESC
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
                   COALESCE(SUM(s.cases_passed), 0)                           AS cases_passed,
                   -- 채점 커버리지의 분모(ARTEL-301). verdict_known과 **다른 수다**: 요약은 멀쩡히
                   -- 받았지만 시나리오에 기대 라벨이 하나도 없어 채점 대상이 아닌 런이 있다.
                   -- 0이면 아래 다섯도 0인데 그것은 "채점할 것이 없었다"이지 "0점"이 아니다.
                   COUNT(rs.qa_try_id)                                        AS scored_runs,
                   -- 오탐과 미탐을 한 숫자로 접지 않는다 — QA 에이전트에게 미탐이 훨씬 나쁘고
                   -- (못 찾은 버그는 출시된다), 접으면 그 방향이 사라져 두 종류의 나쁜 설정이
                   -- 같은 점수로 보인다.
                   COALESCE(SUM(rs.correct_pass), 0)                          AS correct_pass,
                   COALESCE(SUM(rs.false_alarm), 0)                           AS false_alarm,
                   COALESCE(SUM(rs.miss), 0)                                  AS miss,
                   COALESCE(SUM(rs.correct_fail), 0)                          AS correct_fail,
                   COALESCE(SUM(rs.unreported), 0)                            AS unreported
              FROM scoped s
              LEFT JOIN run_usage ru ON ru.qa_try_id = s.id
              LEFT JOIN run_score rs ON rs.qa_try_id = s.id
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
            .bind("userId", userId)
            .bind("seesAllProjects", seesAllProjects)
            .bind("from", from)
            .bind("to", to)
            // 셀 한 줄과 총계 한 줄을 더 읽는다. 셀이 limit + 1개 나오면 잘린 것이다.
            .bind("limit", limit + 2)
            // 질의에 :projectId 가 없을 때 묶으면 R2DBC 가 없는 파라미터라고 던진다.
            .let { if (projectId == null) it else it.bind("projectId", projectId) }
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
        casesPassed = longAt("cases_passed"),
        scoredRuns = longAt("scored_runs"),
        correctPass = longAt("correct_pass"),
        falseAlarm = longAt("false_alarm"),
        miss = longAt("miss"),
        correctFail = longAt("correct_fail"),
        unreported = longAt("unreported")
    )

    private fun Readable.longAt(name: String): Long =
        get(name, java.lang.Long::class.java)!!.toLong()

    /**
     * 에이전트가 무엇을 했는지. 도구 이름으로 접는다 (ARTEL-681).
     *
     * [aggregateByRunConfig] 는 런이 **어떻게 끝났나**를 낸다. 이쪽은 **무엇을 했나**다. 그
     * 차이가 지금까지 집계에 없었고, 그래서 `record_knowledge` 가 모든 런에서 0 회라는 것을
     * 사람이 `docker logs | grep` 으로 세서 알아냈다.
     *
     * **한 번도 안 불린 도구가 이 결과의 요점이다.** `qa_log` 만 `GROUP BY` 하면 0 인 도구는
     * 행이 아예 안 생겨 목록에서 사라지고, 읽는 쪽은 "그런 도구가 없다" 와 "있는데 안 썼다" 를
     * 가릴 수 없다. 그 둘은 완전히 다른 답이다.
     *
     * 그래서 런이 **쥐고 있던** 목록에서 시작한다. `qa_try.run_config` 의 `tools` 가 그것이고,
     * Agent 가 세션을 열 때 자기가 무엇을 들었는지 실어 보낸다(`run_config.py` 의 `tools`).
     * 추측이 아니라 그 런의 사실이라, 도구가 늘거나 줄어도 이 질의는 그대로다.
     *
     * `PULSE` 는 건드리지 않는다. 전량이 최대 74,368 자이고 여기서 세는 것은 `TOOL` 행뿐이다.
     *
     * @param from 포함, [to] 배타. [aggregateByRunConfig] 와 같은 기준(`qa_try.started_at`)을
     *   쓴다 — 두 집계가 다른 창을 보면 한 화면에서 나란히 읽을 수 없다.
     */
    suspend fun aggregateTools(
        projectId: Long,
        userId: Long,
        from: Instant,
        to: Instant
    ): List<QaToolStatsRow> =
        databaseClient.sql(
            """
            WITH scoped AS (
                SELECT qt.id, qt.run_config
                  FROM qa_try qt
                  JOIN test_scenario ts ON ts.id = qt.test_scenario_id
                  JOIN project_member pm
                    ON pm.project_id = ts.project_id AND pm.app_user_id = :userId
                 WHERE ts.project_id = :projectId
                   AND qt.started_at >= :from
                   AND qt.started_at < :to
            ),
            -- 각 런이 쥐고 있던 도구. 0 회를 말할 수 있게 하는 자리다.
            held AS (
                SELECT s.id AS qa_try_id, t.tool
                  FROM scoped s
                 CROSS JOIN LATERAL jsonb_array_elements_text(
                     COALESCE(s.run_config -> 'tools', '[]'::jsonb)
                 ) AS t(tool)
            ),
            -- 실제로 부른 것. `TOOL` 은 조작 도구만이 아니라 28 개 전부를 남긴다(ARTEL-608).
            called AS (
                SELECT l.qa_try_id,
                       l.payload ->> 'tool' AS tool,
                       COUNT(*)             AS calls
                  FROM qa_log l
                 WHERE l.type = 'TOOL'
                   AND l.qa_try_id IN (SELECT id FROM scoped)
                   AND l.payload ->> 'tool' IS NOT NULL
                 GROUP BY l.qa_try_id, l.payload ->> 'tool'
            ),
            -- `report_step` 이 근거를 댔는가. 지식을 읽으라고 준 자리가 실제로 쓰이는지는
            -- 호출 수로는 안 보인다 — 부르기는 매번 부르고 그 칸만 비어 있었다.
            cited AS (
                SELECT COUNT(*) FILTER (
                           WHERE jsonb_array_length(
                               COALESCE(l.payload -> 'args' -> 'used_knowledge_ids', '[]'::jsonb)
                           ) > 0
                       ) AS with_citation,
                       COUNT(*) AS verdicts
                  FROM qa_log l
                 WHERE l.type = 'TOOL'
                   AND l.payload ->> 'tool' = 'report_step'
                   AND l.qa_try_id IN (SELECT id FROM scoped)
            )
            SELECT h.tool                                        AS tool,
                   COALESCE(SUM(c.calls), 0)                     AS calls,
                   COUNT(DISTINCT h.qa_try_id)                   AS runs_held,
                   COUNT(DISTINCT c.qa_try_id)                   AS runs_called,
                   (SELECT with_citation FROM cited)             AS with_citation,
                   (SELECT verdicts FROM cited)                  AS verdicts
              FROM held h
              LEFT JOIN called c
                     ON c.qa_try_id = h.qa_try_id AND c.tool = h.tool
             GROUP BY h.tool
             ORDER BY calls ASC, h.tool ASC
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("userId", userId)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ ->
                QaToolStatsRow(
                    tool = row.get("tool", String::class.java)!!,
                    calls = row.longAt("calls"),
                    runsHeld = row.longAt("runs_held"),
                    runsCalled = row.longAt("runs_called"),
                    verdictsWithCitation = row.longAt("with_citation"),
                    verdicts = row.longAt("verdicts")
                )
            }
            .flow()
            .toList()

    /**
     * 보고된 결함을 severity 로 접는다 (ARTEL-681).
     *
     * 도구 집계와 가르는 것은 묻는 것이 다르기 때문이다 — 저쪽은 `report_issue` 를 **불렀나**를
     * 세고 이쪽은 그래서 **무엇이 남았나**를 센다. 오늘 BLOCKER 두 건이 가짜였는데, 호출 수만
     * 봐서는 그것이 두 건인지도 몰랐다.
     */
    suspend fun aggregateIssues(
        projectId: Long,
        userId: Long,
        from: Instant,
        to: Instant
    ): List<QaIssueStatsRow> =
        databaseClient.sql(
            """
            SELECT i.severity AS severity, COUNT(*) AS issues
              FROM issue i
              JOIN qa_try qt ON qt.id = i.qa_try_id
              JOIN test_scenario ts ON ts.id = qt.test_scenario_id
              JOIN project_member pm
                ON pm.project_id = ts.project_id AND pm.app_user_id = :userId
             WHERE ts.project_id = :projectId
               AND qt.started_at >= :from
               AND qt.started_at < :to
             GROUP BY i.severity
             ORDER BY COUNT(*) DESC, i.severity ASC
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("userId", userId)
            .bind("from", from)
            .bind("to", to)
            .map { row, _ ->
                QaIssueStatsRow(
                    severity = row.get("severity", String::class.java)!!,
                    issues = row.longAt("issues")
                )
            }
            .flow()
            .toList()
}

/**
 * 도구 하나가 이 창에서 어떻게 쓰였나.
 *
 * @property calls 부른 횟수. **0 이 유효한 답이고 이 집계의 요점이다** — 런이 쥐고 있었는데 한
 *   번도 안 부른 도구가 그렇게 나온다.
 * @property runsHeld 그 도구를 쥐고 있던 런 수. 도구가 새로 생기거나 빠지면 런마다 다르다.
 * @property runsCalled 실제로 부른 런 수. [runsHeld] 와의 차이가 "줬는데 안 쓴" 런이다.
 * @property verdictsWithCitation `used_knowledge_ids` 가 채워진 `report_step` 수. 행마다 같은
 *   값이 실린다 — 도구가 아니라 창 전체의 성질이라, 읽는 쪽이 어느 행에서 읽어도 같다.
 * @property verdicts `report_step` 총 호출 수. 위 값의 분모다.
 */
data class QaToolStatsRow(
    val tool: String,
    val calls: Long,
    val runsHeld: Long,
    val runsCalled: Long,
    val verdictsWithCitation: Long,
    val verdicts: Long
)

/** severity 하나에 몇 건이 보고됐나. */
data class QaIssueStatsRow(
    val severity: String,
    val issues: Long
)

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
    val casesPassed: Long,
    val scoredRuns: Long,
    val correctPass: Long,
    val falseAlarm: Long,
    val miss: Long,
    val correctFail: Long,
    val unreported: Long
)
