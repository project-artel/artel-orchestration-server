package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.security.MessageDigest
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * 자연어 TC 의 계약을 채운다 — 용어집, TC 마다 필요한 것 · 남기는 것, 새 게임 시작 상태.
 *
 * **계약이 없거나 문구가 바뀐 TC 만** agent 에 보낸다. TC 를 하나 만들 때마다 AI 를 부르지 않고, 저작이
 * 시작될 때(또는 일괄 가져오기 뒤) 빠진 것만 채운다. 문구 해시로 바뀜을 안다.
 *
 * 지도에서 온 TC(`capability_key` 가 있음)와, 누가 이미 조건을 적어 둔 TC 는 건드리지 않는다 — 계약을
 * 뽑는 것은 글밖에 없는 TC 를 위한 것이고, 근거가 더 센 출처를 AI 추정으로 덮으면 안 된다.
 */
@Service
class CaseContractService(
    private val databaseClient: DatabaseClient,
    private val testCases: TestCaseRepository,
    private val agent: AgentCaseContractClient,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CaseContractService::class.java)

    // 같은 프로젝트를 두 저작이 동시에 정리하면 같은 TC 를 두 번 보낸다. 프로젝트마다 하나씩.
    private val locks = ConcurrentHashMap<Long, Mutex>()

    suspend fun refresh(projectId: Long): CaseContractRefresh = locks.computeIfAbsent(projectId) { Mutex() }.withLock {
        val cases = testCases.findByProjectIdOrderByIdAsc(projectId).toList()
        val need = cases.filter { it.capabilityKey == null && needsContract(it) }
        val glossary = glossary(projectId)
        if (need.isEmpty()) return@withLock CaseContractRefresh(0, 0, glossary.terms.size())
        val response = agent.extract(
            CaseContractRequest(
                cases = need.map {
                    ContractCaseItem(it.id!!, it.scene, it.precondition.orEmpty(), it.step, it.expectedValue)
                },
                terms = glossary.terms,
                about = "",
                projectId = projectId,
            )
        )
        val byId = need.associateBy { it.id!! }
        var written = 0
        for (contract in response.contracts) {
            val case = byId[contract.id] ?: continue
            write(case, contract)
            written++
        }
        saveGlossary(projectId, response.terms, response.startState)
        logger.info(
            "계약 정리 [projectId={}] 보낸 TC {} · 쓴 계약 {} · 용어 {}",
            projectId, need.size, written, response.terms.size()
        )
        CaseContractRefresh(need.size, written, response.terms.size())
    }

    /** 새 게임 시작 상태. 없으면 빈 map 이다. */
    suspend fun startState(projectId: Long): Map<String, String> =
        glossary(projectId).startState

    private data class Glossary(val terms: JsonNode, val startState: Map<String, String>)

    private suspend fun glossary(projectId: Long): Glossary {
        val row = databaseClient.sql("SELECT terms::text AS terms, start_state::text AS start_state FROM case_glossary WHERE project_id = :projectId")
            .bind("projectId", projectId)
            .map { r, _ -> (r.get("terms", String::class.java) ?: "[]") to (r.get("start_state", String::class.java) ?: "{}") }
            .one()
            .awaitSingleOrNull()
            ?: return Glossary(objectMapper.createArrayNode(), emptyMap())
        val start = objectMapper.readTree(row.second).fields().asSequence().associate { it.key to it.value.asText() }
        return Glossary(objectMapper.readTree(row.first), start)
    }

    private suspend fun saveGlossary(projectId: Long, terms: JsonNode, start: Map<String, String>) {
        // 이미 있는 시작 값이 이긴다. 사람이 확인해 둔 값을 다음 정리가 AI 추정으로 덮으면 안 된다.
        databaseClient.sql(
            """
            INSERT INTO case_glossary (project_id, terms, start_state, updated_at)
            VALUES (:projectId, CAST(:terms AS jsonb), CAST(:start AS jsonb), now())
            ON CONFLICT (project_id) DO UPDATE
               SET terms = EXCLUDED.terms,
                   start_state = EXCLUDED.start_state || case_glossary.start_state,
                   updated_at = now()
            """.trimIndent()
        )
            .bind("projectId", projectId)
            .bind("terms", objectMapper.writeValueAsString(terms))
            .bind("start", objectMapper.writeValueAsString(start))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun write(case: TestCaseEntity, contract: CaseContractItem) {
        val source = objectMapper.createObjectNode().apply {
            put("state_after", contract.stateAfter)
            put("assumed_before", contract.assumedBefore)
        }
        val mark = objectMapper.createObjectNode().apply {
            put("hash", hashOf(case))
            put("source", "ai")
            put("at", Instant.now().toString())
        }
        val spec = databaseClient.sql(
            """
            UPDATE test_case
               SET condition = CAST(:condition AS jsonb),
                   metadata = jsonb_set(
                       jsonb_set(coalesce(metadata, '{}'::jsonb), '{source}',
                                 coalesce(metadata->'source', '{}'::jsonb) || CAST(:source AS jsonb), true),
                       '{contract}', CAST(:mark AS jsonb), true),
                   updated_at = now()
             WHERE id = :id
            """.trimIndent()
        )
            .bind("id", case.id!!)
            .bind("source", objectMapper.writeValueAsString(source))
            .bind("mark", objectMapper.writeValueAsString(mark))
        val bound = contract.condition?.takeUnless { it.isNull }
            ?.let { spec.bind("condition", objectMapper.writeValueAsString(it)) }
            ?: spec.bindNull("condition", String::class.java)
        bound.fetch().rowsUpdated().awaitSingle()
    }

    private fun needsContract(case: TestCaseEntity): Boolean {
        val meta = runCatching { objectMapper.readTree(case.metadata.asString()) }.getOrNull() as? ObjectNode
            ?: return case.condition == null
        val mark = meta.path("contract")
        if (mark.isMissingNode) {
            // 한 번도 뽑지 않은 TC. 누가 조건이나 남김을 직접 적어 두었으면 그 계약을 쓴다.
            return case.condition == null && meta.path("source").path("state_after").isMissingNode
        }
        // 사람이 고친 계약은 문구가 바뀌어도 다시 뽑지 않는다.
        if (mark.path("source").asText() != "ai") return false
        return mark.path("hash").asText() != hashOf(case)
    }

    private fun hashOf(case: TestCaseEntity): String =
        MessageDigest.getInstance("SHA-256")
            .digest(listOf(case.scene, case.precondition.orEmpty(), case.step, case.expectedValue).joinToString("\n").toByteArray())
            .joinToString("") { "%02x".format(it) }
}
