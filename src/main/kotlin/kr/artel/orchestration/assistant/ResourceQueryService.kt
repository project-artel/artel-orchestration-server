package kr.artel.orchestration.assistant

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testcase.dto.originLabel
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.CaseScope
import kr.artel.orchestration.testcase.service.TestCaseSearchService
import io.r2dbc.postgresql.codec.Json
import org.slf4j.LoggerFactory
import kr.artel.orchestration.testcase.service.TestCaseReader
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service

/**
 * 범용 질문 Agent 가 서비스의 엔터티를 따라가며 읽는 창구(ARTEL-1085, POC).
 *
 * 도구는 셋이다 — `find`(말로 찾기) · `get`(하나 자세히) · `related`(관계 따라가기). 종류마다 화면용 응답을 그대로
 * 넘기지 않고 **질문에 답하는 데 쓸 칸만** 추려 짧게 낸다. 모든 결과는 다음에 따라갈 관계(`relations`)를 싣는다 —
 * 모델이 다음 걸음을 고르기 쉽게 하는 것이 이 모양의 요점이다.
 *
 * **범위는 세션이 정한다.** 프로젝트는 세션에 묶인 것이고, 케이스 내용은 세션의 TR 버전으로 비춘다. 프레임이
 * 프로젝트를 지목하지 않는다 — 케이스 검색과 같은 원칙이다. 다른 프로젝트의 id 는 없는 것과 같다.
 */
