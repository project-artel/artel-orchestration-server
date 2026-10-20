package kr.artel.orchestration.issue.repository

import io.r2dbc.spi.Readable
import kotlinx.coroutines.reactor.awaitSingle
import kr.artel.orchestration.issue.dto.IssueBuildFacet
import kr.artel.orchestration.issue.dto.IssueOrigin
import kr.artel.orchestration.issue.dto.IssueTestRunFacet
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Repository
import java.time.OffsetDateTime

/**
 * 이슈가 나온 자리(시나리오 · TR · 빌드)를 읽는다.
 *
 * 이슈는 이 셋을 직접 들지 않는다. `qa_try` → `qa_run` → `test_run` · `game_build` 를 거쳐야 닿는다. 그래서 한
 * 페이지의 try 들을 **한 문장**으로 모아 읽는다 — 줄마다 물으면 N+1 이다.
 *
 * 빌드는 run 이 고른 빌드(`qa_run.game_build_id`)를 먼저 보고, 없으면 TR 의 빌드를 본다. run 이 빌드를 고르기
 * 전의 실행은 TR 의 빌드로 돌았기 때문이다.
 */
@Repository
class IssueOriginRepository(private val database: DatabaseClient) {

    suspend fun originsOf(qaTryIds: Collection<Long>): Map<Long, IssueOrigin> {
        if (qaTryIds.isEmpty()) return emptyMap()
        return database.sql(
            """
            SELECT qt.id AS qa_try_id, ts.id AS test_scenario_id, ts.title AS test_scenario_title,
                   tr.id AS test_run_id, tr.name AS test_run_name,
                   gb.id AS game_build_id, gb.version AS build_version, gb.label AS build_label,
                   qt.model, r.started_at AS qa_run_started_at
            FROM qa_try qt
            JOIN test_scenario ts ON ts.id = qt.test_scenario_id
            LEFT JOIN qa_run r ON r.id = qt.qa_run_id
            LEFT JOIN test_run tr ON tr.id = r.test_run_id
            LEFT JOIN game_build gb ON gb.id = COALESCE(r.game_build_id, tr.game_build_id)
            WHERE qt.id IN (:qaTryIds)
            """
        )
            .bind("qaTryIds", qaTryIds.distinct())
            .map { row ->
                row.long("qa_try_id")!! to IssueOrigin(
                    testScenarioId = row.long("test_scenario_id").toString(),
                    testScenarioTitle = row.get("test_scenario_title", String::class.java).orEmpty(),
                    testRunId = row.long("test_run_id")?.toString(),
                    testRunName = row.get("test_run_name", String::class.java),
                    gameBuildId = row.long("game_build_id")?.toString(),
                    buildVersion = row.get("build_version", String::class.java),
                    buildLabel = row.get("build_label", String::class.java),
                    model = row.get("model", String::class.java),
                    qaRunStartedAt = row.get("qa_run_started_at", OffsetDateTime::class.java)?.toInstant(),
                )
            }
            .all()
            .collectList()
            .awaitSingle()
            .toMap()
    }

