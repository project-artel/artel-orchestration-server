package kr.artel.orchestration.testcase.contract

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
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

    // 정리는 요청과 따로 돈다. 저작을 여는 요청이 끊겨도(브라우저 · 프록시 타임아웃) 정리는 끝까지 간다 —
    // 요청 안에서 돌렸더니 TC 73건을 정리하던 중 요청이 끊기며 함께 취소되고, 저작이 계약 없이 글만으로 짰다.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private val running = ConcurrentHashMap<Long, Deferred<CaseContractRefresh>>()

    /** 돌고 있는 정리가 있으면 그것을, 없으면 새로 시작한 것을 돌려준다. 기다리는 쪽이 취소돼도 정리는 계속된다. */
    fun refreshInBackground(projectId: Long): Deferred<CaseContractRefresh> {
        running[projectId]?.takeIf { it.isActive }?.let { return it }
        val started = scope.async(start = CoroutineStart.LAZY) { refresh(projectId) }
        val current = running.merge(projectId, started) { old, new -> if (old.isActive) old else new }!!
        if (current === started) {
            started.invokeOnCompletion { running.remove(projectId, started) }
            started.start()
        } else {
            started.cancel()
        }
        return current
    }

    /**
     * TC 를 만들거나 고친 뒤 부른다. 저작을 열 때 기다리지 않게 미리 정리해 둔다. 몇 초 모아서 한 번 돌고, 돌고 있는
     * 정리가 있으면 끝난 뒤 다시 돈다 — 그 정리는 새 TC 를 읽기 전에 시작했을 수 있다.
     */
    fun refreshSoon(projectId: Long) {
        scope.launch {
            delay(REFRESH_DEBOUNCE_MS)
            runCatching {
                running[projectId]?.join()
                refreshInBackground(projectId).await()
            }.onFailure { logger.warn("계약 미리 정리 실패 [projectId=$projectId]: ${it.message}") }
        }
    }

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
                knownLeaves = (cases - need.toSet()).flatMap { leavesOf(it).keys }.distinct(),
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
                if (from.isBlank() || to.isBlank()) null
                else QaObservationService.Exit(from, to, e.path("by").asText(""), e.path("build_id").takeIf { it.isNumber }?.asLong())
            }
            .orEmpty()

    /** 프로젝트에서 가장 최근에 등록된 build. 저작에 버전 맥락이 없을 때의 "지금 버전" 이다. */
    suspend fun latestBuildId(projectId: Long): Long? =
        databaseClient.sql("SELECT id FROM game_build WHERE project_id = :p ORDER BY created_at DESC, id DESC LIMIT 1")
            .bind("p", projectId).map { r, _ -> r.get("id", java.lang.Long::class.java)?.toLong() }.one().awaitSingleOrNull()

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
            put("v", CONTRACT_VERSION)
            put("at", Instant.now().toString())
        }
        // 도착 화면은 저작이 이미 읽는 자리(`arrives_at`)에 둔다. 다시 뽑아 화면이 안 바뀌면 지운다.
        val arrival = objectMapper.createObjectNode().apply {
            contract.arrivesAt.trim().ifBlank { null }?.let { put(MapTestCaseWriter.ARRIVES_AT, it) }
        }
        val spec = databaseClient.sql(
            """
            UPDATE test_case
               SET condition = CAST(:condition AS jsonb),
                   metadata = (jsonb_set(
                       jsonb_set(coalesce(metadata, '{}'::jsonb), '{source}',
                                 coalesce(metadata->'source', '{}'::jsonb) || CAST(:source AS jsonb), true),
                       '{contract}', CAST(:mark AS jsonb), true) - 'arrives_at') || CAST(:arrival AS jsonb),
                   updated_at = now()
             WHERE id = :id
            """.trimIndent()
        )
            .bind("id", case.id!!)
            .bind("source", objectMapper.writeValueAsString(source))
            .bind("mark", objectMapper.writeValueAsString(mark))
            .bind("arrival", objectMapper.writeValueAsString(arrival))
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
        // 뽑는 칸이 늘면 판본을 올린다. 앞 판본으로 뽑은 계약은 문구가 그대로여도 한 번 다시 뽑는다.
        if (mark.path("v").asInt(1) < CONTRACT_VERSION) return true
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
    suspend fun applyAnswer(projectId: Long, ref: ContractAnswerRef, option: String, buildId: Long? = null) {
        // 시작 값 질문은 어느 케이스의 것도 아니다 — 프로젝트의 시작 상태에 남긴다.
        if (ref.operator == START_OPERATOR) {
            if (option.startsWith(START_VALUE)) setStart(projectId, ref.variable, option.removePrefix(START_VALUE), buildId)
            return
        }
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
                saveCase(case.id!!, next, source.put("assumed_before", withoutAssumed), meta, buildId = buildId)
                option.removePrefix(YES_BY).toLongOrNull()?.takeIf { option.startsWith(YES_BY) }?.let { makerId ->
                    addLeaves(projectId, makerId, "${ref.variable}=${ref.value}", buildId = buildId)
                }
            }
            option == START -> setStart(projectId, ref.variable, ref.value, buildId)
            option == NO -> {
                val next = condition?.takeUnless { it.isNull }?.let { drop(it, ref.variable, ref.value) }
                saveCase(case.id!!, next, source.put("assumed_before", withoutAssumed), meta, buildId = buildId)
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
    suspend fun applyStatement(projectId: Long, said: String, question: String, buildId: Long? = null): Int {
        val glossary = glossary(projectId)
        if (glossary.terms.size() == 0) return 0
        val cases = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter { it.capabilityKey == null }
        val lines = cases.map(::contractLine)
        val patches = agent.statement(StatementRequest(said, question, glossary.terms, glossary.startState, lines)).patches
        var applied = 0
        for (patch in patches) {
            val ref = ContractAnswerRef(patch.tcId ?: 0, patch.name, "==", patch.value)
            when (patch.op) {
                "needs" -> if (patch.tcId != null) { applyAnswer(projectId, ref, YES, buildId); applied++ }
                "not_needs" -> if (patch.tcId != null) { dropAny(projectId, patch.tcId, patch.name, buildId); applied++ }
                "leaves" -> if (patch.tcId != null) { addLeaves(projectId, patch.tcId, "${patch.name}=${patch.value}", buildId = buildId); applied++ }
                "start" -> { setStart(projectId, patch.name, patch.value, buildId); applied++ }
            }
        }
        if (applied > 0) logger.info("사용자 말을 계약에 옮김 [projectId={}] {}건: {}", projectId, applied, patches)
        return applied
    }

    /**
     * QA 가 통과시킨 TC 를 실행하는 동안 바뀐 값([changed], TC id → 코드 이름 → `앞→뒤`)을 용어집 이름으로 옮겨, 계약이
     * 빠뜨린 '남기는 것' 만 더한다. 요구는 더하지 않는다 — 실행이 무엇을 요구했는지는 값의 변화로 알 수 없다.
     * 출처는 `observed` 다. 용어집이 없으면(지도 프로젝트) 아무것도 하지 않는다.
     */
    suspend fun applyObserved(projectId: Long, changed: Map<Long, Map<String, String>>, buildId: Long? = null): Int {
        val glossary = glossary(projectId)
        if (glossary.terms.size() == 0 || changed.isEmpty()) return 0
        val cases = testCases.findAllById(changed.keys).toList().filter { it.projectId == projectId && it.capabilityKey == null }
        if (cases.isEmpty()) return 0
        val observed = cases.map { case -> "${case.id}: " + changed[case.id].orEmpty().entries.joinToString(", ") { (k, v) -> "$k $v" } }
        val patches = agent.observed(ObservedContractRequest(glossary.terms, glossary.startState, cases.map(::contractLine), observed)).patches
        var applied = 0
        for (patch in patches.filter { it.op == "leaves" && it.tcId in changed.keys }) {
            addLeaves(projectId, patch.tcId!!, "${patch.name}=${patch.value}", OBSERVED, buildId)
            applied++
        }
        if (applied > 0) logger.info("QA 관측을 계약에 옮김 [projectId={}] {}건: {}", projectId, applied, patches)
        return applied
    }

    /** 계약까지 붙인 TC 목록과 용어집. 지도에서 온 TC 는 빼고 글로 쓴 TC 만이다. */
    suspend fun list(projectId: Long): CaseContractList {
        val glossary = glossary(projectId)
        val rows = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter { it.capabilityKey == null }.map { case ->
            val meta = runCatching { objectMapper.readTree(case.metadata.asString()) }.getOrNull()
            CaseContractRow(
                testCaseId = case.id.toString(),
                scene = case.scene,
                precondition = case.precondition,
                step = case.step,
                expectedValue = case.expectedValue,
                needs = case.condition?.asString()?.let { tests(objectMapper.readTree(it)) }.orEmpty(),
                leaves = meta?.path("source")?.path("state_after")?.asText("").orEmpty()
                    .split(";").map { it.trim() }.filter { it.isNotBlank() },
                contractSource = meta?.path("contract")?.path("source")?.asText(null),
                arrivesAt = meta?.path(MapTestCaseWriter.ARRIVES_AT)?.asText(null)?.ifBlank { null },
            )
        }
        return CaseContractList(glossary.terms, glossary.startState, rows)
    }

    private fun tests(node: JsonNode): List<String> = when (node.path("kind").asText()) {
        "test" -> listOf("${node.path("left").asText()} ${node.path("operator").asText()} ${node.path("right").asText()}")
        "every" -> node.path("parts").flatMap { tests(it) }
        "either" -> listOf(node.path("parts").joinToString(" | ") { "(" + tests(it).joinToString(", ") + ")" })
        else -> emptyList()
    }

    /**
     * 사람(또는 사용자의 AI)이 적은 계약을 그대로 쓴다. 출처는 `user` 라 AI 추출이 덮지 않는다. [needs] 는 `(이름, 비교, 값)` 이다.
     * 둘 다 비었으면 아무것도 하지 않는다 — 그 TC 는 AI 가 글에서 뽑는다.
     */
    suspend fun writeUserContract(
        projectId: Long,
        caseId: Long,
        needs: List<Triple<String, String, String>>,
        leaves: List<String>,
        arrivesAt: String? = null,
    ) {
        if (needs.isEmpty() && leaves.isEmpty() && arrivesAt.isNullOrBlank()) return
        val case = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        arrivesAt?.trim()?.ifBlank { null }?.let { meta.put(MapTestCaseWriter.ARRIVES_AT, it) }
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        source.put("state_after", leaves.joinToString("; "))
        val parts = needs.map { (name, op, value) -> testNode(name, op, value) }
        val condition: JsonNode? = when (parts.size) {
            0 -> null
            1 -> parts.first()
            else -> objectMapper.createObjectNode().apply { put("kind", "every"); putArray("parts").addAll(parts) }
        }
        saveCase(caseId, condition, source, meta, USER)
    }

    /** 계약에 쓰는 이름 꼴. Agent 가 뽑은 계약과 같은 규칙이다(공백 → `_`). */
    fun contractName(text: String): String = text.trim().replace(' ', '_')

    private fun testNode(name: String, op: String, value: String): ObjectNode = objectMapper.createObjectNode().apply {
        put("kind", "test"); put("left", name); put("operator", op); put("right", value)
        put("context", "static"); put("offset", 0); putNull("subjectLost")
    }

    /** 용어집에 없는 이름만 더한다. 시작 값이 있으면 시작 상태에도 넣는다. 더한 이름을 돌려준다. */
    suspend fun mergeTerms(projectId: Long, terms: List<CaseTermInput>): List<String> {
        if (terms.isEmpty()) return emptyList()
        val glossary = glossary(projectId)
        val known = glossary.terms.map { it.path("name").asText() }.toSet()
        val fresh = terms.filter { it.name.isNotBlank() && it.name !in known }.distinctBy { it.name }
        if (fresh.isEmpty()) return emptyList()
        val all = (glossary.terms.deepCopy() as com.fasterxml.jackson.databind.node.ArrayNode)
        fresh.forEach { t ->
            all.addObject().apply {
                put("name", t.name); put("kind", t.kind); put("meaning", t.meaning)
                putArray("aliases"); putArray("options").apply { t.options.forEach { add(it) } }
                put("start", t.start?.ifBlank { null } ?: "unknown")
                put("start_basis", if (t.start.isNullOrBlank()) "unknown" else "text")
                putArray("not_same_as")
            }
        }
        val start = glossary.startState + fresh.mapNotNull { t -> t.start?.ifBlank { null }?.let { contractName(t.name) to contractName(it) } }
        saveGlossary(projectId, all, start)
        return fresh.map { it.name }
    }

    /** 케이스가 남기는 것(`state_after`), 이름 → 값. */
    private fun leavesOf(case: TestCaseEntity): Map<String, String> =
        runCatching { objectMapper.readTree(case.metadata.asString()).path("source").path("state_after").asText("") }.getOrDefault("")
            .split(";").mapNotNull { pair -> pair.split("=", limit = 2).takeIf { it.size == 2 }?.let { it[0].trim() to it[1].trim() } }
            .filter { it.first.isNotBlank() }.toMap()

    /** 계약을 사람이 읽는 한 줄로: `id | 행동 → 기대 || 필요 | 남김`. */
    private fun contractLine(case: kr.artel.orchestration.testcase.entity.TestCaseEntity): String {
        val meta = runCatching { objectMapper.readTree(case.metadata.asString()) }.getOrNull()
        val needs = case.condition?.asString().orEmpty().let { Regex("\"left\":\\s*\"([^\"]+)\"[^}]*?\"right\":\\s*\"([^\"]*)\"").findAll(it).joinToString(", ") { m -> "${m.groupValues[1]}=${m.groupValues[2]}" } }
        return "${case.id} | ${case.step} → ${case.expectedValue} || ${needs.ifBlank { "-" }} | ${meta?.path("source")?.path("state_after")?.asText("")?.ifBlank { "-" } ?: "-"}"
    }

    /** 값에 상관없이 그 이름의 요구를 뺀다 — "검은 필요 없어"는 어떤 값인지 말하지 않는다. */
    private suspend fun dropAny(projectId: Long, caseId: Long, variable: String, buildId: Long? = null) {
        val case = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return
        val condition = case.condition?.let { objectMapper.readTree(it.asString()) }
        val values = condition?.let { c -> Regex("\"left\":\\s*\"${Regex.escape(variable)}\"[^}]*?\"right\":\\s*\"([^\"]*)\"").findAll(c.toString()).map { it.groupValues[1] }.toList() }.orEmpty()
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        val withoutAssumed = source.path("assumed_before").asText("").split(";")
            .map { it.trim() }.filter { it.isNotBlank() && !it.startsWith(variable) }.joinToString("; ")
        var next = condition?.takeUnless { it.isNull }
        values.forEach { v -> next = next?.let { drop(it, variable, v) } }
        saveCase(caseId, next, source.put("assumed_before", withoutAssumed), meta, buildId = buildId)
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

    private suspend fun saveCase(
        caseId: Long,
        condition: JsonNode?,
        source: ObjectNode,
        meta: ObjectNode,
        by: String = USER,
        buildId: Long? = null,
    ) {
        meta.set<JsonNode>("source", source)
        (meta.path("contract") as? ObjectNode ?: objectMapper.createObjectNode().also { meta.set<JsonNode>("contract", it) })
            .put("source", by).put("at", Instant.now().toString())
            // 어느 build 에서 알게 된 사실인가. 새 버전의 저작은 이것으로 앞 버전 사실을 가려 표시한다.
            .apply { if (buildId != null) put("build_id", buildId) else remove("build_id") }
        val spec = databaseClient.sql("UPDATE test_case SET condition = CAST(:condition AS jsonb), metadata = CAST(:meta AS jsonb), updated_at = now() WHERE id = :id")
            .bind("id", caseId).bind("meta", objectMapper.writeValueAsString(meta))
        (condition?.let { spec.bind("condition", objectMapper.writeValueAsString(it)) } ?: spec.bindNull("condition", String::class.java))
            .fetch().rowsUpdated().awaitSingle()
    }

    private suspend fun addLeaves(projectId: Long, caseId: Long, pair: String, by: String = USER, buildId: Long? = null) {
        val case = testCases.findById(caseId)?.takeIf { it.projectId == projectId } ?: return
        val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
        val source = (meta.path("source") as? ObjectNode) ?: objectMapper.createObjectNode()
        val have = source.path("state_after").asText("").split(";").map { it.trim() }.filter { it.isNotBlank() }
        if (pair in have) return
        // **새로 알게 된 것이 이긴다.** 같은 이름의 앞 값은 지운다 — 앞 버전에서 '등불=0' 이었던 것을 새 버전 QA 가
        // '등불=1' 로 봤으면 둘 다 남기지 않는다.
        val name = pair.substringBefore('=').trim()
        val next = have.filterNot { it.substringBefore('=').trim() == name } + pair
        source.put("state_after", next.joinToString("; "))
        saveCase(caseId, case.condition?.let { objectMapper.readTree(it.asString()) }, source, meta, by, buildId)
    }

    private suspend fun setStart(projectId: Long, variable: String, value: String, buildId: Long? = null) {
        val seen = buildId?.let { objectMapper.writeValueAsString(mapOf(variable to it)) } ?: "{}"
        databaseClient.sql(
            """
            INSERT INTO case_glossary (project_id, start_state, start_seen)
            VALUES (:projectId, jsonb_build_object(CAST(:name AS text), CAST(:value AS text)), CAST(:seen AS jsonb))
            ON CONFLICT (project_id) DO UPDATE
               SET start_state = case_glossary.start_state || EXCLUDED.start_state,
                   start_seen = (case_glossary.start_seen - CAST(:name AS text)) || EXCLUDED.start_seen,
                   updated_at = now()
            """.trimIndent()
        ).bind("projectId", projectId).bind("name", variable).bind("value", value).bind("seen", seen)
            .fetch().rowsUpdated().awaitSingle()
    }

    /** 시작 값마다 알게 된 build. 사람 답 · QA 관측으로 정해진 것만 있다. */
    suspend fun startSeen(projectId: Long): Map<String, Long> =
        databaseClient.sql("SELECT start_seen::text AS s FROM case_glossary WHERE project_id = :p")
            .bind("p", projectId).map { r, _ -> r.get("s", String::class.java) ?: "{}" }.one().awaitSingleOrNull()
            ?.let { objectMapper.readTree(it).fields().asSequence().mapNotNull { (k, v) -> v.takeIf { it.isNumber }?.let { k to it.asLong() } }.toMap() }
            .orEmpty()

    data class ContractAnswerRef(val caseId: Long, val variable: String, val operator: String, val value: String)

    companion object {
        const val YES = "yes"
        const val YES_BY = "yes-by:"
        const val START = "start"
        const val NO = "no"
        /** 시작 값 질문(`contract:0|이름|start|?`)과 그 보기(`start=값`). [ScenarioQuestionBuilder] 와 같은 꼴이다. */
        const val START_OPERATOR = "start"
        const val START_VALUE = "start="

        /** 계약을 고친 출처. 사람 말이면 user, QA 가 본 것이면 observed 다. */
        const val USER = "user"
        const val OBSERVED = "observed"

        /** 뽑는 칸의 판본. 2: 다른 화면으로 가는 TC 의 도착 화면(`arrives_at`). */
        const val CONTRACT_VERSION = 2

        /** TC 를 연달아 넣을 때 한 번에 모아 정리하려고 기다리는 시간. */
        private const val REFRESH_DEBOUNCE_MS = 3_000L
    }
}
