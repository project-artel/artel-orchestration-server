package kr.artel.orchestration.qa.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitSingleOrNull
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.math.BigDecimal

/**
 * QA 히스토리에서 런 하나를 펼쳤을 때 그 자리에 서는 값들(ARTEL-819).
 *
 * [QaTryRepository]와 분리했다 — 저쪽은 런 하나의 생명주기를 쓰고, 이쪽은 네 표를 접어 읽기만
 * 한다. LATERAL 집계와 jsonb 추출은 파생 쿼리로 표현되지 않아 [DatabaseClient]를 쓴다
 * ([QaStatsRepository]와 같은 이유).
 *
 * **접근 판정은 여기서 안 한다.** 부르는 쪽이 `QaTryRepository.findAccessibleById`로 먼저
 * 걸러 온다(`IssueService.listByQaTry`와 같은 규약). 멤버십 조인을 이 질의마다 다시 쓰면
 * 유출 지점이 그만큼 늘어난다.
 */
@Repository
class QaTryDetailRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * 한 런의 시나리오 제목과, 다른 세 표에서 접어 온 수들.
     *
     * `qa_try` 자체의 값(status·model·prompt_version·steps·시각)은 여기서 안 읽는다. 부르는 쪽이
     * 접근 판정을 하며 이미 그 행을 손에 쥐고 있어서, 다시 읽으면 같은 행을 두 번 읽는 것이
     * 되고 두 읽기 사이에 값이 갈릴 자리가 생긴다.
     *
     * **`llm_usage`는 LATERAL로 접는다.** 그냥 조인하면 런 하나가 그 런이 부른 호출 수만큼
     * 복제돼, 옆에 붙은 `issue`·`capability_observation` 스칼라 서브쿼리는 멀쩡한데 `COUNT(*)`만
     * 호출 수를 세게 된다.
     *
     * `cost_usd`는 0으로 뭉개지 않는다. 단가를 안 주는 provider가 있어 NULL은 "공짜"가 아니라
     * "모른다"이고, 둘을 같은 0으로 보여주면 비용 비교가 조용히 틀린다.
     */
    suspend fun findDetail(qaTryId: Long): QaTryDetailRow? =
        databaseClient.sql(
            """
            SELECT ts.title AS scenario_title,
                   COALESCE(u.calls, 0)               AS llm_calls,
                   COALESCE(u.priced_calls, 0)        AS priced_calls,
                   COALESCE(u.estimated_calls, 0)     AS estimated_calls,
                   COALESCE(u.input_tokens, 0)        AS input_tokens,
                   COALESCE(u.cached_input_tokens, 0) AS cached_input_tokens,
                   COALESCE(u.cache_write_tokens, 0)  AS cache_write_tokens,
                   COALESCE(u.output_tokens, 0)       AS output_tokens,
                   u.cost_usd                         AS cost_usd,
                   -- 이 런이 게임에 대해 알아낸 것. `qa_log`의 ERROR가 아니다 — 그쪽은
                   -- orchestration이 agent 요청을 거절한 기록이라 장치가 삐끗한 것이지 결함이 아니다.
                   (SELECT COUNT(*) FROM issue i WHERE i.qa_try_id = qt.id) AS issues,
                   -- capability_observation.qa_try_id 는 V71 이후에만 채워진다. 그 전 런이 0으로
                   -- 보이는 것은 맞다 — 그때는 이 런이 무엇을 봤는지 기록 자체가 없다.
                   (SELECT COUNT(*) FROM capability_observation o WHERE o.qa_try_id = qt.id) AS feedback
              FROM qa_try qt
              JOIN test_scenario ts ON ts.id = qt.test_scenario_id
              LEFT JOIN LATERAL (
                  SELECT COUNT(*)                                   AS calls,
                         COUNT(lu.cost_usd)                         AS priced_calls,
                         COUNT(*) FILTER (WHERE lu.cost_estimated)  AS estimated_calls,
                         SUM(lu.input_tokens)                       AS input_tokens,
                         SUM(lu.cached_input_tokens)                AS cached_input_tokens,
                         SUM(lu.cache_write_tokens)                 AS cache_write_tokens,
                         SUM(lu.output_tokens)                      AS output_tokens,
                         SUM(lu.cost_usd)                           AS cost_usd
                    FROM llm_usage lu
                   WHERE lu.service = 'QA_RUN' AND lu.reference_id = qt.id
              ) u ON TRUE
             WHERE qt.id = :qaTryId
            """.trimIndent()
        )
            .bind("qaTryId", qaTryId)
            .map { row: Readable -> row.toDetailRow() }
            .awaitSingleOrNull()

    /**
     * 이 런이 부른 도구를 이름별로 센다. 많이 부른 것이 위다.
     *
     * `payload ->> 'tool'`을 쓴다(`payload ? 'tool'` 아님) — `?`는 R2DBC 파라미터 자리로 먹힌다.
     * 이름이 스키마로 강제되지는 않아 `message`를 대비책으로 둔다. `message`는 프레임의 표시용
     * 이름이라 같은 값이고, 둘 다 비면 셀 것이 없으므로 뺀다.
     *
     * 한 런의 `qa_log`는 대부분 `PULSE`이고 수천 행까지 간다(실측 2,735행 중 TOOL 75행). 인덱스가
     * `qa_try_id`로 좁힌 뒤 `type`으로 떨구고 큰 `payload`는 TOAST에 있어 매칭된 행만 펼쳐진다 —
     * 실측 1.6ms.
     */
    suspend fun findToolCalls(qaTryId: Long): List<QaTryToolCallRow> =
        databaseClient.sql(
            """
            SELECT COALESCE(ql.payload ->> 'tool', ql.message) AS tool,
                   COUNT(*)                                    AS calls
              FROM qa_log ql
             WHERE ql.qa_try_id = :qaTryId
               AND ql.type = 'TOOL'
               AND COALESCE(ql.payload ->> 'tool', ql.message) IS NOT NULL
             GROUP BY 1
             -- 동점 도구의 순서가 실행마다 흔들리면 같은 런을 두 번 열었을 때 목록이 달라 보인다.
             ORDER BY calls DESC, tool ASC
            """.trimIndent()
        )
            .bind("qaTryId", qaTryId)
            .map { row: Readable -> row.toToolCallRow() }
            .flow()
            .toList()

    private fun Readable.toDetailRow() = QaTryDetailRow(
        scenarioTitle = get("scenario_title", String::class.java)!!,
        llmCalls = getLong("llm_calls"),
        pricedCalls = getLong("priced_calls"),
        estimatedCalls = getLong("estimated_calls"),
        inputTokens = getLong("input_tokens"),
        cachedInputTokens = getLong("cached_input_tokens"),
        cacheWriteTokens = getLong("cache_write_tokens"),
        outputTokens = getLong("output_tokens"),
        costUsd = get("cost_usd", BigDecimal::class.java),
        issues = getLong("issues"),
        feedback = getLong("feedback")
    )

    private fun Readable.toToolCallRow() = QaTryToolCallRow(
        tool = get("tool", String::class.java)!!,
        calls = getLong("calls")
    )

    /**
     * `SUM()`은 `bigint`를, `COUNT()`도 `bigint`를 준다. 드라이버가 어느 쪽이든 [Number]로 주므로
     * 컬럼마다 타입을 찍는 대신 여기서 한 번 좁힌다.
     */
    private fun Readable.getLong(name: String): Long =
        get(name, Number::class.java)?.toLong() ?: 0L
}

/**
 * 런 하나를 펼쳤을 때의 수들. 화면 DTO가 아니라 질의 결과라, 어느 것도 여기서 계산하지 않는다.
 *
 * [pricedCalls]는 [costUsd]가 몇 개의 호출 위에 서 있는지다. [llmCalls]보다 작으면 그 금액은
 * 하한이고 화면이 그렇게 말해야 한다. [estimatedCalls]는 그중 우리가 토큰으로 계산한 몫이다 —
 * provider가 청구액을 안 줘서 그렇고, 하나라도 있으면 합계는 청구액과 같은 얼굴을 하면 안 된다.
 */
data class QaTryDetailRow(
    val scenarioTitle: String,
    val llmCalls: Long,
    val pricedCalls: Long,
    val estimatedCalls: Long,
    val inputTokens: Long,
    val cachedInputTokens: Long,
    val cacheWriteTokens: Long,
    val outputTokens: Long,
    val costUsd: BigDecimal?,
    val issues: Long,
    val feedback: Long
)

/** 도구 이름 하나와 이 런에서 불린 횟수. */
data class QaTryToolCallRow(
    val tool: String,
    val calls: Long
)
