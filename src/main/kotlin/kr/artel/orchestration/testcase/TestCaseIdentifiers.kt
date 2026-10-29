package kr.artel.orchestration.testcase

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.project.service.ProjectAccessService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.flow
import org.springframework.stereotype.Service
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RestController

data class TestCaseIdentifierResponse(val testCaseId: String, val caseNumber: String)

@Service
class TestCaseIdentifiers(private val database: DatabaseClient, private val access: ProjectAccessService) {
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
