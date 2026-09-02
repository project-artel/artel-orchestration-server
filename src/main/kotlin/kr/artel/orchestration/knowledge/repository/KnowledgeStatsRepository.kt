package kr.artel.orchestration.knowledge.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository
import java.time.Instant

/**
 * 지식창고의 결과를 QA 런의 실행 설정 축으로 접는다(ARTEL-255).
 *
 * V25가 런을 **무엇으로** 돌렸는지 남겼고, `knowledge_entry_facts`가 그 런이 만든 지식이 어떻게
 * 됐는지 남긴다. 이 질의는 둘을 잇는 자리다. 착상은 **후속 런이 공짜 심판**이라는 것 —
 * 어떤 런이 만든 지식을 나중 런이 지우면 그것이 부정 신호이고, 심판 LLM도 정답지도 필요 없다.
 *
 * `QaStatsRepository`와 같은 형태로 [DatabaseClient] 생 SQL을 쓴다. GROUPING SETS와 사전 집계는
 * 파생 쿼리로 표현되지 않는다.
 *
 * **롤업이 view가 아니라 여기 있는 이유.** 기간 필터가 파라미터인데 view는 파라미터를 못 받고,
 * 축을 view에 접어 넣으면 축을 하나 늘릴 때마다 view를 갈아야 한다. view는 파라미터 없는
 * "항목별 사실"까지만 맡는다.
 */
