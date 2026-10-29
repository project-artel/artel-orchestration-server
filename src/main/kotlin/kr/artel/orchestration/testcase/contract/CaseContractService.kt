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

    /** QA 가 지나가며 본 씬 출구. 씬 이름은 게임이 부르는 그대로다. */
    suspend fun observedExits(projectId: Long): List<QaObservationService.Exit> =
        databaseClient.sql("SELECT exits::text AS exits FROM case_glossary WHERE project_id = :projectId")
            .bind("projectId", projectId)
            .map { r, _ -> r.get("exits", String::class.java) ?: "[]" }
            .one()
            .awaitSingleOrNull()
            ?.let { objectMapper.readTree(it) }
            ?.mapNotNull { e ->
                val from = e.path("from").asText(""); val to = e.path("to").asText("")
                if (from.isBlank() || to.isBlank()) null else QaObservationService.Exit(from, to, e.path("by").asText(""))
            }
            .orEmpty()

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

    /**
     * 계약 질문에 대한 답을 계약에 옮긴다. 모델을 거치지 않는다 — 어느 케이스의 어느 요구인지는 질문 id 에,
     * 무엇으로 바꿀지는 고른 보기에 다 있다. 고친 케이스는 출처를 `user` 로 남겨 다음 정리가 덮지 않는다.
     */
    suspend fun applyAnswer(projectId: Long, ref: ContractAnswerRef, option: String) {
        val case = testCases.findById(ref.caseId)?.takeIf { it.projectId == projectId } ?: return
        val test = objectMapper.createObjectNode().apply {
            put("kind", "test"); put("left", ref.variable); put("operator", ref.operator); put("right", ref.value)
            put("context", "static"); put("offset", 0); putNull("subjectLost")
        }
        val condition = case.condition?.let { objectMapper.readTree(it.asString()) }
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        val withoutAssumed = source.path("assumed_before").asText("").split(";")
            .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(ref.variable) }.joinToString("; ")
        when {
            option == YES || option.startsWith(YES_BY) -> {
                val next = when {
                    condition == null || condition.isNull -> test
                    condition.path("kind").asText() == "every" -> (condition.deepCopy<ObjectNode>()).also { (it.path("parts") as com.fasterxml.jackson.databind.node.ArrayNode).add(test) }
                    else -> objectMapper.createObjectNode().apply { put("kind", "every"); putArray("parts").add(condition).add(test) }
                }
                saveCase(case.id!!, next, source.put("assumed_before", withoutAssumed), meta)
                option.removePrefix(YES_BY).toLongOrNull()?.takeIf { option.startsWith(YES_BY) }?.let { makerId ->
                    addLeaves(projectId, makerId, "${ref.variable}=${ref.value}")
                }
            }
            option == START -> setStart(projectId, ref.variable, ref.value)
            option == NO -> {
                val next = condition?.takeUnless { it.isNull }?.let { drop(it, ref.variable, ref.value) }
                saveCase(case.id!!, next, source.put("assumed_before", withoutAssumed), meta)
            }
        }
        logger.info("계약 질문에 답함 [projectId={}] TC {} {} {} {} → {}", projectId, ref.caseId, ref.variable, ref.operator, ref.value, option)
    }

    /**
     * 사용자가 대화에서 한 말 중 게임 사실을 계약에 옮긴다. 옮긴 수를 돌려준다.
     *
     * 용어집이 없는 프로젝트(계약을 쓰지 않는 프로젝트)에서는 부르지 않는다 — 지도 프로젝트의 말 한마디마다
     * 모델을 부를 이유가 없다.
     */
    suspend fun applyStatement(projectId: Long, said: String, question: String): Int {
        val glossary = glossary(projectId)
        if (glossary.terms.size() == 0) return 0
        val cases = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter { it.capabilityKey == null }
        val lines = cases.map { case ->
            val meta = runCatching { objectMapper.readTree(case.metadata.asString()) }.getOrNull()
            val needs = case.condition?.asString().orEmpty().let { Regex("\"left\":\\s*\"([^\"]+)\"[^}]*?\"right\":\\s*\"([^\"]*)\"").findAll(it).joinToString(", ") { m -> "${m.groupValues[1]}=${m.groupValues[2]}" } }
            "${case.id} | ${case.step} → ${case.expectedValue} || ${needs.ifBlank { "-" }} | ${meta?.path("source")?.path("state_after")?.asText("")?.ifBlank { "-" } ?: "-"}"
        }
        val patches = agent.statement(StatementRequest(said, question, glossary.terms, glossary.startState, lines)).patches
        var applied = 0
        for (patch in patches) {
            val ref = ContractAnswerRef(patch.tcId ?: 0, patch.name, "==", patch.value)
            when (patch.op) {
                "needs" -> if (patch.tcId != null) { applyAnswer(projectId, ref, YES); applied++ }
                "not_needs" -> if (patch.tcId != null) { dropAny(projectId, patch.tcId, patch.name); applied++ }
                "leaves" -> if (patch.tcId != null) { addLeaves(projectId, patch.tcId, "${patch.name}=${patch.value}"); applied++ }
                "start" -> { setStart(projectId, patch.name, patch.value); applied++ }
            }
        }
        if (applied > 0) logger.info("사용자 말을 계약에 옮김 [projectId={}] {}건: {}", projectId, applied, patches)
        return applied
    }

    /** 값에 상관없이 그 이름의 요구를 뺀다 — "검은 필요 없어"는 어떤 값인지 말하지 않는다. */
    private suspend fun dropAny(projectId: Long, caseId: Long, variable: String) {
        val case = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return
        val condition = case.condition?.let { objectMapper.readTree(it.asString()) }
        val values = condition?.let { c -> Regex("\"left\":\\s*\"${Regex.escape(variable)}\"[^}]*?\"right\":\\s*\"([^\"]*)\"").findAll(c.toString()).map { it.groupValues[1] }.toList() }.orEmpty()
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        val withoutAssumed = source.path("assumed_before").asText("").split(";")
            .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(variable) }.joinToString("; ")
        var next = condition?.takeUnless { it.isNull }
        values.forEach { v -> next = next?.let { drop(it, variable, v) } }
        saveCase(caseId, next, source.put("assumed_before", withoutAssumed), meta)
    }

    private fun drop(node: JsonNode, variable: String, value: String): JsonNode? = when (node.path("kind").asText()) {
        "test" -> node.takeUnless { it.path("left").asText() == variable && it.path("right").asText() == value }
        "every", "either" -> {
            val parts = node.path("parts").mapNotNull { drop(it, variable, value) }
            when {
                parts.isEmpty() -> null
                parts.size == 1 && node.path("kind").asText() == "every" -> parts.first()
                else -> (node.deepCopy<ObjectNode>()).also { it.putArray("parts").addAll(parts) }
            }
        }
        else -> node
    }

    private suspend fun saveCase(caseId: Long, condition: JsonNode?, source: ObjectNode, meta: ObjectNode) {
        meta.set<JsonNode>("source", source)
        (meta.path("contract") as? ObjectNode ?: objectMapper.createObjectNode().also { meta.set<JsonNode>("contract", it) })
            .put("source", "user").put("at", Instant.now().toString())
        val spec = databaseClient.sql("UPDATE test_case SET condition = CAST(:condition AS jsonb), metadata = CAST(:meta AS jsonb), updated_at = now() WHERE id = :id")
            .bind("id", caseId).bind("meta", objectMapper.writeValueAsString(meta))
        (condition?.let { spec.bind("condition", objectMapper.writeValueAsString(it)) } ?: spec.bindNull("condition", String::class.java))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun addLeaves(projectId: Long, caseId: Long, pair: String) {
        val case = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        val have = source.path("state_after").asText("")
        if (have.split(";").any { it.trim() == pair }) return
        source.put("state_after", listOf(have, pair).filter { it.isNotBlank() }.joinToString("; "))
        saveCase(caseId, case.condition?.let { objectMapper.readTree(it.asString()) }, source, meta)
    }

    private suspend fun setStart(projectId: Long, variable: String, value: String) {
        databaseClient.sql(
            """
            INSERT INTO case_glossary (project_id, start_state) VALUES (:projectId, jsonb_build_object(CAST(:name AS text), CAST(:value AS text)))
            ON CONFLICT (project_id) DO UPDATE SET start_state = case_glossary.start_state || EXCLUDED.start_state, updated_at = now()
            """.trimIndent()
        ).bind("projectId", projectId).bind("name", variable).bind("value", value)
            .fetch().rowsUpdated().awaitSingle()
    }

    data class ContractAnswerRef(val caseId: Long, val variable: String, val operator: String, val value: String)

    companion object {
        const val YES = "yes"
        const val YES_BY = "yes-by:"
        const val START = "start"
        const val NO = "no"
    }
}
