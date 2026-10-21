package kr.artel.orchestration.contentmap.macro.search

import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.macro.MacroWriteFrames
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/** 검색 후보 macro 한 줄. `source` 와 `definition_json` 전체는 읽지 않는다. */
data class MacroSearchRow(
    val id: Long,
    val name: String,
    val docstring: String?,
    /** `definition_json -> entry -> parameters`. scene context 가 읽는 것과 같은 조각이다. */
    val declaredParameters: Json?,
)

/**
 * `MACRO_SEARCH` 의 두 후보 목록(ARTEL-986). 둘 다 content map 하나로 좁히고 최대 [limit] 개를 순서대로
 * 돌려준다. 합치는 일은 [MacroSearchService] 가 한다.
 *
 * [DatabaseClient] 를 쓰는 이유는 knowledge, test_case 검색과 같다 — `vector` 코덱이 없어 문자열을
 * `CAST(... AS vector)` 로 보내고, 키워드 쪽은 단어 수에 따라 SQL 이 달라진다.
 */
@Repository
class MacroSearchRepository(
    private val databaseClient: DatabaseClient,
) {

    /**
     * 단어를 하나라도 담은 macro 를 단어 일치 개수 내림차순, 이름 오름차순으로 읽는다.
     *
     * 단어 하나는 `name`, `replace(name, '_', ' ')`, `docstring` 중 하나에 대소문자 무시로 들어 있으면
     * 일치다. `replace` 쪽이 있어야 `cast pair` 가 `cast_pair_at_enemy` 를 찾는다. 같은 단어가 세 칸에
     * 모두 들어 있어도 1 로 센다.
     *
     * [terms] 는 이미 [escapeLike] 를 거친 `%...%` 패턴이 아니라 **원문 단어**다. 패턴은 여기서 만든다.
     * 단어 수는 [MacroSearchFrames.MAX_TERMS] 이하라 SQL 길이가 상한을 가진다.
     */
    suspend fun searchByKeywords(contentMapId: Long, terms: List<String>, limit: Int): List<MacroSearchRow> {
        if (terms.isEmpty()) return emptyList()
        val matches = terms.indices.joinToString(" + ") { index ->
            """(CASE WHEN m.name ILIKE :t$index OR replace(m.name, '_', ' ') ILIKE :t$index
                          OR m.docstring ILIKE :t$index THEN 1 ELSE 0 END)"""
        }
        var spec = databaseClient.sql(
            """
            SELECT id, name, docstring, declared_parameters
              FROM (
                SELECT m.id, m.name, m.docstring,
                       m.definition_json -> '${MacroWriteFrames.ENTRY_FIELD}' -> '${MacroWriteFrames.PARAMETERS_FIELD}'
                           AS declared_parameters,
                       $matches AS matched_terms
                  FROM macro m
                 WHERE m.content_map_id = :contentMapId
              ) ranked
             WHERE matched_terms > 0
             ORDER BY matched_terms DESC, name ASC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("contentMapId", contentMapId)
            .bind("limit", limit)
        terms.forEachIndexed { index, term -> spec = spec.bind("t$index", "%${escapeLike(term)}%") }
        return spec.map(::rowOf).flow().toList()
    }

    /**
     * 질의 벡터에 코사인 거리가 가까운 macro 를 가까운 순으로 읽는다. 완성 행(`embedding IS NOT NULL`)과
     * 같은 [model] 만 본다. 대기 행은 벡터가 없어 빠진다. 거리가 같으면 이름 순이다.
     *
     * 거리 상한은 두지 않는다. macro 는 한 build 에 수십 개라 가장 가까운 것이 먼 것이어도 순위로는
     * 의미가 있고, 자르는 기준을 정할 실측이 아직 없다.
     */
    suspend fun searchByVector(contentMapId: Long, queryVector: String, model: String, limit: Int): List<MacroSearchRow> =
        databaseClient.sql(
            """
            SELECT m.id, m.name, m.docstring,
                   m.definition_json -> '${MacroWriteFrames.ENTRY_FIELD}' -> '${MacroWriteFrames.PARAMETERS_FIELD}'
                       AS declared_parameters
              FROM macro_embedding e
              JOIN macro m ON m.id = e.macro_id
             WHERE m.content_map_id = :contentMapId
               AND e.model = :model
               AND e.embedding IS NOT NULL
             ORDER BY e.embedding <=> CAST(:queryVector AS vector) ASC, m.name ASC
             LIMIT :limit
            """.trimIndent()
        )
            .bind("contentMapId", contentMapId)
            .bind("model", model)
            .bind("queryVector", queryVector)
            .bind("limit", limit)
            .map(::rowOf)
            .flow()
            .toList()

    private fun rowOf(row: Readable) = MacroSearchRow(
        id = row.get("id", Long::class.javaObjectType)!!,
        name = row.get("name", String::class.java)!!,
        docstring = row.get("docstring", String::class.java),
        declaredParameters = row.get("declared_parameters", Json::class.java),
    )

    companion object {
        /** `ILIKE` 의 기본 escape 문자는 `\` 다. `\`, `%`, `_` 를 글자 그대로 찾도록 앞에 `\` 를 붙인다. */
        fun escapeLike(term: String): String =
            term.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_")
    }
}
