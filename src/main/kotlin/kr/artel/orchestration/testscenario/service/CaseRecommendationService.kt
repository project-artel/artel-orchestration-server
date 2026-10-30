package kr.artel.orchestration.testscenario.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import org.slf4j.LoggerFactory
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service

/**
 * 저작이 추천한 TC(Agent 의 `proposed_cases`). 시나리오에 필요한 상태 변화를 만드는 TC 가 없을 때 저작이 묻고, 답을
 * TC 로 추천한다. 사용자가 저장하기 전까지 여기 남고, 시나리오 스텝은 `recommendation_id` 로 이것을 가리킨다.
 *
 * - **TC 로 저장**: 사용자 TC 를 만들고(그 런의 build 버전에 넣는다) 스텝의 `recommendation_id` 를 그 TC 의 `case_id` 로
 *   바꾼다. 저장된 시나리오와 아직 승인 전인 draft 둘 다 바꾼다.
 * - **버리기**: 스텝은 판정 없는 스텝으로 남는다(`recommendation_id` 만 뗀다).
 * - **시나리오 draft 승인**은 그 draft 가 가리키는 추천을 먼저 저장한다 — 한 번에 저장된다.
 *
 * "draft" 는 시나리오에만 쓰는 말이라, 이것은 추천(recommendation)이라 부른다.
 */
