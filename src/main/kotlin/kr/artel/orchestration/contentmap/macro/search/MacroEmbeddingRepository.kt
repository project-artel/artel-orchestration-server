package kr.artel.orchestration.contentmap.macro.search

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import io.r2dbc.spi.Readable
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitFirstOrNull
import kr.artel.orchestration.common.embedding.ClaimedRow
import kr.artel.orchestration.common.embedding.EmbeddedText
import kr.artel.orchestration.common.embedding.EmbeddingQueue
import kr.artel.orchestration.common.embedding.EmbeddingQueueRepository
import kr.artel.orchestration.common.embedding.EmbeddingTableSpec
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Repository

/** macro 가 임베딩 문장을 만드는 데 쓰는 칸. `source` 와 `definition_json` 은 읽지 않는다. */
data class MacroEmbeddingInput(
    val macroId: Long,
    val name: String,
    val parameterNames: List<String>,
    val docstring: String?,
) {
    /** 지금 이 macro 가 만드는 임베딩 문장. */
    fun text(): String = MacroEmbeddingText.compose(name, parameterNames, docstring)
}

/**
 * macro 임베딩 큐/저장 접근(ARTEL-986).
 *
 * 실제 큐 SQL 은 공용 [EmbeddingQueueRepository] 가 담당하고, 여기서는 macro 테이블 좌표를 지정해
 * 위임한다. 상속이 아니라 위임인 이유는 [kr.artel.orchestration.testcase.repository.TestCaseEmbeddingRepository]
 * 와 같다(`@Repository` 프록시가 상속된 final 메서드를 가로채지 못한다).
 *
 * **macro 가 바뀌면 벡터를 다시 만드는 자리가 [seedPending] 이다.** test_case 는 본문이 바뀔 때
 * 적재 경로가 `discardFor` 를 불러 벡터를 지우지만, macro 는 `MacroRepository.upsertByName` 한 곳이
 * 쓰고 운영 스크립트가 `docstring` 을 SQL 로 직접 고치기도 한다. 쓰는 쪽마다 무효화를 심는 대신,
 * 완성 행의 `source_text`(임베딩한 문장 그대로)를 지금 macro 가 만드는 문장과 시딩 때 비교해 다르면
 * 그 행을 지운다. 지우면 같은 [seedPending] 안의 위임 호출이 "이 (kind, model) 로 행이 없다" 고 보고
 * 대기 행을 만들고, 워커가 새 문장으로 다시 채운다. 임베딩 도중 macro 가 바뀌어 낡은 문장으로 저장된
 * 행도 다음 tick 의 같은 비교에서 걸린다.
 *
 * 비교는 완성 행 전부를 읽는다. 행당 문장 하나(수백 자)이고 macro 는 build 당 수십 개라 10초마다
 * 도는 비용으로 받아들인다.
 */
@Repository
class MacroEmbeddingRepository(
    private val databaseClient: DatabaseClient,
    private val objectMapper: ObjectMapper,
) : EmbeddingQueue {

    private val logger = LoggerFactory.getLogger(MacroEmbeddingRepository::class.java)

    private val queue = EmbeddingQueueRepository(
        databaseClient,
        EmbeddingTableSpec(
            embeddingTable = "macro_embedding",
            ownerIdColumn = "macro_id",
            ownerTable = "macro",
            aliveClause = null,
        )
    )

    override suspend fun seedPending(kind: String, model: String, limit: Int): Long {
        discardStale(kind, model)
        return queue.seedPending(kind, model, limit)
    }

    override suspend fun claimPending(kind: String, model: String, maxAttempts: Int, limit: Int): List<ClaimedRow> =
        queue.claimPending(kind, model, maxAttempts, limit)

    override suspend fun replacePendingWithVectors(
        pendingId: Long,
        ownerId: Long,
        kind: String,
        model: String,
        vectors: List<EmbeddedText>,
    ) = queue.replacePendingWithVectors(pendingId, ownerId, kind, model, vectors)

    override suspend fun recordFailure(pendingId: Long, error: String) =
        queue.recordFailure(pendingId, error)

    override suspend fun discardFor(ownerId: Long): Long =
        queue.discardFor(ownerId)

    override suspend fun deletePending(pendingId: Long) =
        queue.deletePending(pendingId)

    /** 임베딩 문장을 만들 칸만 [macroIds] 의 macro 에서 읽는다. 없어진 macro 는 결과에 없다. */
    suspend fun findInputs(macroIds: Collection<Long>): List<MacroEmbeddingInput> {
        if (macroIds.isEmpty()) return emptyList()
        return databaseClient.sql(
            "SELECT id, name, parameter_names, docstring FROM macro WHERE id IN (:ids)"
        )
            .bind("ids", macroIds)
            .map { row: Readable -> inputOf(row, idColumn = "id") }
            .flow()
            .toList()
    }

    /** 저장된 문장이 지금 문장과 다른 완성 행을 지운다. @return 지운 행 수 */
    private suspend fun discardStale(kind: String, model: String): Int {
        val completed = databaseClient.sql(
            """
            SELECT e.id AS embedding_id, e.source_text, m.id AS macro_id, m.name, m.parameter_names, m.docstring
              FROM macro_embedding e
              JOIN macro m ON m.id = e.macro_id
             WHERE e.kind = :kind AND e.model = :model AND e.embedding IS NOT NULL
            """.trimIndent()
        )
            .bind("kind", kind)
            .bind("model", model)
            .map { row: Readable ->
                row.get("embedding_id", Long::class.javaObjectType)!! to
                    (row.get("source_text", String::class.java) to inputOf(row, idColumn = "macro_id"))
            }
            .flow()
            .toList()

        val staleIds = completed.filter { (_, stored) -> stored.first != stored.second.text() }.map { it.first }
        staleIds.forEach { embeddingId ->
            databaseClient.sql("DELETE FROM macro_embedding WHERE id = :id")
                .bind("id", embeddingId)
                .fetch()
                .rowsUpdated()
                .awaitFirstOrNull()
        }
        if (staleIds.isNotEmpty()) {
            logger.info("macro 임베딩: 문장이 바뀐 벡터 {}건을 지우고 다시 만든다 (model={})", staleIds.size, model)
        }
        return staleIds.size
    }

    private fun inputOf(row: Readable, idColumn: String) = MacroEmbeddingInput(
        macroId = row.get(idColumn, Long::class.javaObjectType)!!,
        name = row.get("name", String::class.java)!!,
        parameterNames = namesOf(row.get("parameter_names", Json::class.java)),
        docstring = row.get("docstring", String::class.java),
    )

    /** `parameter_names` 는 문자열 배열이다(`ck_macro_parameter_names_array`). */
    private fun namesOf(stored: Json?): List<String> =
        stored?.let { objectMapper.readTree(it.asString()) }
            ?.mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }
            .orEmpty()
}
