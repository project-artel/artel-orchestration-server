package kr.artel.orchestration.knowledge.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.knowledge.entity.KnowledgeEdgeScopeSql
import kr.artel.orchestration.knowledge.entity.KnowledgeScope
import kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.DatabaseClient.GenericExecuteSpec
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/**
 * 지식 그래프의 읽기 경로(ARTEL-275).
 *
 * [KnowledgeEdgeRepository]와 같은 테이블을 보지만 분리했다. 그쪽은 쓰기(링크·해제)이고 이쪽은
 * 탐색이다 — [KnowledgeEmbeddingRepository]와 [KnowledgeVectorSearchRepository]를 가른 것과 같은
 * 이유이며, 여기 SQL은 파생 쿼리로 표현할 수 없다(창 함수, CTE, 벡터 캐스팅).
 *
 * ## 정규 id (canonical id)
 *
 * edge의 끝점은 baseline id로 저장된다(ARTEL-274). 그런데 스코프 런에서 그 baseline이 그림자에
 * 가려져 있으면 **결과로 내보낼 것은 그림자 행**이다 — 그 스코프에서는 그림자가 곧 그 항목이다.
 *
 * [KnowledgeScopeSql.VISIBLE]은 "가려진 baseline을 결과에서 뺀다"까지만 하고 "그림자로 갈아끼운다"는
 * 하지 않는다. 그것만 걸면 baseline에 걸린 edge가 스코프 런에서 **통째로 사라진다.**
 * 그래서 아래 `visible` CTE가 보이는 행마다 `COALESCE(shadows_id, id)`로 그것이 대표하는 baseline
 * id를 함께 내고, edge는 그 값으로 조인한다. 쓰기 쪽 `KnowledgeGraphService.canonicalIdOf`와
 * **같은 식이어야 한다** — 다르면 저장한 edge를 조회가 못 찾는다.
 */
