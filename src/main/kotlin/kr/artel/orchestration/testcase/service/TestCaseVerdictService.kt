package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.service.QaLogService
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryItem
import kr.artel.orchestration.testcase.dto.CaseVerdictHistoryResponse
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
    private val reader: TestCaseReader,
    private val logService: QaLogService,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val projectChangeStreamManager: ProjectChangeStreamManager,
) {
    private val logger = LoggerFactory.getLogger(TestCaseVerdictService::class.java)

    /**
     * 검증 스텝 하나의 판정을 [testCaseId] 의 상태로 옮긴다. 통과면 VERIFIED, 실패면 FAILED 다. [verdictLogId] 는
     * 그 판정을 실어 온 STATUS 행이고, 판정 근거를 거기서 읽는다(ARTEL-1134).
     *
     * 실제로 바뀌었으면 `CASE_VERDICT` 행을 qa_log 에 남기고 내보낸다. SSE 가 이 표를 읽으므로 화면은 이 행으로
     * TC 배지를 바꾼다. 케이스가 이 try 의 프로젝트에 없거나 `BROKEN` 이면 아무것도 하지 않는다. BROKEN 은 그 게임 빌드의 몫으로 본다.
     *
     * 판정은 **그 게임 빌드에 든 케이스에만** 적는다. 그 빌드에 버전 몫이 없는 케이스, 게임 빌드를 정할 수 없는
     * 실행은 TC 에 아무것도 남기지 않는다 — 스텝 결과는 qa_log 에 이미 있다. editor 실행은 그 editor 버전에 TC 가
     * 있으면 그 버전의, 없으면 같은 version 의 게임 빌드의 판정이다([judgedBuildId]).
     *
     * **QA 를 멈추지 않는다.** 여기서 난 오류는 로그만 남긴다 — 상태 반영이 실패했다고 실행 중인 시나리오를
     * 끊으면 잃는 것이 더 크다.
     */
    suspend fun recordStepVerdict(qaTry: QaTryEntity, testCaseId: Long, passed: Boolean?, verdictLogId: Long? = null, publish: Boolean = true) {
        val qaTryId = requireNotNull(qaTry.id)
        runCatching {
            val projectId = scenarios.findById(qaTry.testScenarioId)?.projectId ?: return
            val status = when (passed) { true -> VerificationStatus.VERIFIED; false -> VerificationStatus.FAILED; null -> VerificationStatus.DRAFT }
            if (testCases.findById(testCaseId)?.projectId != projectId) {
                logger.info("TC {} 는 qa_try {} 의 프로젝트에 없어 판정을 옮기지 않는다", testCaseId, qaTryId)
                return
            }
            val gameBuildId = judgedBuildId(qaTry)
            val version = gameBuildId?.let { versions.findByTestCaseIdAndGameBuildId(testCaseId, it) }
            if (gameBuildId == null || version == null) {
                logger.info("TC {} 는 qa_try {} 의 게임 빌드 {} 에 없어 판정을 옮기지 않는다", testCaseId, qaTryId, gameBuildId)
                return
            }
            // 깨졌는지는 그 버전의 몫으로 본다(ARTEL-1069). 최신 버전에서 깨졌어도 이 버전에선 멀쩡할 수 있다.
            if (version.verificationStatus == VerificationStatus.BROKEN.name) {
                logger.info("TC {} 는 게임 빌드 {} 에서 BROKEN 이라 판정을 옮기지 않는다", testCaseId, gameBuildId)
                return
            }
            // 이 버전에서 직접 본 판정이다. 앞 버전에서 이어받았던 표시는 지운다(ARTEL-1180).
            versions.save(
                version.copy(
                    verificationStatus = status.name,
                    verifiedAt = Instant.now(clock),
                    verdictQaLogId = verdictLogId,
                    verdictCarriedFromBuildId = null,
                )
            )
            testCases.markLastVerifiedBuild(testCaseId, gameBuildId)
            versions.syncIdentities(listOf(testCaseId))
            if (publish) projectChangeStreamManager.notify(projectId, ProjectChangeTopic.TEST_CASES)
            val payload = objectMapper.createObjectNode()
                .put("case_id", testCaseId)
                .put("verification_status", status.name)
                .put("game_build_id", gameBuildId)
            verdictLogId?.let { payload.put("verdict_log_id", it) }
            val appended = logService.append(
                    qaTryId = qaTryId,
                    direction = "ORCHE_INTERNAL",
                    type = "CASE_VERDICT",
                    message = "TC $testCaseId → ${status.name}",
                    payload = payload,
                )
            if (publish) logService.publish(appended)
        }.onFailure { error ->
            if (!publish || error is kotlinx.coroutines.CancellationException) throw error
            logger.warn("qa_try {} 의 TC {} 판정을 상태로 옮기지 못했다", qaTryId, testCaseId, error)
        }
    }

    /** 이 실행의 판정이 속할 버전. 규칙은 [TestCaseReader.judgedBuildId] 에 있다. */
    private suspend fun judgedBuildId(qaTry: QaTryEntity): Long? =
        reader.judgedBuildId(qaTry.qaRunId?.let { qaRuns.findById(it) }?.gameBuildId)

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
                qaRunId = row.verdictQaRunId?.toString(),
                qaTryId = row.verdictQaTryId?.toString(),
                verdictLogId = row.verdictQaLogId?.toString(),
                isEditor = row.isEditor,
                verdictCarriedFromBuildId = row.verdictCarriedFromBuildId?.toString(),
            )
        }.toList()
        return CaseVerdictHistoryResponse(testCaseId = testCaseId.toString(), items = items)
    }
}