@Repository
class KnowledgeStatsRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * [projectId]의 지식 버전을 그것을 만든 런의 `(model, reasoning_effort, prompt_version,
     * agent_arch)` 4-튜플로 접는다.
     *
     * **왜 축 이름을 파라미터로 받지 않나.** `QaStatsRepository`의 판단을 그대로 따른다. 버전은 이
     * 4-튜플로 분할되므로, 전체 조합을 한 번 접어 두면 단일 축 분해도 두 축 매트릭스도 전체 합계도
     * 클라이언트에서 부분합으로 나온다. 축을 파라미터로 받으면 컬럼 이름을 SQL에 끼워 넣는 자리가
     * 생기고, 화이트리스트를 한 번 틀리면 그대로 주입 지점이 된다. 축이 늘어도 **view가 아니라 이
     * 질의만** 바뀐다.
     *
     * **왜 `qa_try`가 INNER JOIN인가.** 만든 런을 모르는 버전 — 이 마이그레이션 이전의 knowledge
     * 행(view가 버전 1로 합성하되 `created_by_qa_try_id`를 null로 두는 행) — 은 어느 축에도 속하지
     * 않는다. 축 통계에 넣을 자리가 없으므로 조인이 자동으로 떨군다. view 쪽에서 미리 빼지 않는
     * 것은 그 행들의 검색 사용량이 항목별 조회에서는 여전히 사실이기 때문이다.
     *
     * **왜 GROUPING SETS인가.** 전체 합계를 같은 문장에서 받는다. 셀은 [limit]에 잘릴 수 있는데,
     * 잘린 셀의 합으로 총계를 내면 화면이 실제보다 작은 수를 보여주면서 그 차이를 설명하지 못한다.
     *
     * NULL 축은 버리지 않는다. V25 이전 런과 `run_config`를 안 돌려주는 구버전 Agent가 모두 NULL이고,
     * 빼면 셀 합계와 총계가 어긋난다. 실제 NULL과 GROUPING SETS가 접어 버린 NULL은
     * [KnowledgeStatsRow.groupingMask]로 갈린다.
     *
     * @param from 포함. 기준은 **버전이 만들어진 시각**(`knowledge_entry_facts.created_at`)이다 —
     *   런의 시작 시각이 아니다. 같은 런이 기간 경계를 걸쳐 지식을 만들면 각 버전이 자기 시각의
     *   구간에 들어간다.
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
    ): KnowledgeStatsAggregate {
        val projectFilter =
            if (projectId == null) {
                "AND EXISTS (SELECT 1 FROM project p WHERE p.id = f.project_id AND p.deleted_at IS NULL)"
            } else {
                "AND f.project_id = :projectId"
            }
        val rows = databaseClient.sql(
            """
            WITH scoped AS (
                SELECT f.is_current,
                       f.created_by_qa_try_id,
                       f.deleted_at,
                       f.deleted_by_qa_try_id,
                       f.retrieval_count,
                       f.citation_count,
                       f.citation_known_count,
                       qt.model,
                       qt.reasoning_effort,
                       qt.prompt_version,
                       qt.agent_arch
                  FROM knowledge_entry_facts f
                  -- 만든 런을 모르는 버전은 여기서 떨어진다(위 주석 참조).
                  JOIN qa_try qt ON qt.id = f.created_by_qa_try_id
                 WHERE f.created_at >= :from
                   AND f.created_at < :to
                   $projectFilter
                   -- 참여자가 아니면 빈 집계가 된다. 예외로 갈라 답하면 프로젝트의 존재 여부가
                   -- 새어 나간다(QaStatsRepository와 같은 판단). DEVELOPER 등급은 이 조건을
                   -- 통과한다(PlatformAccessService). 프로젝트를 안 받아도 이 조건은 그대로다 —
                   -- 여기가 빠지면 아무 로그인 사용자가 배포 전체를 본다.
                   AND (:seesAllProjects OR EXISTS (
                       SELECT 1 FROM project_member pm
                        WHERE pm.project_id = f.project_id AND pm.app_user_id = :userId
                   ))
            )
            SELECT GROUPING(s.model, s.reasoning_effort, s.prompt_version, s.agent_arch) AS grouping_mask,
                   s.model                                        AS model,
                   s.reasoning_effort                             AS reasoning_effort,
                   s.prompt_version                               AS prompt_version,
                   s.agent_arch                                   AS agent_arch,
                   COUNT(*)                                       AS entry_versions,
                   COUNT(*) FILTER (WHERE s.is_current)           AS current_versions,
                   COUNT(*) FILTER (WHERE s.deleted_at IS NOT NULL) AS deleted_versions,
                   -- 다른 런이 지운 것만 센다. 자기가 만들고 자기가 지운 것은 그 런이 스스로
                   -- 고쳐 쓴 흔적이지 후속 런의 판정이 아니다 — 심판은 남이어야 성립한다.
                   COUNT(*) FILTER (
                       WHERE s.deleted_at IS NOT NULL
                         AND s.deleted_by_qa_try_id IS DISTINCT FROM s.created_by_qa_try_id
                   )                                              AS repudiated_versions,
                   -- SUM(bigint)는 numeric이라 그대로 읽으면 타입이 어긋난다. 명시적으로 되돌린다.
                   COALESCE(SUM(s.retrieval_count), 0)::BIGINT    AS retrieval_total,
                   COALESCE(SUM(s.citation_count), 0)::BIGINT     AS citation_total,
                   COALESCE(SUM(s.citation_known_count), 0)::BIGINT AS citation_known_total
              FROM scoped s
             GROUP BY GROUPING SETS (
                 (s.model, s.reasoning_effort, s.prompt_version, s.agent_arch),
                 ()
             )
             -- 총계(마스크 15)를 먼저 놓아 LIMIT이 셀만 자르게 한다. 동점 셀의 순서가 실행마다
             -- 흔들리면 잘리는 셀도 흔들리므로 축 값으로 못박는다.
             ORDER BY grouping_mask DESC,
                      entry_versions DESC,
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
        return KnowledgeStatsAggregate(
            total = total,
            cells = cells.take(limit),
            truncated = cells.size > limit
        )
    }

    private fun Readable.toStatsRow() = KnowledgeStatsRow(
        groupingMask = get("grouping_mask", java.lang.Integer::class.java)!!.toInt(),
        model = get("model", String::class.java),
        reasoningEffort = get("reasoning_effort", String::class.java),
        promptVersion = get("prompt_version", String::class.java),
        agentArch = get("agent_arch", String::class.java),
        entryVersions = longAt("entry_versions"),
        currentVersions = longAt("current_versions"),
        deletedVersions = longAt("deleted_versions"),
        repudiatedVersions = longAt("repudiated_versions"),
        retrievalTotal = longAt("retrieval_total"),
        citationTotal = longAt("citation_total"),
        citationKnownTotal = longAt("citation_known_total")
    )

    private fun Readable.longAt(name: String): Long =
        get(name, java.lang.Long::class.java)!!.toLong()
}

/**
 * 접힌 결과 한 묶음.
 *
 * @param total 전체 합계 행. 스코프가 비어도 GROUPING SETS의 `()`가 0건 행 하나를 내므로 보통
 *   null이 아니지만, 호출부가 그 가정에 기대지 않도록 nullable로 둔다.
 * @param cells 자르기가 적용된 조합별 행.
 * @param truncated 셀이 상한에 잘렸는지. true면 셀의 합은 [total]보다 작다.
 */
data class KnowledgeStatsAggregate(
    val total: KnowledgeStatsRow?,
    val cells: List<KnowledgeStatsRow>,
    val truncated: Boolean
)

/**
 * 집계 한 줄.
 *
 * @param groupingMask `GROUPING()` 마스크. 0이면 4축이 모두 실제 값(또는 실제 NULL)인 셀이고,
 *   0이 아니면 축이 접힌 합계 행이다. 실제 NULL 축과 접힌 축을 이 값 없이는 구분할 수 없다.
 * @param entryVersions 이 축 조합의 런들이 만든 content 버전 수. 항목 수가 아니다 —
 *   한 항목을 두 번 고치면 세 버전이다.
 * @param currentVersions 그중 아직 최신인 것.
 * @param deletedVersions 그중 현재 삭제 상태인 것.
 * @param repudiatedVersions 삭제하되 **만든 런과 다른 런이** 지운 것. "공짜 심판" 신호가 이 값이다.
 *
 *   ⚠️ **아직 수리와 폐기가 섞여 있을 수 있다.** 항목을 지우고 다시 기록하는 경로가 여전히
 *   열려 있어(artel-agent-server `FORGET_KNOWLEDGE_DESCRIPTION`) 수리 한 번이 DELETE + CREATE로
 *   나가면 여기 잡힌다. 둘을 가르려면 대체본이 원본을 `knowledge_edge`의 `REPLACES` 관계로
 *   가리켜야 한다(ARTEL-274). 그 관계가 실제로 쌓이기 전까지 이 값은 **"수리 + 폐기"의 합**으로
 *   읽어야 한다. (`update_knowledge`가 생긴 뒤로는 지우지 않는 수리가 늘어 섞임이 줄었지만,
 *   0이 됐다고 가정할 근거는 없다.)
 * @param retrievalTotal 이 런들이 만든 버전이 검색으로 나간 총 횟수.
 * @param citationTotal 그중 인용된 횟수.
 * @param citationKnownTotal 인용 여부를 **알 수 있었던** 횟수. 인용 보고 기능이 붙기 전에는 0이며,
 *   그래서 [citationTotal]이 0인 것을 "아무도 인용하지 않았다"로 읽으면 안 된다. 두 값을 함께
 *   내는 이유가 이것이다.
 */
data class KnowledgeStatsRow(
    val groupingMask: Int,
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val entryVersions: Long,
    val currentVersions: Long,
    val deletedVersions: Long,
    val repudiatedVersions: Long,
    val retrievalTotal: Long,
    val citationTotal: Long,
    val citationKnownTotal: Long
)
