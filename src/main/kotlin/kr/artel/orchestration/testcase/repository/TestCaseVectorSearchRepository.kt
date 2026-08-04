package kr.artel.orchestration.testcase.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/**
 * `test_case_embedding`에 대한 코사인 거리 검색(ARTEL-206). 시나리오 저작 챗봇이 자연어 의도로 이
 * 벡터를 검색해 케이스를 매핑한다.
 *
 * [TestCaseEmbeddingRepository](쓰기/큐)와 같은 테이블을 보지만 분리했다 — 이쪽은 읽기 경로다.
 * knowledge 검색과 같은 이유로 [DatabaseClient]를 쓴다(`vector` 코덱이 없어 문자열→`::vector` 캐스팅).
 */
@Repository
class TestCaseVectorSearchRepository(
    private val databaseClient: DatabaseClient
) {

    /**
     * [projectId] 안에서 [queryVector]에 가장 가까운 케이스를 [limit]개 찾는다.
     *
     * 케이스당 CONTENT 벡터는 1개지만, 나중에 벡터가 여럿이 되어도(예: QUERY 추가) 같은 케이스가
     * top-k를 여러 칸 차지하지 않도록 `GROUP BY t.id` + `MIN(거리)`로 접는다. `LIMIT`이 접은 뒤에
     * 걸리도록 접기를 SQL에서 한다.
     *
     * 스코프 조건:
     * - `t.project_id = :projectId` — 다른 프로젝트의 케이스가 새면 안 된다(프레임이 아닌 세션 바인딩에서 파생).
     * - `e.kind`/`e.model` — 백필이 쓴 파티션과 같아야 한다.
     * - `e.embedding IS NOT NULL` — 대기 행(백필 큐 센티널)은 벡터가 없어 제외한다.
     * - `t.category = :category` — 선택. 저작 시 특정 기능군으로 좁힐 때 쓴다(null이면 전체).
     *
     * (test_case는 소프트삭제가 없어 alive 조건이 없다.)
     */
    suspend fun searchNearest(
        projectId: Long,
        queryVector: String,
        kind: String,
        model: String,
        category: String?,
        limit: Int,
    ): List<TestCaseSearchRow> {
        val categoryClause = if (category == null) "" else "AND t.category = :category"

        var spec = databaseClient.sql(
            """
            SELECT t.id                  AS test_case_id,
                   t.category            AS category,
                   t.title               AS title,
                   t.precondition        AS precondition,
                   t.expected            AS expected,
                   t.verification_status AS verification_status,
                   MIN(e.embedding <=> CAST(:queryVector AS vector)) AS distance
              FROM test_case_embedding e
              JOIN test_case t ON t.id = e.test_case_id
             WHERE t.project_id = :projectId
               AND e.kind = :kind
               AND e.model = :model
               AND e.embedding IS NOT NULL
               $categoryClause
             GROUP BY t.id, t.category, t.title, t.precondition, t.expected, t.verification_status
             -- 동점일 때 순서가 흔들리면 같은 질의가 실행마다 다른 top-k를 준다. id로 못박는다.
             ORDER BY distance ASC, t.id DESC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("queryVector", queryVector)
            .bind("projectId", projectId)
            .bind("kind", kind)
            .bind("model", model)
            .bind("limit", limit)

        if (category != null) spec = spec.bind("category", category)

        return spec
            .map { row: Readable ->
                TestCaseSearchRow(
                    testCaseId = row.get("test_case_id", java.lang.Long::class.java)!!.toLong(),
                    category = row.get("category", String::class.java)!!,
                    title = row.get("title", String::class.java)!!,
                    precondition = row.get("precondition", String::class.java),
                    expected = row.get("expected", String::class.java)!!,
                    verificationStatus = row.get("verification_status", String::class.java)!!,
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
 * @param distance 코사인 **거리**(0에 가까울수록 가깝다). 유사도로의 변환은 서비스가 한다.
 */
data class TestCaseSearchRow(
    val testCaseId: Long,
    val category: String,
    val title: String,
    val precondition: String?,
    val expected: String,
    val verificationStatus: String,
    val distance: Double,
)