@Repository
class KnowledgeGraphTraversalRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * [seedCanonicalIds]에 붙은 이웃을 한 레벨 가져온다. **노드당이 아니라 레벨당 질의 한 번**이다.
     *
     * 세 가지가 SQL 안에서 끝난다. 애플리케이션으로 미루면 안 되는 이유가 각각 다르다.
     *
     * 1. **중복 접기**(`rn_dup`). 스코프는 baseline과 같은 관계를 자기 스코프에 나란히 둘 수 있다
     *    (`uq_knowledge_edge_live`가 `scope_id`를 키에 갖는다). 툼스톤을 찍지 않은 채 그렇게 하면
     *    같은 이웃이 두 줄로 나온다. `ORDER BY (e.scope_id IS NULL)`이 **스코프 행을 먼저** 놓아
     *    baseline을 이기게 한다 — 스코프가 baseline을 덮는다는 뜻이 그것이다.
     * 2. **fanout 상한**(`rn`). 애플리케이션에서 자르면 그 상한이 접은 뒤 몇 행이 될지 모른다 —
     *    벡터 검색이 `LIMIT`을 접기 뒤에 거는 것과 같은 이유다.
     * 3. **정렬**. `CONTRADICTS`가 맨 앞인 것은 그것이 판정을 바꾸는 유일한 신호이기 때문이고,
     *    동점을 `e.id`로 못박는 것은 같은 그래프가 같은 확장을 내야 하기 때문이다.
     *
     * [visitedCanonicalIds]는 이미 내보낸 노드 전부다(seed 포함). 사이클은 이 집합만으로 죽는다 —
     * `A REFINES B`, `B CONTRADICTS A`는 한 행을 양방향에서 읽으므로 없으면 핑퐁한다.
     */
    suspend fun neighboursOf(
        projectId: Long,
        scope: KnowledgeScope,
        seedCanonicalIds: List<Long>,
        visitedCanonicalIds: Set<Long>,
        fanout: Int
    ): List<KnowledgeNeighbourRow> {
        if (seedCanonicalIds.isEmpty()) return emptyList()

        val seedBindings = seedCanonicalIds.indices.map { ":seed$it" }
        val seeds = seedBindings.joinToString(", ")
        // visited는 seed를 포함하므로 비는 일이 없지만, 빈 IN () 는 문법 오류라 방어해 둔다.
        val visitedList = visitedCanonicalIds.toList()
        val visitedBindings = visitedList.indices.map { ":visited$it" }
        val visitedClause =
            if (visitedList.isEmpty()) "" else "AND v.canonical_id NOT IN (${visitedBindings.joinToString(", ")})"

        // 어느 끝이 seed인가. 세 자리에서 같은 식을 써야 해서 상수로 뽑는다.
        val viaExpr = "CASE WHEN e.from_knowledge_id IN ($seeds) THEN e.from_knowledge_id ELSE e.to_knowledge_id END"
        val otherExpr = "CASE WHEN e.from_knowledge_id IN ($seeds) THEN e.to_knowledge_id ELSE e.from_knowledge_id END"

        var spec = databaseClient.sql(
            """
            WITH visible AS (
                SELECT k.id,
                       COALESCE(k.shadows_id, k.id) AS canonical_id,
                       k.tag, k.source, k.summary, k.version
                  FROM knowledge k
                 WHERE k.project_id = :projectId
                   AND k.deleted_at IS NULL
                   AND ${KnowledgeScopeSql.VISIBLE}
            ),
            candidate AS (
                SELECT e.id                       AS edge_id,
                       e.relation                 AS relation,
                       e.note                     AS note,
                       $viaExpr                   AS via_id,
                       CASE WHEN e.from_knowledge_id IN ($seeds) THEN 'OUT' ELSE 'IN' END AS direction,
                       v.id                       AS knowledge_id,
                       v.canonical_id             AS canonical_id,
                       v.tag                      AS tag,
                       v.source                   AS source,
                       v.summary                  AS summary,
                       v.version                  AS version,
                       ROW_NUMBER() OVER (
                           PARTITION BY $viaExpr, v.canonical_id, e.relation
                           -- 스코프 행이 baseline을 이긴다. false(=스코프 행)가 먼저 온다.
                           ORDER BY (e.scope_id IS NULL), e.id
                       ) AS rn_dup
                  FROM knowledge_edge e
                  JOIN visible v ON v.canonical_id = $otherExpr
                 WHERE e.project_id = :projectId
                   AND (e.from_knowledge_id IN ($seeds) OR e.to_knowledge_id IN ($seeds))
                   AND ${KnowledgeEdgeScopeSql.VISIBLE}
                   $visitedClause
            ),
            ranked AS (
                SELECT c.*,
                       ROW_NUMBER() OVER (
                           PARTITION BY c.via_id
                           ORDER BY CASE c.relation
                                        WHEN 'CONTRADICTS' THEN 0
                                        WHEN 'LEADS_TO'    THEN 1
                                        WHEN 'REFINES'     THEN 2
                                        WHEN 'DEPENDS_ON'  THEN 3
                                        ELSE 4
                                    END,
                                    c.edge_id
                       ) AS rn
                  FROM candidate c
                 WHERE c.rn_dup = 1
            )
            SELECT * FROM ranked WHERE rn <= :fanout ORDER BY via_id, rn
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("fanout", fanout)

        spec = spec.bindScope(scope)
        seedCanonicalIds.forEachIndexed { index, id -> spec = spec.bind("seed$index", id) }
        visitedList.forEachIndexed { index, id -> spec = spec.bind("visited$index", id) }

        return spec.map { row: Readable -> row.toNeighbourRow() }.flow().toList()
    }

    /**
     * 끝점이 **둘 다** [canonicalIds] 안에 있는 관계.
     *
     * [neighboursOf]는 visited 집합 때문에 이런 edge를 뺀다. 그런데 "히트 1이 히트 3과 모순"은 이
     * 기능이 말할 수 있는 가장 값진 것이라 따로 건진다 — 이웃 줄이 아니라 히트의 주석으로 렌더된다.
     */
    suspend fun edgesAmong(
        projectId: Long,
        scope: KnowledgeScope,
        canonicalIds: List<Long>
    ): List<KnowledgeEdgeAmongRow> {
        if (canonicalIds.size < 2) return emptyList()
        val bindings = canonicalIds.indices.map { ":id$it" }.joinToString(", ")

        var spec = databaseClient.sql(
            """
            SELECT e.from_knowledge_id AS from_id,
                   e.to_knowledge_id   AS to_id,
                   e.relation          AS relation,
                   e.note              AS note
              FROM knowledge_edge e
             WHERE e.project_id = :projectId
               AND e.from_knowledge_id IN ($bindings)
               AND e.to_knowledge_id IN ($bindings)
               AND ${KnowledgeEdgeScopeSql.VISIBLE}
             ORDER BY e.id
            """.trimIndent()
        ).bind("projectId", projectId)

        spec = spec.bindScope(scope)
        canonicalIds.forEachIndexed { index, id -> spec = spec.bind("id$index", id) }

        return spec.map { row: Readable ->
            KnowledgeEdgeAmongRow(
                fromKnowledgeId = row.getLong("from_id"),
                toKnowledgeId = row.getLong("to_id"),
                relation = row.getString("relation"),
                note = row.getString("note")
            )
        }.flow().toList()
    }

    /**
     * [knowledgeId]와 **의미가 가까운** 항목. 저장된 관계가 아니라 조회 시점 계산이다.
     *
     * **QUERY 벡터로 충분하고 CONTENT 백필에 의존하지 않는다.** `searchNearest`는 임베딩된 자연어
     * *질문*을 QUERY 벡터와 견주므로 색인 전체가 질문 공간에 산다(항목당 3개, ARTEL-184).
     * 항목↔항목도 양쪽을 그 공간에 두면 된다 — 같은 메커니즘을 다루는 두 항목은 겹치는 질문을
     * 낳고, 그 겹침이 곧 신호다. LLM이 이미 표현을 정규화해 뒀으므로 본문끼리 재는 것보다 나을
     * 여지도 있다.
     *
     * `CROSS JOIN seed` + `GROUP BY` + `MIN`은 두 항목의 질문 벡터 3×3 곱집합의 최소를 뜻한다.
     * 기존 검색이 3개에 대해 `MIN`을 쓰는 것과 같은 공격성이고, 일부러 일치시켰다.
     *
     * 자기 제외(`k.id <> :knowledgeId`)는 선택이 아니다 — 자기 벡터는 거리 0이라 언제나 1등이다.
     * 그림자를 seed로 받는 경우를 위해 정규 id로도 한 번 더 뺀다.
     *
     * ⚠️ [maxDistance]는 아직 **추측된 값**이다. 실제 코퍼스의 쌍별 거리 분포를 보고 무릎을 찾기
     * 전까지는 이 상수를 믿지 말 것 — 테스트도 이 값 자체를 단언하지 않는다.
     */
    suspend fun similarTo(
        projectId: Long,
        scope: KnowledgeScope,
        knowledgeId: Long,
        canonicalId: Long,
        kind: String,
        model: String,
        excludeCanonicalIds: Set<Long>,
        maxDistance: Double,
        limit: Int
    ): List<KnowledgeSimilarRow> {
        val excludeList = excludeCanonicalIds.toList()
        val excludeBindings = excludeList.indices.map { ":exclude$it" }
        val excludeClause = if (excludeList.isEmpty()) {
            ""
        } else {
            "AND COALESCE(k.shadows_id, k.id) NOT IN (${excludeBindings.joinToString(", ")})"
        }

        var spec = databaseClient.sql(
            """
            WITH seed AS (
                SELECT e.embedding
                  FROM knowledge_embedding e
                 WHERE e.knowledge_id = :knowledgeId
                   AND e.kind = :kind
                   AND e.model = :model
                   AND e.embedding IS NOT NULL
            )
            SELECT k.id                                  AS knowledge_id,
                   COALESCE(k.shadows_id, k.id)          AS canonical_id,
                   k.tag                                 AS tag,
                   k.source                              AS source,
                   k.summary                             AS summary,
                   k.version                             AS version,
                   MIN(e.embedding <=> s.embedding)      AS distance
              FROM knowledge_embedding e
              JOIN knowledge k ON k.id = e.knowledge_id
             CROSS JOIN seed s
             WHERE k.project_id = :projectId
               AND k.deleted_at IS NULL
               AND e.kind = :kind
               AND e.model = :model
               AND e.embedding IS NOT NULL
               AND k.id <> :knowledgeId
               AND COALESCE(k.shadows_id, k.id) <> :canonicalId
               AND ${KnowledgeScopeSql.VISIBLE}
               $excludeClause
             GROUP BY k.id, k.shadows_id, k.tag, k.source, k.summary, k.version
            HAVING MIN(e.embedding <=> s.embedding) <= :maxDistance
             ORDER BY distance ASC, k.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("knowledgeId", knowledgeId)
            .bind("canonicalId", canonicalId)
            .bind("kind", kind)
            .bind("model", model)
            .bind("maxDistance", maxDistance)
            .bind("limit", limit)

        spec = spec.bindScope(scope)
        excludeList.forEachIndexed { index, id -> spec = spec.bind("exclude$index", id) }

        return spec.map { row: Readable ->
            KnowledgeSimilarRow(
                knowledgeId = row.getLong("knowledge_id"),
                canonicalId = row.getLong("canonical_id"),
                tag = row.getString("tag"),
                source = row.getString("source"),
                summary = row.getString("summary"),
                version = row.getInt("version"),
                distance = row.get("distance", java.lang.Double::class.java)!!.toDouble()
            )
        }.flow().toList()
    }

    /**
     * 운영 런은 scope가 NULL이다. 타입 없는 NULL을 보내면 Postgres가 파라미터 타입을 정하지 못해
     * 질의 자체가 실패하므로 BIGINT로 못박아 보낸다([KnowledgeVectorSearchRepository]와 같은 이유).
     */
    private fun GenericExecuteSpec.bindScope(scope: KnowledgeScope): GenericExecuteSpec =
        scope.id?.let { bind("scopeId", it) } ?: bindNull("scopeId", java.lang.Long::class.java)

    private fun Readable.toNeighbourRow() = KnowledgeNeighbourRow(
        edgeId = getLong("edge_id"),
        relation = getString("relation"),
        note = getString("note"),
        viaCanonicalId = getLong("via_id"),
        direction = getString("direction"),
        knowledgeId = getLong("knowledge_id"),
        canonicalId = getLong("canonical_id"),
        tag = getString("tag"),
        source = getString("source"),
        summary = getString("summary"),
        version = getInt("version")
    )

    private fun Readable.getLong(name: String) = get(name, java.lang.Long::class.java)!!.toLong()
    private fun Readable.getInt(name: String) = get(name, java.lang.Integer::class.java)!!.toInt()
    private fun Readable.getString(name: String) = get(name, String::class.java)!!
}

