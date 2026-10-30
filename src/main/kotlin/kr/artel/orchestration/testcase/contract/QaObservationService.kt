package kr.artel.orchestration.testcase.contract

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import java.time.Instant

/**
 * QA 가 끝난 뒤 로그에서 **확실한 것만** 쌓는다 — TC 를 실행할 때 서 있던 씬, 실행 뒤 도착한 씬, 먹힌 입력,
 * 그리고 씬 사이의 출구. 모델을 부르지 않는다. QA 실행 중에는 아무것도 하지 않는다.
 *
 * 씬은 게임이 부르는 이름(`SquareScene`) 그대로 둔다. TC 가 부르는 이름(광장)으로 바꾸는 것은 읽는 쪽
 * ([kr.artel.orchestration.testcase.service.TestCaseService]) 이 한다 — 원본이 남아야 짝을 다시 지을 수 있다.
 * 값의 변화(진행도 같은 것)는 아직 쌓지 않는다. 코드 이름과 용어집 이름을 잇는 단계가 따로 필요하다.
 * 계약을 쓰는 프로젝트(용어집이 있는 프로젝트)에서만 쌓는다.
 */
@Service
class QaObservationService(
    private val databaseClient: DatabaseClient,
    private val testCases: TestCaseRepository,
    private val objectMapper: ObjectMapper,
    private val caseContracts: CaseContractService,
) {
    private val logger = LoggerFactory.getLogger(QaObservationService::class.java)
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    /** 런 종료 처리를 붙잡지 않는다. 쌓기가 실패해도 판정과 알림은 그대로다. */
    fun accumulateLater(qaTryId: Long) {
        scope.launch {
            runCatching { accumulate(qaTryId) }
                .onFailure { logger.warn("QA 관측 쌓기 실패 [qaTryId=$qaTryId]: ${it.message}") }
        }
    }

    /** [changed] 는 그 TC 를 실행하는 동안 바뀐 게임 값(코드 이름 → `앞→뒤`)이다. 용어집 이름과 잇기 전의 원본이다. */
    data class Observed(
        val caseId: Long,
        val scene: String?,
        val arrivesAt: String?,
        val input: String?,
        val changed: Map<String, String> = emptyMap(),
    )
    /** [buildId] 는 그 길을 본 build 다. 다음 버전의 저작이 앞 버전에서만 본 길을 가려 읽는다. */
    data class Exit(
        val from: String,
        val to: String,
        val by: String,
        @param:com.fasterxml.jackson.annotation.JsonProperty("build_id") @get:com.fasterxml.jackson.annotation.JsonProperty("build_id")
        val buildId: Long? = null,
    )

    suspend fun accumulate(qaTryId: Long): Int {
        val projectId = databaseClient.sql(
            "SELECT ts.project_id FROM qa_try t JOIN test_scenario ts ON ts.id = t.test_scenario_id WHERE t.id = :id"
        ).bind("id", qaTryId).map { r, _ -> r.get("project_id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingleOrNull() ?: return 0
        val hasGlossary = databaseClient.sql("SELECT 1 FROM case_glossary WHERE project_id = :p")
            .bind("p", projectId).map { _, _ -> 1 }.one().awaitSingleOrNull() != null
        if (!hasGlossary) return 0
        // 이 try 가 돈 build. 런이 고정한 것이 먼저고, 없으면 인스턴스가 마지막으로 보고한 것이다.
        val buildId = databaseClient.sql(
            """
            SELECT coalesce(r.game_build_id, i.last_game_build_id) AS build_id
              FROM qa_try t LEFT JOIN qa_run r ON r.id = t.qa_run_id LEFT JOIN game_instance i ON i.id = t.game_instance_id
             WHERE t.id = :id
            """.trimIndent()
        ).bind("id", qaTryId)
            // build 를 모르는 try 면 null 이다. 리액터는 null 을 못 싣는다 — Optional 로 감싼다.
            .map { r, _ -> java.util.Optional.ofNullable(r.get("build_id", java.lang.Long::class.java)?.toLong()) }
            .one().awaitSingleOrNull()?.orElse(null)
        val rows = databaseClient.sql(
            "SELECT type, payload::text AS payload FROM qa_log WHERE qa_try_id = :id AND type IN ('PULSE','ACTION','TOOL_RESULT','STATUS') ORDER BY id"
        ).bind("id", qaTryId).map { r, _ -> r.get("type", String::class.java)!! to (r.get("payload", String::class.java) ?: "{}") }.all().collectList().awaitSingle()
        val (observed, exits) = walk(rows.map { (type, payload) -> type to objectMapper.readTree(payload) })
        val ours = testCases.findByProjectIdOrderByIdAsc(projectId).toList().filter { it.capabilityKey == null }.associateBy { it.id!! }
        var written = 0
        for (o in observed) {
            val case = ours[o.caseId] ?: continue
            val meta = objectMapper.readTree(case.metadata.asString()) as ObjectNode
            meta.putObject("observed").apply {
                o.scene?.let { put("scene", it) }
                o.arrivesAt?.let { put("arrives_at", it) }
                o.input?.let { put("input", it) }
                if (o.changed.isNotEmpty()) putObject("changed").apply { o.changed.forEach { (k, v) -> put(k, v) } }
                put("qa_try_id", qaTryId)
                buildId?.let { put("build_id", it) }
                put("at", Instant.now().toString())
            }
            databaseClient.sql("UPDATE test_case SET metadata = CAST(:m AS jsonb), updated_at = now() WHERE id = :id")
                .bind("m", objectMapper.writeValueAsString(meta)).bind("id", case.id!!)
                .fetch().rowsUpdated().awaitSingle()
            written++
        }
        // 바뀐 값을 용어집 이름으로 옮겨 계약이 빠뜨린 '남기는 것' 을 더한다. 통과한 TC 만이다(관측은 COMPLETED 에서만 난다).
        val changed = observed.filter { it.changed.isNotEmpty() && it.caseId in ours }.associate { it.caseId to it.changed }
        val linked = runCatching { caseContracts.applyObserved(projectId, changed, buildId) }
            .onFailure { logger.warn("QA 관측을 계약에 옮기기 실패 [qaTryId=$qaTryId]: ${it.message}") }
            .getOrDefault(0)
        if (exits.isNotEmpty()) {
            // 같은 길(어디서 · 어디로 · 무엇으로)은 하나만 둔다. 다시 보면 그 길을 본 build 가 이번 것으로 바뀐다 —
            // 다음 버전에서도 그대로인 길이 "앞 버전에서만 봄" 으로 남지 않게.
            val seen = exits.map { it.copy(buildId = buildId) }
            val kept = caseContractsExits(projectId).filter { old -> seen.none { it.from == old.from && it.to == old.to && it.by == old.by } }
            databaseClient.sql("UPDATE case_glossary SET exits = CAST(:e AS jsonb), updated_at = now() WHERE project_id = :p")
                .bind("e", objectMapper.writeValueAsString(kept + seen)).bind("p", projectId).fetch().rowsUpdated().awaitSingle()
        }
        logger.info("QA 관측 쌓음 [qaTryId={}, projectId={}] 케이스 {} · 출구 {} · 계약에 옮긴 값 {}", qaTryId, projectId, written, exits.size, linked)
        return written
    }

    private suspend fun caseContractsExits(projectId: Long): List<Exit> =
        databaseClient.sql("SELECT exits::text AS exits FROM case_glossary WHERE project_id = :p")
            .bind("p", projectId).map { r, _ -> r.get("exits", String::class.java) ?: "[]" }.one().awaitSingleOrNull()
            ?.let { objectMapper.readValue(it, Array<Exit>::class.java).toList() }.orEmpty()

    companion object {
        /**
         * 로그를 앞에서부터 걷는다. 스텝 판정 사이가 그 TC 의 구간이다.
         *
         * **사람이 낼 수 있는 입력으로 바뀐 씬만 출구로 센다.** 치트(`go_to_scene`)나 행동(`auto_cast_spells`)으로
         * 넘어간 씬은 게임의 길이 아니다 — 쌓으면 저작이 그 길을 사람 스텝으로 적는다. 입력 뒤 씬이 한 번 바뀌면
         * 그 입력은 쓴 것으로 친다. 시간이 지나 저절로 넘어간 씬을 앞 입력 탓으로 돌리지 않기 위해서다.
         */
        fun walk(rows: List<Pair<String, JsonNode>>): Pair<List<Observed>, List<Exit>> {
            var scene: String? = null
            var lastInput: String? = null
            var start: String? = null
            var mover: String? = null
            val inputs = mutableListOf<String>()
            val values = mutableMapOf<String, String>()
            var windowValues: Map<String, String> = emptyMap()
            val observed = mutableListOf<Observed>()
            val exits = mutableListOf<Exit>()
            for ((type, p) in rows) {
                when (type) {
                    "PULSE" -> {
                        valuesIn(p).let { seen -> if (seen.isNotEmpty()) { values.putAll(seen); if (windowValues.isEmpty()) windowValues = values.toMap() } }
                        val now = p.path("scene").asText("").ifBlank { null } ?: continue
                        if (scene != null && now != scene) {
                            lastInput?.let { by -> Exit(scene!!, now, by).takeIf { it !in exits }?.let { exits += it }; mover = by }
                            lastInput = null
                        }
                        scene = now
                        if (start == null) start = now
                    }
                    "ACTION" -> p.path("actions").forEach { a ->
                        when (a.path("method").asText()) {
                            "key_click", "key_down" -> {
                                val key = "key:${a.path("params").path(0).asText()}"
                                inputs += key; lastInput = key
                            }
                            // 사람 입력이 아니다. 이 뒤의 씬 전환은 출구가 아니다.
                            "invoke_cheat", "start_behavior" -> lastInput = null
                            // 무엇을 눌렀는지는 결과가 와야 안다. 그 결과는 씬이 바뀐 뒤에 적힌다.
                            "mouse_down" -> { inputs += PENDING_CLICK; lastInput = PENDING_CLICK }
                        }
                    }
                    "TOOL_RESULT" -> if (p.path("content").asText("").contains("mouse_down:")) {
                        val target = CLICKED.find(p.path("content").asText(""))?.groupValues?.get(1)
                        if (target != null) {
                            val click = "click:$target"
                            exits.replaceAll { if (it.by == PENDING_CLICK) it.copy(by = click) else it }
                            inputs.replaceAll { if (it == PENDING_CLICK) click else it }
                            if (mover == PENDING_CLICK) mover = click
                            if (lastInput == PENDING_CLICK) lastInput = click
                        } else {
                            // 전해지지 않은 클릭이다. 그 사이 바뀐 씬은 클릭 때문이 아니다.
                            exits.removeIf { it.by == PENDING_CLICK }
                            inputs.removeAll { it == PENDING_CLICK }
                            if (mover == PENDING_CLICK) mover = null
                            if (lastInput == PENDING_CLICK) lastInput = null
                        }
                    }
                    "STATUS" -> {
                        if (p.path("step").isMissingNode || p.path("step").isNull) continue
                        val caseId = p.path("case_id").asLong(0)
                        val status = p.path("status").asText()
                        if (status != "COMPLETED" && status != "FAILED") continue
                        if (caseId > 0 && status == "COMPLETED") {
                            val arrived = scene.takeIf { it != start }
                            val changed = values.filter { (k, v) -> windowValues[k] != v }.entries.take(MAX_CHANGED)
                                .associate { (k, v) -> k to "${windowValues[k] ?: "?"}→$v" }
                            observed += Observed(caseId, start, arrived, (if (arrived != null) mover else null) ?: inputs.firstOrNull(), changed)
                        }
                        start = scene; inputs.clear(); mover = null; windowValues = values.toMap()
                    }
                }
            }
            return observed to exits.toList()
        }

        private const val PENDING_CLICK = "click:?"
        private const val MAX_CHANGED = 16

        /** 게임 값으로 읽을 것: 수 · 참거짓 · 글자. 계정 · 화면 장식 같은 잡음은 뺀다. */
        private val NOISE = listOf("Account.", "Vfx", "frames", "Material", "Sprite", "Token", "Localization", "resumedFrame")

        /** PULSE 의 정적 값 가운데 원시 값만. 이름은 `선언 타입.멤버` 다. */
        fun valuesIn(pulse: JsonNode): Map<String, String> =
            pulse.path("statics").mapNotNull { s ->
                val value = s.path("value")
                if (!(value.isNumber || value.isBoolean || value.isTextual)) return@mapNotNull null
                val name = "${s.path("declaring").asText()}.${s.path("member").asText()}"
                if (NOISE.any { it in name }) null else name to value.asText()
            }.toMap()
        private val CLICKED = Regex("""mouse_down: (\S+) 에 보냄""")
    }
}
