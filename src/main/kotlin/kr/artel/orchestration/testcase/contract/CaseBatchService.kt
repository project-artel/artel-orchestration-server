package kr.artel.orchestration.testcase.contract

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.stereotype.Service
import kotlinx.coroutines.reactor.awaitSingleOrNull

/**
 * TC 를 계약까지 한 번에 넣는다(MCP 의 `save_test_cases`). 만들기 · 고치기는 화면과 같은 길([TestCaseService])을 타고,
 * 계약 칸이 있으면 그 뒤에 출처 `user` 로 덮는다. 한 줄이 틀려도 나머지는 들어간다 — 틀린 줄은 이유와 함께 돌려준다.
 */
@Service
class CaseBatchService(
    private val testCaseService: TestCaseService,
    private val caseContracts: CaseContractService,
    private val projectAccess: ProjectAccessService,
    private val databaseClient: DatabaseClient,
) {
    suspend fun save(projectId: Long, userId: Long, request: CaseBatchRequest): CaseBatchResponse {
        if (!projectAccess.isMember(projectId, userId)) throw NotFoundException("프로젝트를 찾을 수 없습니다.")
        if (request.cases.size > MAX_CASES) throw BadRequestException("한 번에 ${MAX_CASES}건까지 넣을 수 있습니다.")
        val addedTerms = caseContracts.mergeTerms(projectId, request.terms)
        val buildId = request.gameBuildId?.ifBlank { null } ?: latestBuild(projectId)?.toString()
        val created = mutableListOf<String>()
        val updated = mutableListOf<String>()
        val rejected = mutableListOf<CaseBatchRejection>()
        request.cases.forEachIndexed { index, item ->
            runCatching {
                val needs = item.needs.orEmpty().map { parseNeed(it) }
                val leaves = item.leaves.orEmpty().map { parseLeave(it) }
                val caseId = if (item.testCaseId.isNullOrBlank()) {
                    val response = testCaseService.createTestCase(
                        projectId, userId,
                        TestCaseCreateRequest(item.scene, item.step, item.precondition, item.expectedValue, gameBuildId = buildId),
                    ) ?: throw NotFoundException("프로젝트를 찾을 수 없습니다.")
                    response.id.also { created += it }
                } else {
                    // 다른 프로젝트의 TC 는 고치기 전에 거른다 — 고친 뒤에 거르면 이미 바뀐 뒤다.
                    if (projectOf(item.testCaseId.toLong()) != projectId) throw NotFoundException("TC ${item.testCaseId} 를 찾을 수 없습니다.")
                    val response = testCaseService.updateTestCase(
                        item.testCaseId.toLong(), userId,
                        TestCaseUpdateRequest(scene = item.scene, step = item.step, precondition = item.precondition, expectedValue = item.expectedValue),
                    )?.takeIf { it.projectId == projectId.toString() } ?: throw NotFoundException("TC ${item.testCaseId} 를 찾을 수 없습니다.")
                    response.id.also { updated += it }
                }
                caseContracts.writeUserContract(projectId, caseId.toLong(), needs, leaves, item.arrivesAt)
            }.onFailure { rejected += CaseBatchRejection(index, it.message ?: it::class.simpleName.orEmpty()) }
        }
        return CaseBatchResponse(created, updated, rejected, addedTerms)
    }

    /** `검 == 1` · `동전 >= 30` · `낮밤 = 밤`. `=` 하나는 `==` 로 읽는다. */
    private fun parseNeed(text: String): Triple<String, String, String> {
        val m = NEED.matchEntire(text.trim()) ?: throw BadRequestException("필요한 것은 `이름 == 값` 꼴이어야 합니다: $text")
        val op = m.groupValues[2].let { if (it == "=") "==" else it }
        return Triple(caseContracts.contractName(m.groupValues[1]), op, caseContracts.contractName(m.groupValues[3]))
    }

    /** `등불=1` · `동전=+20`. */
    private fun parseLeave(text: String): String {
        val m = LEAVE.matchEntire(text.trim()) ?: throw BadRequestException("남기는 것은 `이름=값` 꼴이어야 합니다: $text")
        return "${caseContracts.contractName(m.groupValues[1])}=${caseContracts.contractName(m.groupValues[2])}"
    }

    private suspend fun projectOf(testCaseId: Long): Long? =
        databaseClient.sql("SELECT project_id FROM test_case WHERE id = :id")
            .bind("id", testCaseId).map { r, _ -> r.get("project_id", java.lang.Long::class.java)?.toLong() }.one().awaitSingleOrNull()

    private suspend fun latestBuild(projectId: Long): Long? =
        databaseClient.sql("SELECT id FROM game_build WHERE project_id = :p AND NOT is_editor ORDER BY created_at DESC, id DESC LIMIT 1")
            .bind("p", projectId).map { r, _ -> r.get("id", java.lang.Long::class.java)?.toLong() }.one().awaitSingleOrNull()

    private companion object {
        const val MAX_CASES = 50
        val NEED = Regex("""(.+?)\s*(==|!=|>=|<=|>|<|=)\s*(.+)""")
        val LEAVE = Regex("""(.+?)\s*=\s*(.+)""")
    }
}