/**
 * 한 레벨에서 건진 이웃 한 줄.
 *
 * @property viaCanonicalId 이 이웃이 어느 노드에 매달렸는가(정규 id).
 * @property knowledgeId **이 스코프에서 실제로 보이는 행**의 id. 그림자가 있으면 그림자의 id다.
 *   Agent에게 나가는 값이라 그 스코프에서 다시 조회할 수 있는 id여야 한다.
 * @property canonicalId 그 행이 대표하는 baseline id. visited 집합과 다음 레벨의 seed가 이 값이다.
 * @property direction seed 기준 방향. `OUT`이면 seed가 `from`이다. 대칭 관계는 렌더가 무시한다.
 */
data class KnowledgeNeighbourRow(
    val edgeId: Long,
    val relation: String,
    val note: String,
    val viaCanonicalId: Long,
    val direction: String,
    val knowledgeId: Long,
    val canonicalId: Long,
    val tag: String,
    val source: String,
    val summary: String,
    val version: Int
)

/** 히트끼리 걸린 관계 한 줄. 끝점은 둘 다 정규 id다. */
data class KnowledgeEdgeAmongRow(
    val fromKnowledgeId: Long,
    val toKnowledgeId: Long,
    val relation: String,
    val note: String
)

/** 벡터로 가까운 항목 한 줄. [distance]는 코사인 거리이고 유사도 변환은 서비스가 한다. */
data class KnowledgeSimilarRow(
    val knowledgeId: Long,
    val canonicalId: Long,
    val tag: String,
    val source: String,
    val summary: String,
    val version: Int,
    val distance: Double
)
