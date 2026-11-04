package kr.artel.orchestration.testrun.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.testrun.dto.TestRunCopyRequest
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunResponse
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import org.springframework.stereotype.Service

/**
 * TR 복제(ARTEL-1377). 새 TR 을 만들고, 원본의 시나리오를 원래 순서대로 **복사본**으로 붙인다. 원본 TR · TS 는 그대로다.
 *
 * 만들기는 [TestRunService.create], 붙이기는 [ScenarioLibraryService.importIntoRun] 을 그대로 쓴다 — 버전 · 폴더 고르기와
 * 복사본의 `case_id` 맞추기가 이미 거기 있다. 붙이다 실패하면 만든 TR 을 지워, 시나리오가 빠진 사본이 남지 않게 한다.
 */
@Service
class TestRunCopyService(
    private val runService: TestRunService,
    private val libraryService: ScenarioLibraryService,
    private val runScenarioRepository: TestRunScenarioRepository,
) {
    suspend fun copy(projectId: Long, testRunId: Long, userId: Long, request: TestRunCopyRequest): TestRunResponse {
        val source = runService.get(testRunId, userId)?.takeIf { it.projectId == projectId.toString() }
            ?: throw NotFoundException("TR 을 찾을 수 없습니다.")
        val created = runService.create(projectId, userId, placement(source, request))
            ?: throw NotFoundException("TR 을 찾을 수 없습니다.")
        val createdId = created.id.toLong()
        val scenarioIds = runScenarioRepository.findByTestRunIdOrderByPosition(testRunId).toList().map { it.testScenarioId.toString() }
        if (scenarioIds.isNotEmpty()) {
            try {
                libraryService.importIntoRun(projectId, createdId, userId, scenarioIds)
            } catch (error: Throwable) {
                runService.delete(createdId, userId, dropScenarios = true)
                throw error
            }
        }
        return runService.get(createdId, userId)?.copy(scenarioCount = scenarioIds.size)
            ?: throw NotFoundException("TR 을 찾을 수 없습니다.")
    }

    private fun placement(source: TestRunResponse, request: TestRunCopyRequest): TestRunCreateRequest {
        val name = "${source.name} 사본"
        return when {
            request.folderId != null -> TestRunCreateRequest(name, source.description, folderId = request.folderId)
            request.unversioned -> TestRunCreateRequest(name, source.description, unversioned = true)
            request.gameBuildId != null -> TestRunCreateRequest(name, source.description, gameBuildId = request.gameBuildId)
            source.folderId != null -> TestRunCreateRequest(name, source.description, folderId = source.folderId)
            source.gameBuild != null -> TestRunCreateRequest(name, source.description, gameBuildId = source.gameBuild.gameBuildId)
            else -> TestRunCreateRequest(name, source.description, unversioned = true)
        }
    }
}
