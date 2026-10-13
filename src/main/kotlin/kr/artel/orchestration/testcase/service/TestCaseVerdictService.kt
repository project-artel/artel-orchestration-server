package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.service.QaLogService
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryItem
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryResponse
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service

/**
 * QA 판정을 TC 상태로 옮기고, build 별 판정을 읽는다(ARTEL-994).
 *
 * #293 이후 TC 는 build 마다 다른 행이라, **행의 상태가 곧 그 버전의 판정**이다. 따로 이력 표를 두지 않는다 —
 * 앞 버전의 행이 그 판정을 들고 남고, build 를 가로지르는 조회는 `metadata.case_key` 로 한다.
 */
@Service
class TestCaseVerdictService(
    private val testCases: TestCaseRepository,
    private val scenarios: TestScenarioRepository,
    private val qaRuns: QaRunRepository,
    private val logService: QaLogService,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
) {
    private val logger = LoggerFactory.getLogger(TestCaseVerdictService::class.java)

    /**
     * 검증 스텝 하나의 판정을 [testCaseId] 의 상태로 옮긴다. 통과면 VERIFIED, 실패면 FAILED 다.
     *
     * 실제로 바뀌었으면 `CASE_VERDICT` 행을 qa_log 에 남기고 내보낸다. SSE 가 이 표를 읽으므로 화면은 이 행으로
     * TC 배지를 바꾼다. 케이스가 이 try 의 프로젝트에 없거나 `BROKEN` 이면 아무것도 하지 않는다.
     *
     * **QA 를 멈추지 않는다.** 여기서 난 오류는 로그만 남긴다 — 상태 반영이 실패했다고 실행 중인 시나리오를
     * 끊으면 잃는 것이 더 크다.
     */
    suspend fun recordStepVerdict(qaTry: QaTryEntity, testCaseId: Long, passed: Boolean) {
        val qaTryId = requireNotNull(qaTry.id)
        runCatching {
            val projectId = scenarios.findById(qaTry.testScenarioId)?.projectId ?: return
            val gameBuildId = qaTry.qaRunId?.let { qaRuns.findById(it) }?.gameBuildId
            val status = if (passed) VerificationStatus.VERIFIED else VerificationStatus.FAILED
            if (testCases.recordVerdict(projectId, testCaseId, status.name, gameBuildId) == 0) {
                logger.info("TC {} 는 qa_try {} 의 프로젝트에 없거나 BROKEN 이라 판정을 옮기지 않는다", testCaseId, qaTryId)
                return
            }
            val payload = objectMapper.createObjectNode()
                .put("case_id", testCaseId)
                .put("verification_status", status.name)
            gameBuildId?.let { payload.put("game_build_id", it) }
            logService.publish(
                logService.append(
                    qaTryId = qaTryId,
                    direction = "ORCHE_INTERNAL",
                    type = "CASE_VERDICT",
                    message = "TC $testCaseId → ${status.name}",
                    payload = payload,
                )
            )
        }.onFailure { error ->
            logger.warn("qa_try {} 의 TC {} 판정을 상태로 옮기지 못했다", qaTryId, testCaseId, error)
        }
    }

    /**
     * [testCaseId] 와 같은 케이스의 build 별 판정. 비멤버이거나 다른 프로젝트 케이스면 404 다.
     * `case_key` 가 없는 케이스(사람이 쓴 것)는 build 에 묶이지 않아 빈 목록이다.
     */
    suspend fun history(projectId: Long, testCaseId: Long, userId: Long): CaseVerdictHistoryResponse {
        val testCase = testCases.findById(testCaseId)
        if (testCase == null || testCase.projectId != projectId || !projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("test case 를 찾을 수 없습니다.")
        }
        val items = testCases.findVerdictHistory(projectId, testCaseId).map { row ->
            CaseVerdictHistoryItem(
                testCaseId = row.testCaseId.toString(),
                gameBuildId = row.gameBuildId.toString(),
                version = row.version,
                label = row.label,
                verificationStatus = row.verificationStatus,
            )
        }.toList()
        return CaseVerdictHistoryResponse(testCaseId = testCaseId.toString(), items = items)
    }
}
