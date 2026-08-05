package kr.artel.orchestration.knowledge.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/**
 * `knowledge_embedding`에 대한 코사인 거리 검색(ARTEL-186).
 *
 * [KnowledgeEmbeddingRepository]와 같은 테이블을 보지만 분리했다. 그쪽은 백필 큐(claim/실패 기록)의
 * 쓰기 경로이고 이쪽은 읽기 경로다. 한 클래스에 두면 "큐 접근"이라는 그 클래스의 책임 설명이 더는
 * 맞지 않게 된다.
 *
 * Spring Data 파생 쿼리가 아니라 [DatabaseClient]를 쓰는 이유는 [KnowledgeEmbeddingRepository]와
 * 같다: `vector` 타입에 R2DBC 코덱이 없어 문자열로 보내고 `::vector`로 캐스팅해야 한다.
 */
@Repository
class KnowledgeVectorSearchRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * 프로젝트 안에서 [queryVector]에 가장 가까운 knowledge를 [limit]개 찾는다.
     *
     * **`GROUP BY k.id` + `MIN(거리)`가 이 질의의 핵심이다.** 항목당 QUERY 벡터가 3개(ARTEL-184)라
     * 접지 않으면 같은 항목이 top-k를 여러 칸 차지해 top-10이 실질 top-3이 된다. 가장 가까운 벡터의
     * 거리를 그 항목의 점수로 삼는다.
     *
     * 접기를 애플리케이션이 아니라 SQL에서 하는 이유는 `LIMIT`이 **접은 뒤에** 걸려야 하기 때문이다.
     * DB에서 넉넉히 받아 코드에서 접으면 몇 개가 남을지 알 수 없어 상한이 상한 노릇을 못 한다.
     *
     * 스코프 조건 셋은 뺄 수 없다:
     * - `k.project_id = :projectId` — 다른 프로젝트의 지식이 새면 안 된다.
     * - `k.deleted_at IS NULL` — 소프트삭제(ARTEL-188)된 항목은 읽기 경로에서 빠져야 한다.
     * - `e.embedding IS NOT NULL` — 대기 행(V18의 큐 센티널)은 벡터가 없다. 빼지 않으면 거리가
     *   NULL인 그룹이 만들어져 아직 백필되지 않은 항목이 결과에 섞인다.
     */
    suspend fun searchNearest(
        projectId: Long,
        queryVector: String,
        kind: String,
        model: String,
        tags: List<String>,
        source: String?,
        limit: Int
    ): List<KnowledgeSearchRow> {
        // tag는 enum 토큰으로 검증된 뒤에 오지만 그래도 리터럴로 잇지 않고 이름 바인딩을 만든다.
        // 검증이 한 번 느슨해지는 것으로 인젝션이 열리는 구조를 남기지 않는다.
        val tagBindings = tags.indices.map { ":tag$it" }
        val tagClause = if (tags.isEmpty()) "" else "AND k.tag IN (${tagBindings.joinToString(", ")})"
        val sourceClause = if (source == null) "" else "AND k.source = :source"

        var spec = databaseClient.sql(
            """
            SELECT k.id           AS knowledge_id,
                   k.tag          AS tag,
                   k.source       AS source,
                   k.summary      AS summary,
                   k.description  AS description,
                   -- 검색이 내보낸 시점의 버전. 사용 로그가 이 값으로 붙어야 나중에 항목이
                   -- 고쳐져도 "그때 이 런이 읽은 것"이 보존된다(ARTEL-255). 순위와 무관하다.
                   k.version      AS version,
                   MIN(e.embedding <=> CAST(:queryVector AS vector)) AS distance
              FROM knowledge_embedding e
              JOIN knowledge k ON k.id = e.knowledge_id
             WHERE k.project_id = :projectId
               AND k.deleted_at IS NULL
               AND e.kind = :kind
               AND e.model = :model
               AND e.embedding IS NOT NULL
               $tagClause
               $sourceClause
             GROUP BY k.id, k.tag, k.source, k.summary, k.description, k.version
             -- 동점일 때 순서가 흔들리면 같은 질의가 실행마다 다른 top-k를 준다. id로 못박는다.
             ORDER BY distance ASC, k.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("queryVector", queryVector)
            .bind("projectId", projectId)
            .bind("kind", kind)
            .bind("model", model)
            .bind("limit", limit)

        tags.forEachIndexed { index, tag -> spec = spec.bind("tag$index", tag) }
        if (source != null) spec = spec.bind("source", source)

        return spec
            .map { row: Readable ->
                KnowledgeSearchRow(
                    knowledgeId = row.get("knowledge_id", java.lang.Long::class.java)!!.toLong(),
                    tag = row.get("tag", String::class.java)!!,
                    source = row.get("source", String::class.java)!!,
                    summary = row.get("summary", String::class.java)!!,
                    description = row.get("description", String::class.java)!!,
                    version = row.get("version", java.lang.Integer::class.java)!!.toInt(),
                    distance = row.get("distance", java.lang.Double::class.java)!!.toDouble()
                )
            }
            .flow()
            .toList()
    }
}

/**
 * 접힌 검색 결과 한 줄.
 *
 * @param version 이 항목의 현재 content 버전. Agent에게는 나가지 않고 사용 로그에만 쓰인다.
 * @param distance 코사인 **거리**(0에 가까울수록 가깝다). 유사도로의 변환은 서비스가 한다 —
 *   리포지토리는 DB가 준 값을 그대로 올린다.
 */
data class KnowledgeSearchRow(
    val knowledgeId: Long,
    val tag: String,
    val source: String,
    val summary: String,
    val description: String,
    val version: Int,
    val distance: Double
)