    /**
     * 프로젝트 이슈를 빌드 · TR · 심각도로 센다. 한 문장의 `GROUPING SETS` 라 표를 한 번만 훑는다.
     * [status] 가 있으면 그 상태의 이슈만 센다 — 목록의 상태 거르기와 같은 기준으로 개수를 보여야 한다.
     */
    suspend fun facets(projectId: Long, status: String?, gameBuildId: Long? = null, testRunId: Long? = null): Facets {
        val rows = database.sql(
            """
            SELECT GROUPING(bid) AS g_build, GROUPING(trid) AS g_tr, GROUPING(i.severity) AS g_sev,
                   bid, MAX(gb.version) AS version, MAX(gb.label) AS label,
                   trid, MAX(tr.name) AS tr_name, MAX(k.bid) AS tr_build,
                   i.severity, COUNT(*) AS n, COUNT(*) FILTER (WHERE i.status = 'OPEN') AS open_n
            FROM issue i
            JOIN qa_try qt ON qt.id = i.qa_try_id
            JOIN test_scenario ts ON ts.id = qt.test_scenario_id
            LEFT JOIN qa_run r ON r.id = qt.qa_run_id
            LEFT JOIN test_run tr ON tr.id = r.test_run_id
            CROSS JOIN LATERAL (SELECT COALESCE(r.game_build_id, tr.game_build_id) AS bid, r.test_run_id AS trid) k
            LEFT JOIN game_build gb ON gb.id = k.bid
            WHERE ts.project_id = :projectId
              AND i.archived_at IS NULL
              AND (:status IS NULL OR i.status = :status)
              AND (:gameBuildId IS NULL OR k.bid = :gameBuildId)
              AND (:testRunId IS NULL OR k.trid = :testRunId)
            GROUP BY GROUPING SETS ((bid), (trid), (i.severity), ())
            """
        )
            .bind("projectId", projectId)
            .let { if (status == null) it.bindNull("status", String::class.java) else it.bind("status", status) }
            .let { if (gameBuildId == null) it.bindNull("gameBuildId", java.lang.Long::class.java) else it.bind("gameBuildId", gameBuildId) }
            .let { if (testRunId == null) it.bindNull("testRunId", java.lang.Long::class.java) else it.bind("testRunId", testRunId) }
            .map { row -> row.facetRow() }
            .all()
            .collectList()
            .awaitSingle()
        val total = rows.firstOrNull { it.kind == Kind.TOTAL }?.count ?: 0
        val archived = database.sql("SELECT count(*) AS n FROM issue WHERE project_id = :projectId AND archived_at IS NOT NULL")
            .bind("projectId", projectId)
            .map { row -> row.get("n", java.lang.Long::class.java)!!.toLong() }
            .one().awaitSingle()
        return Facets(
            total = total,
            archived = archived,
            builds = rows.filter { it.kind == Kind.BUILD }
                .map { IssueBuildFacet(it.buildId?.toString(), it.version, it.label, it.count, it.openCount) }
                .sortedByDescending { it.gameBuildId?.toLong() ?: -1 },
            testRuns = rows.filter { it.kind == Kind.TEST_RUN }
                .map { IssueTestRunFacet(it.testRunId?.toString(), it.testRunName, it.testRunBuildId?.toString(), it.count, it.openCount) }
                .sortedByDescending { it.testRunId?.toLong() ?: -1 },
            severities = rows.filter { it.kind == Kind.SEVERITY && it.severity != null }.associate { it.severity!! to it.count },
        )
    }

    data class Facets(
        val total: Long,
        val archived: Long,
        val builds: List<IssueBuildFacet>,
        val testRuns: List<IssueTestRunFacet>,
        val severities: Map<String, Long>,
    )

    private enum class Kind { BUILD, TEST_RUN, SEVERITY, TOTAL }

    private data class FacetRow(
        val kind: Kind,
        val buildId: Long?,
        val version: String?,
        val label: String?,
        val testRunId: Long?,
        val testRunName: String?,
        val testRunBuildId: Long?,
        val severity: String?,
        val count: Long,
        val openCount: Long,
    )

    private fun Readable.facetRow(): FacetRow {
        val groupedBuild = int("g_build") == 1
        val groupedTestRun = int("g_tr") == 1
        val groupedSeverity = int("g_sev") == 1
        val kind = when {
            !groupedBuild -> Kind.BUILD
            !groupedTestRun -> Kind.TEST_RUN
            !groupedSeverity -> Kind.SEVERITY
            else -> Kind.TOTAL
        }
        return FacetRow(
            kind = kind,
            buildId = long("bid"),
            version = get("version", String::class.java),
            label = get("label", String::class.java),
            testRunId = long("trid"),
            testRunName = get("tr_name", String::class.java),
            testRunBuildId = long("tr_build"),
            severity = get("severity", String::class.java),
            count = long("n") ?: 0,
            openCount = long("open_n") ?: 0,
        )
    }

    private fun Readable.long(name: String): Long? = get(name, java.lang.Long::class.java)?.toLong()

    private fun Readable.int(name: String): Int = get(name, java.lang.Integer::class.java)?.toInt() ?: 0
}
