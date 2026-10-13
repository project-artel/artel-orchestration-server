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
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * QA 판정을 TC 상태로 옮기고, build 별 판정을 읽는다(ARTEL-994).
 *
 * 케이스의 정체(`test_case`)는 버전을 넘어 하나이고, 판정은 버전 몫(`test_case_version`)에 남는다(ARTEL-1018).
 * 앞 버전의 몫이 그 버전의 판정을 들고 남는다.
 */
@Service
class TestCaseVerdictService(
    private val testCases: TestCaseRepository,
    private val versions: TestCaseVersionRepository,
    private val scenarios: TestScenarioRepository,
    private val qaRuns: QaRunRepository,
    private val logService: QaLogService,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
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
            val testCase = testCases.findById(testCaseId)
                ?.takeIf { it.projectId == projectId && it.verificationStatus != VerificationStatus.BROKEN.name }
            if (testCase == null) {
                logger.info("TC {} 는 qa_try {} 의 프로젝트에 없거나 BROKEN 이라 판정을 옮기지 않는다", testCaseId, qaTryId)
                return
            }
            if (gameBuildId == null) {
                // build 를 모르는 실행(인스턴스가 SDK 등록을 한 적 없음)은 어느 버전 몫에도 적을 수 없다.
                testCases.recordVerdict(projectId, testCaseId, status.name, null)
            } else {
                recordOnVersion(testCase, gameBuildId, status)
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
     * 판정을 그 build 의 버전 몫에 적는다(ARTEL-1018). 몫이 없으면 정체의 지금 내용으로 만든다 — 사람이 쓴
     * 케이스는 지도가 몫을 만들지 않으므로 판정이 처음 올 때 생기고, 지도 케이스도 그 버전 지도에 없지만
     * 시나리오가 그 버전에서 돌렸다면 판정은 실제로 난 것이라 버리지 않는다.
     * 정체에는 가장 최근 버전을 비춘다. 그래서 앞 버전을 다시 돌린 판정은 정체 상태를 바꾸지 않는다.
     */
    private suspend fun recordOnVersion(testCase: TestCaseEntity, gameBuildId: Long, status: VerificationStatus) {
        val testCaseId = requireNotNull(testCase.id)
        val version = versions.findByTestCaseIdAndGameBuildId(testCaseId, gameBuildId)
            ?: TestCaseVersionEntity.snapshotOf(testCase, gameBuildId)
        versions.save(version.copy(verificationStatus = status.name, verifiedAt = Instant.now(clock)))
        testCases.markLastVerifiedBuild(testCaseId, gameBuildId)
        versions.syncIdentities(listOf(testCaseId))
    }

    /**
     * [testCaseId] 의 build 별 판정. 비멤버이거나 다른 프로젝트 케이스면 404 다.
     * 버전 몫이 없는 케이스(판정을 받은 적 없는 사람이 쓴 것 등)는 빈 목록이다.
     */
    suspend fun history(projectId: Long, testCaseId: Long, userId: Long): CaseVerdictHistoryResponse {
        val testCase = testCases.findById(testCaseId)
        if (testCase == null || testCase.projectId != projectId || !projectAccessService.isMember(projectId, userId)) {
            throw NotFoundException("test case 를 찾을 수 없습니다.")
        }
        val items = versions.findHistory(projectId, testCaseId).map { row ->
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