@Service
class CaseRecommendationService(
    private val databaseClient: DatabaseClient,
    private val testCaseService: TestCaseService,
    private val projectAccess: ProjectAccessService,
    private val drafts: AuthoringDraftStore,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(CaseRecommendationService::class.java)

    data class Recommendation(
        val id: Long,
        val scene: String,
        val precondition: String,
        val step: String,
        val expectedValue: String,
        val why: String,
        val status: String,
        val testCaseId: Long?,
    ) {
        /** 대화 기록 · 화면 이벤트에 싣는 모양. */
        fun wire(): Map<String, Any?> = mapOf(
            "id" to id.toString(), "scene" to scene, "precondition" to precondition, "step" to step,
            "expectedValue" to expectedValue, "why" to why, "status" to status, "testCaseId" to testCaseId?.toString(),
        )
    }

    /**
     * Agent 가 시나리오와 함께 보낸 추천([drafts], `ref` 는 음수)을 남기고 `ref → 추천 id` 를 돌려준다. 스텝의
     * `proposed_case` 를 이 id 로 바꿔 저장한다.
     */
    suspend fun record(projectId: Long, runId: Long, proposed: JsonNode): Map<Long, Recommendation> {
        val out = linkedMapOf<Long, Recommendation>()
        for (draft in proposed) {
            val ref = draft.path("ref").asLong(0)
            val expected = draft.path("expected").asText("").trim()
            if (ref >= 0 || expected.isBlank() || ref in out) continue
            val id = databaseClient.sql(
                """
                INSERT INTO case_recommendation (project_id, test_run_id, scene, precondition, step, expected_value, why)
                VALUES (:p, :r, :scene, :pre, :step, :expected, :why) RETURNING id
                """.trimIndent()
            ).bind("p", projectId).bind("r", runId)
                .bind("scene", draft.path("scene").asText("")).bind("pre", draft.path("precondition").asText(""))
                .bind("step", draft.path("action").asText("")).bind("expected", expected).bind("why", draft.path("why").asText(""))
                .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }.one().awaitSingle()
            out[ref] = Recommendation(
                id, draft.path("scene").asText(""), draft.path("precondition").asText(""), draft.path("action").asText(""),
                expected, draft.path("why").asText(""), PENDING, null,
            )
        }
        return out
    }

    /**
     * 사람이 알려 준 길(`광장→숲` 을 어떻게 가나)을 씬 전이 TC 로 추천한다. 저장하면 계약 정리가 도착 화면 표식을 달고, 다음
     * 저작부터 그 길을 TC 로 안다 — QA 가 그 길을 확인할 수도 있게 된다. 이 프로젝트에 같은 길의 추천이 이미 있으면(대기 ·
     * 저장) 다시 내지 않는다. 버린 것은 다시 낼 수 있다 — 사람이 같은 길을 또 알려 줬다.
     */
    suspend fun recommendSceneMove(projectId: Long, runId: Long, from: String, to: String, how: String): Recommendation? {
        if (from.isBlank() || to.isBlank() || how.isBlank()) return null
        val expected = "$to 화면으로 바뀐다."
        val already = databaseClient.sql(
            """
            SELECT 1 FROM case_recommendation
             WHERE project_id = :p AND scene = :scene AND expected_value = :expected AND status IN ('PENDING', 'SAVED')
             LIMIT 1
            """.trimIndent()
        ).bind("p", projectId).bind("scene", from).bind("expected", expected)
            .map { _, _ -> true }.one().awaitSingleOrNull() == true
        if (already) return null
        val draft = objectMapper.createObjectNode()
            .put("ref", -1).put("scene", from).put("precondition", "").put("action", how).put("expected", expected)
            .put("why", "$from→$to 로 가는 길을 알려 주셨어요. TC 로 남기면 다음 저작부터 이 길을 알고, QA 도 확인해요.")
        return record(projectId, runId, objectMapper.createArrayNode().add(draft)).values.firstOrNull()
    }

    suspend fun list(projectId: Long, runId: Long, userId: Long): List<Recommendation> {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        return byIds(runId, null)
    }

    /**
     * 추천을 TC 로 저장한다. [ids] 가 비면 이 런의 대기 중인 추천 전부다. 저장한 TC 는 이 런의 build 버전에 들어가고,
     * 이 런의 시나리오와 이 사용자의 draft 에서 그 추천을 가리키던 스텝이 새 TC 를 확인하는 스텝으로 바뀐다.
     */
    suspend fun save(projectId: Long, runId: Long, userId: Long, ids: List<Long>, edits: Map<Long, TestCaseCreateRequest> = emptyMap()): List<Recommendation> {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val pending = byIds(runId, ids.ifEmpty { null }).filter { it.status == PENDING }
        if (pending.isEmpty()) return emptyList()
        val buildId = databaseClient.sql("SELECT game_build_id FROM test_run WHERE id = :r AND project_id = :p")
            .bind("r", runId).bind("p", projectId)
            // 버전 없는 TR 이면 null 이다. 리액터는 null 을 못 싣는다 — Optional 로 감싼다.
            .map { row, _ -> java.util.Optional.ofNullable(row.get("game_build_id", java.lang.Long::class.java)?.toLong()) }
            .one().awaitSingleOrNull()?.orElse(null)
        val saved = mutableListOf<Recommendation>()
        for (rec in pending) {
            val request = edits[rec.id] ?: TestCaseCreateRequest(
                scene = rec.scene.ifBlank { "-" }, step = rec.step.ifBlank { rec.expectedValue },
                precondition = rec.precondition.ifBlank { null }, expectedValue = rec.expectedValue,
            )
            val created = testCaseService.createTestCase(projectId, userId, request.copy(gameBuildId = buildId?.toString()))
                ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
            val caseId = created.id.toLong()
            databaseClient.sql("UPDATE case_recommendation SET status = 'SAVED', test_case_id = :c, updated_at = now() WHERE id = :id")
                .bind("c", caseId).bind("id", rec.id).fetch().rowsUpdated().awaitSingle()
            saved += rec.copy(status = SAVED, testCaseId = caseId)
        }
        // 길 추천은 스텝에 추천 표시가 없다 — 그 구간은 메우기가 사람이 한 말 그대로 사람 스텝으로 채웠다. 같은 말의 사람 스텝을
        // 새 TC 를 확인하는 스텝으로 바꾼다. 안 바꾸면 TC 는 저장돼도 어느 시나리오도 쓰지 않아 QA 가 확인하지 않는다.
        relink(
            runId, userId, saved.associate { it.id to it.testCaseId },
            saved.filter { it.step.isNotBlank() && it.testCaseId != null }.associate { it.step.trim() to it.testCaseId!! },
        )
        logger.info("추천 TC 저장 [runId={}] {}", runId, saved.map { it.id to it.testCaseId })
        return saved
    }

    /** 추천을 버린다. 그 추천을 가리키던 스텝은 판정 없는 스텝으로 남는다. */
    suspend fun discard(projectId: Long, runId: Long, userId: Long, ids: List<Long>): Int {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        val pending = byIds(runId, ids.ifEmpty { null }).filter { it.status == PENDING }
        for (rec in pending) {
            databaseClient.sql("UPDATE case_recommendation SET status = 'DISCARDED', updated_at = now() WHERE id = :id")
                .bind("id", rec.id).fetch().rowsUpdated().awaitSingle()
        }
        relink(runId, userId, pending.associate { it.id to null })
        return pending.size
    }

    /** draft 들이 가리키는 대기 중인 추천. 시나리오 승인이 먼저 저장한다. */
    fun referencedBy(steps: List<kr.artel.orchestration.testscenario.dto.ChatScenarioStep>): List<Long> =
        steps.mapNotNull { it.recommendationId }.distinct()

    /**
     * 추천을 가리키던 스텝을 바꾼다. 값이 TC id 면 그 TC 를 확인하는 스텝이 되고, null 이면 추천 표시만 뗀다. 이 런의 저장된
     * 시나리오와 이 사용자의 draft 를 함께 바꾼다.
     */
    private suspend fun relink(runId: Long, userId: Long, to: Map<Long, Long?>, byAction: Map<String, Long> = emptyMap()) {
        if (to.isEmpty() && byAction.isEmpty()) return
        val rows = databaseClient.sql(
            """
            SELECT s.id, s.steps::text AS steps FROM test_scenario s
              JOIN test_run_scenario r ON r.test_scenario_id = s.id
             WHERE r.test_run_id = :r AND (s.steps::text LIKE '%recommendation_id%' OR s.steps::text LIKE '%HUMAN%')
            """.trimIndent()
        ).bind("r", runId).map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() to row.get("steps", String::class.java)!! }
            .all().collectList().awaitSingle()
        for ((scenarioId, text) in rows) {
            val steps = objectMapper.readTree(text) as? ArrayNode ?: continue
            var changed = false
            steps.forEach { step ->
                val node = step as? ObjectNode ?: return@forEach
                if (node.path("step_source").asText() == "HUMAN" && !node.path("case_id").isNumber) {
                    byAction[node.path("action").asText("").trim()]?.let { caseId ->
                        node.put("case_id", caseId)
                        node.put("step_source", "CASE")
                        changed = true
                    }
                }
                val rec = node.path("recommendation_id").takeIf { it.isNumber }?.asLong() ?: return@forEach
                if (rec !in to) return@forEach
                node.remove("recommendation_id")
                // TC 가 되면 그 스텝은 그 TC 를 확인하는 스텝이다. 미상(UNKNOWN)으로 남기면 화면이 모르는 스텝으로 그린다.
                to[rec]?.let { node.put("case_id", it); node.put("step_source", "CASE") }
                changed = true
            }
            if (changed) {
                databaseClient.sql("UPDATE test_scenario SET steps = CAST(:s AS jsonb), updated_at = now() WHERE id = :id")
                    .bind("s", objectMapper.writeValueAsString(steps)).bind("id", scenarioId).fetch().rowsUpdated().awaitSingle()
            }
        }
        val key = "$userId:$runId"
        val draft = drafts.of(key)
        val relinked = draft.relink(to)
        if (draft.linkHuman(byAction) || relinked) drafts.publish(key)
    }

    private suspend fun byIds(runId: Long, ids: List<Long>?): List<Recommendation> {
        val sql = "SELECT * FROM case_recommendation WHERE test_run_id = :r" + (if (ids != null) " AND id IN (:ids)" else "") + " ORDER BY id"
        var spec = databaseClient.sql(sql).bind("r", runId)
        if (ids != null) spec = spec.bind("ids", ids)
        return spec.map { row, _ ->
            Recommendation(
                id = row.get("id", java.lang.Long::class.java)!!.toLong(),
                scene = row.get("scene", String::class.java).orEmpty(),
                precondition = row.get("precondition", String::class.java).orEmpty(),
                step = row.get("step", String::class.java).orEmpty(),
                expectedValue = row.get("expected_value", String::class.java).orEmpty(),
                why = row.get("why", String::class.java).orEmpty(),
                status = row.get("status", String::class.java).orEmpty(),
                testCaseId = row.get("test_case_id", java.lang.Long::class.java)?.toLong(),
            )
        }.all().collectList().awaitSingle()
    }

    companion object {
        const val PENDING = "PENDING"
        const val SAVED = "SAVED"
    }
}