@Service
class ResourceQueryService(
    private val db: DatabaseClient,
    private val testCases: TestCaseRepository,
    private val reader: TestCaseReader,
    private val objectMapper: ObjectMapper,
    private val caseSearch: TestCaseSearchService,
) {

    /** 질문 하나. [op] 와 [kind] 가 맞지 않으면 무엇을 쓸 수 있는지 말로 돌려준다(모델이 고쳐 부르게). */
    suspend fun query(scope: Scope, request: ResourceRequest): ResourceAnswer {
        val limit = (request.limit ?: DEFAULT_LIMIT).coerceIn(1, MAX_LIMIT)
        return when (request.op) {
            "find" -> find(scope, request.kind, request.text.orEmpty(), limit)
            // id 없는 test_run 은 지금 대화가 열린 TR 이다 — "이 테스트 런" 이 가리키는 것.
            "get" -> (request.id ?: scope.runId.takeIf { request.kind == "test_run" })?.let { get(scope, request.kind, it) }
                ?: note("get 에는 id 가 필요하다. 지금 테스트 런은 kind=test_run 에 id 없이 부른다.")
            "related" -> {
                val id = request.id ?: return note("related 에는 id 가 필요하다.")
                val relation = request.relation ?: return note("related 에는 relation 이 필요하다. ${RELATIONS[request.kind]}")
                related(scope, request.kind, id, relation, limit)
            }
            else -> note("op 는 find · get · related 중 하나다.")
        }
    }

    // ── find ─────────────────────────────────────────────────────────────────────────────

    private suspend fun find(scope: Scope, kind: String, text: String, limit: Int): ResourceAnswer = when (kind) {
        "case" -> {
            // 버전을 넘어 찾는다 — 사용자가 말하는 케이스가 지금 버전에 없는 옛 케이스일 수 있다.
            // **뜻으로 먼저 찾는다.** 사용자는 "전투 화면" 이라 말하고 케이스에는 `TurnBattleScene` 이라 적혀 있다 — 글자
            // 일치로는 못 찾았다(POC 측정 V5 · F1). 의미 검색이 실패하면 낱말 검색만으로 간다.
            val semantic = runCatching { caseSearch.search(scope.projectId, text, null, limit).results.map { it.id.toLong() } }
                .onFailure { logger.warn("케이스 의미 검색 실패 — 낱말 검색만 쓴다: {}", it.message) }
                .getOrDefault(emptyList())
            val byWords = wordMatches(
                "SELECT t.id FROM test_case t LEFT JOIN test_case_version v ON v.test_case_id = t.id WHERE t.project_id = :projectId",
                listOf("t.step", "t.scene", "t.expected_value", "v.step", "v.expected_value"),
                "t.id", text, limit, "projectId" to scope.projectId,
            )
            ResourceAnswer(caseItems(scope, (semantic + byWords).distinct().take(limit)))
        }
        "scenario" -> {
            val ids = wordMatches(
                "SELECT s.id FROM test_scenario s WHERE s.project_id = :projectId", listOf("s.title", "s.description"),
                "s.id", text, limit, "projectId" to scope.projectId,
            )
            if (ids.isEmpty()) ResourceAnswer(emptyList()) else ResourceAnswer(rows(
                "SELECT s.id, s.title, jsonb_array_length(s.steps) AS steps FROM test_scenario s WHERE s.id IN (:ids)", "ids" to ids,
            ).sortedBy { ids.indexOf(it.long("id")) }.map { scenarioItem(it.long("id"), it.str("title"), mapOf("steps" to it["steps"])) })
        }
        "test_run" -> {
            // 이름 전체가 아니라 낱말로 찾는다 — "재현 — 첫 스테이지 전투 클리어 테스트 런은…" 같은 말에 이름이 통째로
            // 들어 있지 않다(POC 측정 Q2: 못 찾고 지금 TR 로 답했다).
            val ids = wordMatches("SELECT r.id FROM test_run r WHERE r.project_id = :projectId", listOf("r.name", "r.description"),
                "r.id", text, limit, "projectId" to scope.projectId)
            if (ids.isEmpty()) ResourceAnswer(emptyList()) else ResourceAnswer(rows(
                "SELECT r.id, r.name, b.version FROM test_run r LEFT JOIN game_build b ON b.id = r.game_build_id WHERE r.id IN (:ids)", "ids" to ids,
            ).sortedBy { ids.indexOf(it.long("id")) }
                .map { item("test_run", it.long("id"), it.str("name") ?: "", mapOf("version" to it["version"]), RELATIONS.getValue("test_run")) })
        }
        "qa_run" -> ResourceAnswer(rows(
            """
            SELECT q.id, q.status, q.started_at, r.name, b.version,
                   (SELECT count(*) FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
                     WHERE t.qa_run_id = q.id AND l.type = 'STATUS' AND l.payload ->> 'status' = 'FAILED'
                       AND l.payload ->> 'is_verification' = 'true') AS failed_verifications
              FROM qa_run q
              JOIN test_run r ON r.id = q.test_run_id LEFT JOIN game_build b ON b.id = q.game_build_id
             WHERE r.project_id = :projectId AND (:text = '%%' OR r.name ILIKE :text)
             ORDER BY q.id DESC LIMIT :limit
            """, "projectId" to scope.projectId, "text" to "%${text.trim()}%", "limit" to limit,
        ).map {
            item("qa_run", it.long("id"), "${it.str("name")} · ${it["status"]}",
                mapOf("status" to it["status"], "test_run" to it["name"], "game_version" to it["version"], "started_at" to it["started_at"]?.toString(),
                    "failed_verifications" to it["failed_verifications"]),
                RELATIONS.getValue("qa_run"))
        })
        "build" -> {
            // editor 빌드는 게임 버전이 아니다(ARTEL-1014). 가장 최근에 생겨도 "최신 빌드" 가 아니다(POC 측정 N5).
            val builds = rows(
                """
                SELECT id, version, label, is_editor, created_at FROM game_build
                 WHERE project_id = :projectId ORDER BY is_editor, created_at DESC LIMIT :limit
                """, "projectId" to scope.projectId, "limit" to limit,
            )
            val latest = builds.firstOrNull { it["is_editor"] == false }?.long("id")
            ResourceAnswer(builds.map { row ->
                item("build", row.long("id"), row.str("label") ?: row.str("version") ?: "",
                    mapOf("version" to row["version"], "is_editor" to row["is_editor"], "latest_game_version" to (row.long("id") == latest),
                        "is_this_test_run_version" to (row.long("id") == scope.gameBuildId), "created_at" to row["created_at"]?.toString()),
                    emptyList())
            })
        }
        "issue" -> {
            val ids = wordMatches(
                """
                SELECT i.id FROM issue i JOIN qa_try t ON t.id = i.qa_try_id JOIN test_scenario s ON s.id = t.test_scenario_id
                 WHERE s.project_id = :projectId
                """, listOf("i.title", "CAST(i.detail AS text)"), "i.id", text, limit, "projectId" to scope.projectId,
            )
            if (ids.isEmpty()) ResourceAnswer(emptyList()) else ResourceAnswer(rows(
                """
                SELECT i.id, i.severity, i.title, i.status, i.detail, i.qa_try_id, t.qa_run_id FROM issue i
                  JOIN qa_try t ON t.id = i.qa_try_id WHERE i.id IN (:ids)
                """, "ids" to ids,
            ).sortedBy { ids.indexOf(it.long("id")) }.map {
                item("issue", it.long("id"), it.str("title") ?: "",
                    mapOf("severity" to it["severity"], "status" to it["status"], "qa_try_id" to it["qa_try_id"],
                        "qa_run_id" to it["qa_run_id"], "detail" to it["detail"]?.let(::jsonText)?.take(STEP_TEXT)),
                    emptyList())
            })
        }
        else -> note("find 는 case · scenario · test_run · qa_run · build · issue 를 찾는다.")
    }

    // ── get ──────────────────────────────────────────────────────────────────────────────

    private suspend fun get(scope: Scope, kind: String, id: Long): ResourceAnswer = when (kind) {
        "case" -> {
            val items = caseItems(scope, listOf(id), detailed = true)
            if (items.isEmpty()) note("이 프로젝트에 케이스 $id 이 없다.") else ResourceAnswer(items)
        }
        "scenario" -> {
            val row = rows("SELECT id, title, description, steps FROM test_scenario WHERE id = :id AND project_id = :projectId",
                "id" to id, "projectId" to scope.projectId).firstOrNull()
                ?: return note("이 프로젝트에 시나리오 $id 이 없다.")
            // `tc_no` 는 화면의 "TC n" 이다 — 연이은 같은 케이스를 한 덩어리로 세는 순번이고 케이스 id 가 아니다.
            // 사용자는 이 번호로 말하므로 같은 규칙(home `groupStepsByCase`)으로 센다.
            var tcNo = 0
            var previousCase: Long? = null
            val steps = objectMapper.readTree(jsonText(row["steps"])).mapIndexed { index, step ->
                val caseId = step.path("case_id").takeIf { it.isNumber }?.asLong()
                if (caseId != null && caseId != previousCase) tcNo += 1
                previousCase = caseId
                mapOf(
                    "no" to index + 1,
                    "action" to step.path("action").asText().take(STEP_TEXT),
                    "tc_no" to caseId?.let { tcNo },
                    "case_ref" to caseId?.let { "[[tc:$it]]" },
                )
            }
            ResourceAnswer(listOf(scenarioItem(id, row.str("title"), mapOf("description" to row["description"], "steps" to steps))))
        }
        "test_run" -> {
            val row = rows(
                """
                SELECT r.id, r.name, r.game_build_id, b.version FROM test_run r LEFT JOIN game_build b ON b.id = r.game_build_id
                 WHERE r.id = :id AND r.project_id = :projectId
                """, "id" to id, "projectId" to scope.projectId,
            ).firstOrNull() ?: return note("이 프로젝트에 테스트 런 $id 이 없다.")
            val scenarios = rows(
                """
                SELECT s.id, s.title FROM test_run_scenario l JOIN test_scenario s ON s.id = l.test_scenario_id
                 WHERE l.test_run_id = :id ORDER BY l.position
                """, "id" to id,
            ).map { mapOf("ref" to "[[ts:${it.long("id")}]]", "title" to it["title"]) }
            ResourceAnswer(listOf(item("test_run", id, row.str("name") ?: "",
                mapOf("version" to (row["version"] ?: "버전 없음"), "scenarios" to scenarios), RELATIONS.getValue("test_run"))))
        }
        "qa_run" -> {
            val run = rows(
                """
                SELECT q.id, q.status, q.started_at, q.completed_at, r.name, b.version, b.is_editor FROM qa_run q
                  JOIN test_run r ON r.id = q.test_run_id LEFT JOIN game_build b ON b.id = q.game_build_id
                 WHERE q.id = :id AND r.project_id = :projectId
                """, "id" to id, "projectId" to scope.projectId,
            ).firstOrNull() ?: return note("이 프로젝트에 QA 실행 $id 이 없다.")
            ResourceAnswer(listOf(item("qa_run", id, "${run.str("name")} · ${run["status"]}",
                mapOf("status" to run["status"], "game_version" to run["version"], "editor" to run["is_editor"],
                    "started_at" to run["started_at"]?.toString(), "completed_at" to run["completed_at"]?.toString(),
                    "tries" to tries(id)),
                RELATIONS.getValue("qa_run"))))
        }
        "qa_try" -> qaTry(scope, id)
        "build" -> {
            val row = rows("SELECT id, version, label, is_editor, created_at FROM game_build WHERE id = :id AND project_id = :projectId",
                "id" to id, "projectId" to scope.projectId).firstOrNull() ?: return note("이 프로젝트에 빌드 $id 이 없다.")
            ResourceAnswer(listOf(item("build", id, row.str("label") ?: row.str("version") ?: "",
                mapOf("version" to row["version"], "is_editor" to row["is_editor"], "created_at" to row["created_at"]?.toString()),
                emptyList())))
        }
        else -> note("get 은 case · scenario · test_run · qa_run · qa_try · build 를 읽는다.")
    }

    // ── related ──────────────────────────────────────────────────────────────────────────

    private suspend fun related(scope: Scope, kind: String, id: Long, relation: String, limit: Int): ResourceAnswer =
        when ("$kind.$relation") {
            "case.versions" -> caseVersions(scope, id)
            "case.verdicts" -> caseVerdicts(scope, id, limit)
            "case.scenarios" -> ResourceAnswer(rows(
                """
                SELECT s.id, s.title FROM test_scenario s
                 WHERE s.project_id = :projectId AND s.steps @> CAST(:needle AS jsonb) ORDER BY s.id DESC LIMIT :limit
                """, "projectId" to scope.projectId, "needle" to """[{"case_id": $id}]""", "limit" to limit,
            ).map { scenarioItem(it.long("id"), it.str("title"), emptyMap()) })
            "scenario.cases" -> {
                val steps = rows("SELECT steps FROM test_scenario WHERE id = :id AND project_id = :projectId",
                    "id" to id, "projectId" to scope.projectId).firstOrNull()?.get("steps")
                    ?: return note("이 프로젝트에 시나리오 $id 이 없다.")
                val ids = objectMapper.readTree(jsonText(steps)).mapNotNull { it.path("case_id").takeIf { n -> n.isNumber }?.asLong() }.distinct()
                ResourceAnswer(caseItems(scope, ids.take(limit)))
            }
            "scenario.test_runs" -> ResourceAnswer(rows(
                """
                SELECT r.id, r.name FROM test_run_scenario l JOIN test_run r ON r.id = l.test_run_id
                 WHERE l.test_scenario_id = :id AND r.project_id = :projectId
                """, "id" to id, "projectId" to scope.projectId,
            ).map { item("test_run", it.long("id"), it.str("name") ?: "", emptyMap(), RELATIONS.getValue("test_run")) })
            "test_run.qa_runs" -> ResourceAnswer(rows(
                """
                SELECT q.id, q.status, q.started_at, b.version FROM qa_run q LEFT JOIN game_build b ON b.id = q.game_build_id
                  JOIN test_run r ON r.id = q.test_run_id
                 WHERE q.test_run_id = :id AND r.project_id = :projectId ORDER BY q.id DESC LIMIT :limit
                """, "id" to id, "projectId" to scope.projectId, "limit" to limit,
            ).map {
                item("qa_run", it.long("id"), "${it["status"]} · ${it["started_at"]}",
                    mapOf("status" to it["status"], "game_version" to it["version"]), RELATIONS.getValue("qa_run"))
            })
            "test_run.scenarios" -> get(scope, "test_run", id)
            "qa_run.tries" -> ResourceAnswer(tries(id).map { item("qa_try", (it["qa_try_id"] as Number).toLong(), it["scenario"].toString(), it, RELATIONS.getValue("qa_try")) })
            "qa_try.issues" -> ResourceAnswer(rows(
                """
                SELECT i.id, i.severity, i.title, i.status, i.detail FROM issue i
                  JOIN qa_try t ON t.id = i.qa_try_id JOIN test_scenario s ON s.id = t.test_scenario_id
                 WHERE i.qa_try_id = :id AND s.project_id = :projectId ORDER BY i.id
                """, "id" to id, "projectId" to scope.projectId,
            ).map {
                item("issue", it.long("id"), it.str("title") ?: "",
                    mapOf("severity" to it["severity"], "status" to it["status"], "detail" to it["detail"]?.let(::jsonText)?.take(STEP_TEXT)), emptyList())
            })
            else -> note("$kind 의 관계는 ${RELATIONS[kind] ?: "없다"}.")
        }

    // ── case ─────────────────────────────────────────────────────────────────────────────

    /** 케이스를 세션의 TR 버전으로 비춰 낸다. TR 버전에 없으면 그렇다고 적고, 어느 버전에 있는지 함께 싣는다. */
    private suspend fun caseItems(scope: Scope, ids: List<Long>, detailed: Boolean = false): List<ResourceItem> {
        if (ids.isEmpty()) return emptyList()
        val identities = testCases.findAllById(ids).toList().filter { it.projectId == scope.projectId }.sortedBy { it.id }
        val trScope = CaseScope.of(scope.gameBuildId)
        val inTr = scope.gameBuildId?.let { reader.byIds(identities.mapNotNull(TestCaseEntity::id), CaseScope.Version(it)) }
        val shown = reader.overlay(identities, trScope)
        val builds = buildsOf(identities.mapNotNull { it.id })
        return shown.map { case ->
            val caseId = requireNotNull(case.id)
            val fields = linkedMapOf<String, Any?>(
                "scene" to case.scene,
                "step" to case.step,
                "expected" to case.expectedValue,
                "verification_status" to case.verificationStatus,
                "in_this_test_run_version" to (inTr?.containsKey(caseId) ?: true),
                "versions" to builds[caseId].orEmpty(),
            )
            // 어느 빌드에도 몫이 없는 케이스는 "이전 버전" 이 아니라 버전 없는 케이스다(POC 측정 V2).
            if (builds[caseId].isNullOrEmpty()) {
                fields["version_note"] = "어느 게임 버전에도 속하지 않은 케이스(사람이 쓴 버전 없는 케이스)"
            }
            if (detailed) {
                fields["precondition"] = case.precondition
                fields["origin"] = case.originLabel()
            }
            item("case", caseId, case.step, fields, RELATIONS.getValue("case"))
        }
    }

    /** 케이스가 든 빌드와 그 빌드의 판정. 옛 → 새 순이다. editor 빌드는 게임 버전이 아니라 뺀다. */
    private suspend fun buildsOf(caseIds: List<Long>): Map<Long, List<Map<String, Any?>>> {
        if (caseIds.isEmpty()) return emptyMap()
        return rows(
            """
            SELECT v.test_case_id, b.version, v.verification_status FROM test_case_version v
              JOIN game_build b ON b.id = v.game_build_id
             WHERE v.test_case_id IN (:ids) AND b.is_editor = FALSE ORDER BY b.created_at, b.id
            """, "ids" to caseIds,
        ).groupBy({ it.long("test_case_id") }, { mapOf("version" to it["version"], "status" to it["verification_status"]) })
    }

    /** 빌드별 몫과, 세션 TR 버전의 내용과 무엇이 다른지. "이건 이전 버전 케이스다" 에 답하는 재료다. */
    private suspend fun caseVersions(scope: Scope, caseId: Long): ResourceAnswer {
        val identity = testCases.findById(caseId)?.takeIf { it.projectId == scope.projectId }
            ?: return note("이 프로젝트에 케이스 $caseId 이 없다.")
        val rows = rows(
            """
            SELECT v.game_build_id, b.version, b.label, b.created_at, v.verification_status FROM test_case_version v
              JOIN game_build b ON b.id = v.game_build_id
             WHERE v.test_case_id = :id AND b.is_editor = FALSE ORDER BY b.created_at, b.id
            """, "id" to caseId,
        )
        val base = reader.overlay(identity, CaseScope.of(scope.gameBuildId))
        val items = rows.map { row ->
            val build = row.long("game_build_id")
            val shown = reader.overlay(identity, CaseScope.Version(build))
            val differs = listOfNotNull(
                "step".takeIf { shown.step != base.step },
                "precondition".takeIf { shown.precondition != base.precondition },
                "expected".takeIf { shown.expectedValue != base.expectedValue },
                "scene".takeIf { shown.scene != base.scene },
            )
            item("case_version", build, "${row.str("label") ?: row.str("version")}",
                mapOf(
                    "version" to row["version"],
                    "verification_status" to row["verification_status"],
                    "is_this_test_run_version" to (build == scope.gameBuildId),
                    "differs_from_this_test_run_version" to differs,
                    "step" to shown.step.takeIf { "step" in differs },
                    "expected" to shown.expectedValue.takeIf { "expected" in differs },
                ),
                emptyList())
        }
        val noteText = if (scope.gameBuildId != null && rows.none { it.long("game_build_id") == scope.gameBuildId })
            "이 케이스는 이 테스트 런의 버전에 없다." else null
        return ResourceAnswer(items, noteText)
    }

    /**
     * 케이스의 최근 판정. 검증 스텝의 결과와 그 스텝에 Agent 가 남긴 이유다. 실패면 같은 시도에서 앞서 실패한
     * 스텝도 싣는다 — 앞 스텝이 막혀 사전조건이 안 섰는지(결함이 아닌지) 가르는 재료다.
     */
    private suspend fun caseVerdicts(scope: Scope, caseId: Long, limit: Int): ResourceAnswer {
        testCases.findById(caseId)?.takeIf { it.projectId == scope.projectId } ?: return note("이 프로젝트에 케이스 $caseId 이 없다.")
        val verdicts = rows(
            """
            SELECT l.qa_try_id, t.qa_run_id, b.version, l.created_at,
                   l.payload ->> 'status' AS status, (l.payload ->> 'step')::int AS step, l.payload ->> 'message' AS message
              FROM qa_log l JOIN qa_try t ON t.id = l.qa_try_id
              LEFT JOIN qa_run q ON q.id = t.qa_run_id LEFT JOIN game_build b ON b.id = q.game_build_id
             WHERE l.type = 'STATUS' AND (l.payload ->> 'case_id') = :caseId
               AND (l.payload ->> 'is_verification') = 'true'
             ORDER BY l.id DESC LIMIT :limit
            """, "caseId" to caseId.toString(), "limit" to limit,
        )
        val items = verdicts.map { row ->
            val failed = row["status"] == "FAILED"
            val tryId = row.long("qa_try_id")
            val before = if (!failed) emptyList() else rows(
                """
                SELECT (payload ->> 'step')::int AS step, payload ->> 'case_id' AS case_id, payload ->> 'message' AS message
                  FROM qa_log WHERE qa_try_id = :tryId AND type = 'STATUS' AND payload ->> 'status' = 'FAILED'
                   AND (payload ->> 'step')::int < :step ORDER BY (payload ->> 'step')::int LIMIT 3
                """, "tryId" to tryId, "step" to (row["step"] as Number).toInt(),
            ).map { mapOf("step" to it["step"], "case_id" to it["case_id"], "message" to it.str("message")?.take(STEP_TEXT)) }
            item("verdict", tryId, "${row["status"]} · ${row["version"] ?: "?"}",
                mapOf(
                    "result" to if (failed) "FAILED" else "PASSED",
                    "game_version" to row["version"],
                    "qa_run_id" to row["qa_run_id"],
                    "step" to row["step"],
                    "reason" to row.str("message")?.take(STEP_TEXT),
                    "earlier_failed_steps" to before,
                    "at" to row["created_at"]?.toString(),
                ),
                RELATIONS.getValue("qa_try"))
        }
        return ResourceAnswer(items, if (items.isEmpty()) "이 케이스는 아직 QA 판정을 받은 적이 없다." else null)
    }

    // ── qa ───────────────────────────────────────────────────────────────────────────────

    private suspend fun tries(qaRunId: Long): List<Map<String, Any?>> = rows(
        """
        SELECT t.id, t.status, s.title, t.cases_total, t.cases_passed, t.steps_total, t.steps_passed FROM qa_try t
          JOIN test_scenario s ON s.id = t.test_scenario_id WHERE t.qa_run_id = :id ORDER BY t.id
        """, "id" to qaRunId,
    ).map {
        // 실패한 검증을 요약에 바로 싣는다. 시도 상태가 COMPLETED 여도 케이스는 실패했을 수 있다 — 따로 더 읽어야
        // 보이게 두면 "다 잘 끝났다" 로 요약된다(실측: QA 17).
        val failedCases = rows(
            """
            SELECT payload ->> 'case_id' AS case_id, payload ->> 'message' AS message FROM qa_log
             WHERE qa_try_id = :id AND type = 'STATUS' AND payload ->> 'status' = 'FAILED'
               AND payload ->> 'is_verification' = 'true' ORDER BY (payload ->> 'step')::int LIMIT 10
            """, "id" to it.long("id"),
        ).map { failed -> mapOf("case_ref" to failed.str("case_id")?.let { c -> "[[tc:$c]]" }, "reason" to failed.str("message")?.take(STEP_TEXT)) }
        mapOf("qa_try_id" to it["id"], "scenario" to it["title"], "status" to it["status"],
            "cases" to "${it["cases_passed"] ?: "?"}/${it["cases_total"] ?: "?"}",
            "steps" to "${it["steps_passed"] ?: "?"}/${it["steps_total"] ?: "?"}",
            "failed_cases" to failedCases)
    }

    private suspend fun qaTry(scope: Scope, id: Long): ResourceAnswer {
        val row = rows(
            """
            SELECT t.id, t.status, t.qa_run_id, s.id AS scenario_id, s.title, b.version, t.cases_total, t.cases_passed
              FROM qa_try t JOIN test_scenario s ON s.id = t.test_scenario_id
              LEFT JOIN qa_run q ON q.id = t.qa_run_id LEFT JOIN game_build b ON b.id = q.game_build_id
             WHERE t.id = :id AND s.project_id = :projectId
            """, "id" to id, "projectId" to scope.projectId,
        ).firstOrNull() ?: return note("이 프로젝트에 QA 시도 $id 이 없다.")
        val failed = rows(
            """
            SELECT (payload ->> 'step')::int AS step, payload ->> 'case_id' AS case_id, payload ->> 'message' AS message,
                   payload ->> 'is_verification' AS verification
              FROM qa_log WHERE qa_try_id = :id AND type = 'STATUS' AND payload ->> 'status' = 'FAILED'
             ORDER BY (payload ->> 'step')::int LIMIT 20
            """, "id" to id,
        ).map {
            mapOf("step" to it["step"], "case_ref" to it.str("case_id")?.let { c -> "[[tc:$c]]" },
                "is_verification" to (it["verification"] == "true"), "reason" to it.str("message")?.take(STEP_TEXT))
        }
        return ResourceAnswer(listOf(item("qa_try", id, row.str("title") ?: "",
            mapOf("status" to row["status"], "qa_run_id" to row["qa_run_id"], "scenario_ref" to "[[ts:${row.long("scenario_id")}]]",
                "game_version" to row["version"], "cases" to "${row["cases_passed"] ?: "?"}/${row["cases_total"] ?: "?"}",
                "failed_steps" to failed),
            RELATIONS.getValue("qa_try"))))
    }

    // ── 공통 ─────────────────────────────────────────────────────────────────────────────

    private fun scenarioItem(id: Long, title: String?, fields: Map<String, Any?>) =
        item("scenario", id, title ?: "", fields, RELATIONS.getValue("scenario"))

    /** TC · TS 는 표식을 싣는다. 모델이 답에 그대로 쓰면 화면이 칩으로 그린다. */
    private fun item(kind: String, id: Long, label: String, fields: Map<String, Any?>, relations: List<String>) =
        ResourceItem(
            kind = kind, id = id, label = label,
            ref = when (kind) { "case" -> "[[tc:$id]]"; "scenario" -> "[[ts:$id]]"; else -> null },
            fields = fields.filterValues { it != null },
            relations = relations,
        )

    private fun note(text: String) = ResourceAnswer(emptyList(), text)

    private suspend fun rows(sql: String, vararg binds: Pair<String, Any>): List<Map<String, Any?>> {
        var spec = db.sql(sql.trimIndent())
        binds.forEach { (name, value) -> spec = spec.bind(name, value) }
        return spec.fetch().flow().toList()
    }

    /**
     * 낱말로 찾는다. 말 전체를 한 덩어리로 맞추면 "조합 버튼 관련해서 올라온 이슈" 같은 말은 아무것도 맞지 않는다 — 두 글자
     * 이상 낱말 하나라도 맞으면 찾고, 많이 맞은 것을 앞에 둔다. [base] 는 `WHERE` 까지 쓴 SELECT 다.
     */
    private suspend fun wordMatches(
        base: String, columns: List<String>, idColumn: String, text: String, limit: Int, vararg binds: Pair<String, Any>,
    ): List<Long> {
        val words = WORD.findAll(text).map { it.value }.filter { it.length >= 2 && it !in STOP_WORDS }.distinct().take(8).toList()
        if (words.isEmpty()) return rows("${base.trimIndent()} ORDER BY $idColumn DESC LIMIT :limit", *binds, "limit" to limit)
            .map { it.long("id") }.distinct()
        val hit = { i: Int -> columns.joinToString(" OR ") { "$it ILIKE :w$i" } }
        val score = words.indices.joinToString(" + ") { "MAX(CASE WHEN ${hit(it)} THEN 1 ELSE 0 END)" }
        val sql = "${base.trimIndent()} AND (${words.indices.joinToString(" OR ") { hit(it) }}) " +
            "GROUP BY $idColumn ORDER BY ($score) DESC, $idColumn DESC LIMIT :limit"
        val wordBinds = words.mapIndexed { i, w -> "w$i" to "%$w%" }.toTypedArray()
        return rows(sql, *binds, *wordBinds, "limit" to limit).map { it.long("id") }
    }

    /** jsonb 칸은 R2DBC 가 [Json] 으로 준다. `toString()` 은 내용이 아니다(POC 측정 N1 — 시나리오를 못 읽었다). */
    private fun jsonText(value: Any?): String = when (value) {
        is Json -> value.asString()
        null -> "[]"
        else -> value.toString()
    }

    private fun Map<String, Any?>.long(key: String): Long = (getValue(key) as Number).toLong()
    private fun Map<String, Any?>.str(key: String): String? = this[key]?.toString()

    /** 세션이 정한 범위. 프레임은 이것을 바꿀 수 없다. */
    data class Scope(val projectId: Long, val gameBuildId: Long?, val runId: Long? = null)

    companion object {
        const val DEFAULT_LIMIT = 10
        const val MAX_LIMIT = 30
        const val STEP_TEXT = 300

        private val logger = LoggerFactory.getLogger(ResourceQueryService::class.java)
        private val WORD = Regex("""[\p{L}\p{N}_/.]+""")

        /** 찾는 말에서 뺄 낱말. 질문 투의 말은 내용이 아니다. */
        private val STOP_WORDS = setOf("관련", "관련해서", "올라온", "있어", "뭐", "케이스", "이슈", "시나리오", "확인", "확인하는", "테스트")


        /** 종류마다 따라갈 수 있는 관계. 결과의 `relations` 와 잘못 부른 때의 안내가 같은 표를 쓴다. */
        val RELATIONS: Map<String, List<String>> = mapOf(
            "case" to listOf("versions", "verdicts", "scenarios"),
            "scenario" to listOf("cases", "test_runs"),
            "test_run" to listOf("scenarios", "qa_runs"),
            "qa_run" to listOf("tries"),
            "qa_try" to listOf("issues"),
        )
    }
}

/** Agent 가 보낸 질문 하나(`resource_query` 프레임). */
data class ResourceRequest(
    val op: String,
    val kind: String,
    val id: Long? = null,
    val text: String? = null,
    val relation: String? = null,
    val limit: Int? = null,
)

/** 엔터티 하나의 요약. [ref] 는 TC · TS 표식이다. [relations] 는 `related` 로 따라갈 수 있는 이름이다. */
data class ResourceItem(
    val kind: String,
    val id: Long,
    val label: String,
    val ref: String?,
    val fields: Map<String, Any?>,
    val relations: List<String>,
)

/** 답. 찾은 것이 없거나 잘못 불렀으면 [note] 가 그 이유를 말한다. */
data class ResourceAnswer(val items: List<ResourceItem>, val note: String? = null)
