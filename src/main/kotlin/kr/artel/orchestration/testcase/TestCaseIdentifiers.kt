package kr.artel.orchestration.testcase

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.slf4j.LoggerFactory
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

data class TestCaseIdentifierResponse(val testCaseId: String, val caseNumber: String)

fun formatTestCaseIdentifier(caseNumber: String?): String =
    caseNumber?.let { "TC-${it.padStart(3, '0')}" } ?: "TC-…"

@Service
class TestCaseIdentifiers(private val database: DatabaseClient, private val access: ProjectAccessService) {
    private val logger = LoggerFactory.getLogger(javaClass)

    /** 판정 호출부가 확인한 프로젝트 안에서 표시 번호만 읽는다. 삭제된 TC 번호도 남아 있다. */
    suspend fun findNumber(projectId: Long, testCaseId: Long): String? = try {
        database.sql("""
            SELECT case_number FROM test_case_identifier
            WHERE project_id = :project AND test_case_id = :case
        """).bind("project", projectId).bind("case", testCaseId)
            .map { row, _ -> row.get("case_number")!!.toString() }.one().awaitSingleOrNull()
    } catch (error: Exception) {
        if (error is CancellationException) throw error
        logger.warn("TC 표시 번호 조회 실패 [projectId={}, testCaseId={}]", projectId, testCaseId, error)
        null
    }

    suspend fun list(projectId: Long, userId: Long): List<TestCaseIdentifierResponse> {
        access.requireMember(projectId, userId)
        return database.sql("""
            SELECT test_case_id, case_number FROM test_case_identifier
            WHERE project_id = :project ORDER BY case_number
        """).bind("project", projectId).map { row, _ ->
            TestCaseIdentifierResponse(row.get("test_case_id")!!.toString(), row.get("case_number")!!.toString())
        }.flow().toList()
    }
}

@RestController
class TestCaseIdentifierController(private val identifiers: TestCaseIdentifiers) {
    @GetMapping("/api/projects/{projectId}/test-case-identifiers")
    suspend fun list(@PathVariable projectId: Long, @CurrentUserId userId: Long) = identifiers.list(projectId, userId)
}
