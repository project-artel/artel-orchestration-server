package kr.artel.orchestration.qa

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import org.springframework.r2dbc.core.awaitRowsUpdated
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.qa.entity.QaLogEntity
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.qa.repository.QaLogRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.repository.QaTryScoreRepository
import kr.artel.orchestration.qa.service.QaAgentEnvelope
import kr.artel.orchestration.qa.service.QaAgentInboundRouter
import kr.artel.orchestration.qa.service.QaTryPersistenceService
import kr.artel.orchestration.qa.service.QaVerdictService
import kr.artel.orchestration.qa.review.QaVerdictReviewService
import kr.artel.orchestration.qa.review.*
import kr.artel.orchestration.qa.service.QaLaunchReadService
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.testcase.service.TestCaseVerdictService
import org.assertj.core.api.Assertions.assertThatThrownBy
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.AfterEach
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.UUID

/**
 * QA 검증 스텝의 판정이 TC 상태로 옮겨지는지(ARTEL-994).
 *
 * agent 는 스텝마다 `case_id` 와 `is_verification` 을 싣는다. 같은 `case_id` 구간의 마지막 스텝만 검증
 * 스텝이고, 그 스텝의 COMPLETED/FAILED 가 그 TC 의 판정이다. 여기서 지키는 것은 넷이다: 검증 스텝만 옮긴다,
 * 판정이 어느 build 것인지 남긴다, `BROKEN` 은 건드리지 않는다, 재전송은 두 번 세지 않는다. 판정은 그 게임 빌드에
 * 든 케이스에만 적고, editor 실행은 같은 버전의 게임 빌드 판정이다(ARTEL-1014).
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class CaseVerdictFromQaIntegrationTest {

    @Autowired private lateinit var router: QaAgentInboundRouter
    @Autowired private lateinit var persistence: QaTryPersistenceService
    @Autowired private lateinit var qaTryRepository: QaTryRepository
    @Autowired private lateinit var qaRunRepository: QaRunRepository
    @Autowired private lateinit var qaLogRepository: QaLogRepository
    @Autowired private lateinit var scoreRepository: QaTryScoreRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var testRunRepository: TestRunRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository
    @Autowired private lateinit var gameInstanceRepository: GameInstanceRepository
    @Autowired private lateinit var gameBuildRepository: GameBuildRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var qaVerdictService: QaVerdictService
    @Autowired private lateinit var issueRepository: IssueRepository
    @Autowired private lateinit var caseVerdictService: TestCaseVerdictService
    @Autowired private lateinit var reviewService: QaVerdictReviewService
    @Autowired private lateinit var launchReadService: QaLaunchReadService
    @Autowired private lateinit var composition: ScenarioCompositionService
    @Autowired private lateinit var attempts: QaCaseAttemptService
    @Autowired private lateinit var reliability: QaReliabilityService
    @Autowired private lateinit var questions: QaOperatorQuestions
    @Autowired private lateinit var contentReviews: QaCaseContentReviews
    @Autowired private lateinit var caseService: kr.artel.orchestration.testcase.service.TestCaseService
    @Autowired private lateinit var database: org.springframework.r2dbc.core.DatabaseClient

    @Test
    fun `TC 내용 문제와 판단 불가는 legacy 응답의 통과 판정을 실패로 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)
        val logId = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single().verdicts.single().logId.toLong()

        for (decision in listOf("TC_CONTENT_ISSUE", "INCONCLUSIVE")) {
            val saved = reviewService.decideOutcome(run.qaRunId, logId, run.ownerId, decision, "기준을 확인해 주세요")
            // 직접 선택한 평가도 추가 검토 건수에 포함되는지 확인한다.
            database.sql("UPDATE qa_verdict_review SET flagged = TRUE WHERE qa_log_id = :logId")
                .bind("logId", logId).fetch().awaitRowsUpdated()
            val listed = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single()
            val verdict = listed.verdicts.single()
            assertThat(verdict.passed).isTrue()
            assertThat(verdict.humanDecision).isNull()
            assertThat(verdict.needsReview).isFalse()
            assertThat(qaVerdictService.decisionOf(saved)).isNull()
            assertThat(listed.reviewedCount).isEqualTo(1)
            assertThat(listed.reviewCount).isZero()
            assertThat(versionOf(run.passedCaseId).verificationStatus).isEqualTo("VERIFIED")
        }
    }

    @Test
    fun `TC 내용 문제는 원본 판정과 검증 상태를 보존하고 수정 후 새 검증으로 완료된다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.FAILED, "게임 결과"))
        val before = testCaseRepository.findById(run.failedCaseId)!!
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "기대 결과가 잘못됨")
        val reported = contentReviews.list(run.projectId, run.ownerId, run.failedCaseId).single()
        assertThat(reported.state).isEqualTo("NEEDS_REVISION")
        assertThat(reported.note).isEqualTo("기대 결과가 잘못됨")
        assertThat(reported.logId).isEqualTo(result.logId)
        assertThat(reported.attemptId).isEqualTo(attempt.id.toString())
        assertThat(reported.qaTryId).isEqualTo(run.tryId.toString())
        assertThat(reported.qaRunId).isEqualTo(run.qaRunId.toString())
        assertThat(testCaseRepository.findById(run.failedCaseId)!!.verificationStatus).isEqualTo(before.verificationStatus)
        assertThat(attempts.get(run.projectId, run.ownerId, attempt.id).verdict).isEqualTo("FAILED")
        assertThat(issueRepository.findAll().toList().single().status).isEqualTo("RESOLVED")
        val counts = reliability.report(run.projectId, run.ownerId, null, null).overall
        assertThat(counts.reviewed).isEqualTo(1)
        assertThat(counts.falsePositiveRate).isNull()
        assertThat(counts.falseNegativeRate).isNull()
        assertThat(reliability.history(run.projectId, result.logId.toLong(), run.ownerId, null).single().decision).isEqualTo("TC_CONTENT_ISSUE")
        testCaseRepository.save(before.copy(verificationStatus = "DRAFT"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVISION")
        testCaseRepository.save(testCaseRepository.findById(run.failedCaseId)!!.copy(expectedValue = "수정한 기대 결과"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
        val fresh = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
        attempts.report(run.tryId, fresh.id, ReportCaseAttempt(CaseOutcome.PASSED, "수정 후 결과"))
        val done = contentReviews.list(run.projectId, run.ownerId, null).single()
        assertThat(done.state).isEqualTo("READY")
        assertThat(done.resolvedAt).isNotNull()
        assertThat(done.note).isEqualTo(reported.note)
        assertThat(done.attemptId).isEqualTo(reported.attemptId)
    }

    @Test
    fun `과거 내용의 평가는 현재 TC를 수정 필요로 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "통과"))
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(step = "새 절차"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "옛 내용의 문제")
        assertThat(contentReviews.list(run.projectId, run.ownerId, null)).isEmpty()
        assertThat(reliability.history(run.projectId, result.logId.toLong(), run.ownerId, null).single().note).isEqualTo("옛 내용의 문제")
    }

    @Test
    fun `수정 전 시작과 잘못된 snapshot 및 판단 불가 결과는 재검증을 완료하지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val reported = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, reported.id, ReportCaseAttempt(CaseOutcome.PASSED, "통과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", null)
        val early = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(step = "수정 절차"))
        attempts.report(run.tryId, early.id, ReportCaseAttempt(CaseOutcome.PASSED, "수정 전 시작"))
        val mismatch = objectMapper.readTree("""{"id":${run.passedCaseId},"scene":"S","test_step":"오래된 절차","expected":"x"}""")
        val stale = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId, mismatch))
        attempts.report(run.tryId, stale.id, ReportCaseAttempt(CaseOutcome.FAILED, "snapshot 불일치"))
        val uncertain = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        attempts.report(run.tryId, uncertain.id, ReportCaseAttempt(CaseOutcome.INCONCLUSIVE, "판단 불가"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
        val snapshot = objectMapper.readTree("""{"id":${run.passedCaseId},"scene":"S","precondition":null,"test_step":"수정 절차","expected":"x","functions":{"cheats":"all","behaviors":"all"},"extra":"비교 제외"}""")
        val fresh = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId, snapshot))
        attempts.report(run.tryId, fresh.id, ReportCaseAttempt(CaseOutcome.FAILED, "새 내용으로 확인한 실패"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("READY")
    }

    @Test
    fun `현재 보고만 철회할 수 있고 내용 수정 뒤에는 평가 철회로 완료하지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        suspend fun result(): CaseAttemptResponse {
            val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
            return attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        }
        val first = result()
        val second = result()
        reviewService.decideOutcome(run.qaRunId, first.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "처음")
        reviewService.decideOutcome(run.qaRunId, second.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "최신")
        reviewService.decideOutcome(run.qaRunId, first.logId.toLong(), run.ownerId, "PASSED", null)
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().note).isEqualTo("최신")
        reviewService.decideOutcome(run.qaRunId, second.logId.toLong(), run.ownerId, "PASSED", null)
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("READY")
        reviewService.decideOutcome(run.qaRunId, second.logId.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "다시 보고")
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(step = "수정됨"))
        reviewService.decideOutcome(run.qaRunId, second.logId.toLong(), run.ownerId, "PASSED", null)
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
    }

    @Test
    fun `내용 검토 조회는 프로젝트 멤버만 가능하고 삭제 TC는 상태를 다시 변경하지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "사유")
        assertThat(contentReviews.list(run.projectId, run.ownerId, run.failedCaseId)).isEmpty()
        assertThatThrownBy { runBlocking { contentReviews.list(run.projectId, run.ownerId + 999, null) } }.isInstanceOf(NotFoundException::class.java)
        testCaseRepository.deleteById(run.passedCaseId)
        reviewService.decideOutcome(run.qaRunId, result.logId.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "삭제 후 사유")
        assertThat(contentReviews.list(run.projectId, run.ownerId, null)).isEmpty()
        assertThat(reliability.history(run.projectId, result.logId.toLong(), run.ownerId, null)).hasSize(2)
    }

    @Test
    fun `동시 재검증 완료와 내용 수정은 새 수정의 재검증 대기를 보존한다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "사유")
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(step = "첫 수정"))
        val fresh = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        coroutineScope { listOf(async {
            attempts.report(run.tryId, fresh.id, ReportCaseAttempt(CaseOutcome.PASSED, "새 결과"))
        }, async {
            testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(step = "동시 새 수정"))
        }).awaitAll() }
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
    }

    @Test
    fun `사람이 수정한 TC 내용은 build scope에서도 agent 재검증에 전달된다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "기준 수정")
        caseService.updateTestCase(run.passedCaseId, run.ownerId,
            kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest(step = "수정 절차", expectedValue = "수정 기대 결과"))
        val draft = ScenarioDraft(title = "재검증", description = "", steps = listOf(ScenarioStep(action = "", caseId = run.passedCaseId)))
        val agentCase = composition.agentScenario(draft,
            kr.artel.orchestration.testcase.service.CaseScope.Version(run.gameBuildId), run.gameBuildId).steps.single().case!!
        assertThat(agentCase.testStep).isEqualTo("수정 절차")
        assertThat(agentCase.expected).isEqualTo("수정 기대 결과")
        assertThat(agentCase.reviews!!.single().humanPassed).isNull()
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
    }

    @Test
    fun `구조 조건과 기대 항목 및 functions 변경도 재검증 시각을 갱신한다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", null)
        var row = testCaseRepository.findById(run.passedCaseId)!!
        for (changed in listOf(row.copy(condition = Json.of("{}")), row.copy(expectedItems = Json.of("[]")),
            row.copy(functions = Json.of("""{"cheats":"none"}""")), row.copy(precondition = "새 조건"))) {
            row = testCaseRepository.save(changed)
            assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
            assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().revisedAt).isNotNull()
        }
        val legacy = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        database.sql("UPDATE qa_case_attempt SET started_at = NULL WHERE id = :id").bind("id", legacy.id).fetch().awaitRowsUpdated()
        attempts.report(run.tryId, legacy.id, ReportCaseAttempt(CaseOutcome.PASSED, "시작시각 없음"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
        val interrupted = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        database.sql("UPDATE qa_try SET status = 'CANCELLED', completed_at = now() WHERE id = :id").bind("id", run.tryId).fetch().awaitRowsUpdated()
        assertThat(attempts.get(run.projectId, run.ownerId, interrupted.id).verdict).isEqualTo("INTERRUPTED")
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
    }

    @Test
    fun `지도 TC는 지정한 build 내용만 수정하고 다른 build와 판정을 보존한다`(): Unit = runBlocking {
        val run = runningRun()
        val metadata = Json.of("""{"origin":"content-map"}""")
        val mapped = testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(metadata = metadata))
        val current = versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!
        versions.save(current.copy(metadata = metadata))
        val older = gameBuildRepository.save(GameBuildEntity(projectId = run.projectId, version = "0.9.0",
            createdAt = Instant.now().minusSeconds(3600), updatedAt = Instant.now()))
        versions.save(TestCaseVersionEntity.snapshotOf(mapped, older.id!!).copy(step = "옛 버전 절차", verificationStatus = "VERIFIED"))
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "결과"))
        reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "TC_CONTENT_ISSUE", "기대 결과 수정")
        val saved = caseService.updateTestCase(run.passedCaseId, run.ownerId,
            kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest(gameBuildId = run.gameBuildId.toString(), step = "새 버전 절차", expectedValue = "새 기대 결과"))!!
        assertThat(saved.step).isEqualTo("새 버전 절차")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, older.id)!!.step).isEqualTo("옛 버전 절차")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, older.id)!!.verificationStatus).isEqualTo("VERIFIED")
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("NEEDS_REVALIDATION")
        val draft = ScenarioDraft(title = "지도 재검증", description = "", steps = listOf(ScenarioStep(action = "", caseId = run.passedCaseId)))
        val agentCase = composition.agentScenario(draft, kr.artel.orchestration.testcase.service.CaseScope.Version(run.gameBuildId), run.gameBuildId).steps.single().case!!
        assertThat(agentCase.testStep).isEqualTo("새 버전 절차")
        assertThat(agentCase.expected).isEqualTo("새 기대 결과")
        val fresh = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId, objectMapper.valueToTree(agentCase)))
        attempts.report(run.tryId, fresh.id, ReportCaseAttempt(CaseOutcome.PASSED, "수정 후 검증"))
        assertThat(contentReviews.list(run.projectId, run.ownerId, null).single().state).isEqualTo("READY")
        assertThatThrownBy { runBlocking { caseService.updateTestCase(run.passedCaseId, run.ownerId,
            kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest(gameBuildId = (run.gameBuildId + 999).toString(), step = "변경 금지")) } }.isInstanceOf(NotFoundException::class.java)
        val empty = gameBuildRepository.save(GameBuildEntity(projectId = run.projectId, version = "2.0.0", createdAt = Instant.now(), updatedAt = Instant.now()))
        assertThatThrownBy { runBlocking { caseService.updateTestCase(run.passedCaseId, run.ownerId,
            kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest(gameBuildId = empty.id.toString(), step = "변경 금지")) } }.isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `TC 시도별 평가와 오탐 정정이 원래 issue에 반영된다`(): Unit = runBlocking {
        val run = runningRun()
        val first = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        val failed = attempts.report(run.tryId, first.id, ReportCaseAttempt(CaseOutcome.FAILED, "실제 결과 확인"))
        attempts.report(run.tryId, first.id, ReportCaseAttempt(CaseOutcome.FAILED, "실제 결과 확인"))
        val issue = issueRepository.findAll().toList().single()
        val second = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        val repeated = attempts.report(run.tryId, second.id, ReportCaseAttempt(CaseOutcome.FAILED, "재검증 실패"))
        assertThat(issueRepository.findAll().toList()).hasSize(1)
        reviewService.decideOutcome(run.qaRunId, failed.logId!!.toLong(), run.ownerId, "PASSED", "오탐")
        assertThat(issueRepository.findById(issue.id!!)!!.status).isEqualTo("OPEN")
        reviewService.decideOutcome(run.qaRunId, repeated.logId!!.toLong(), run.ownerId, "PASSED", null)
        assertThat(issueRepository.findById(issue.id)!!.status).isEqualTo("RESOLVED")
        reviewService.decideOutcome(run.qaRunId, failed.logId.toLong(), run.ownerId, "FAILED", "평가 수정")
        assertThat(issueRepository.findById(issue.id)!!.status).isEqualTo("OPEN")
        assertThat(reliability.history(run.projectId, failed.logId.toLong(), run.ownerId, null)).hasSize(2)
        val counts = reliability.report(run.projectId, run.ownerId, null, null).overall
        assertThat(counts.total).isEqualTo(2)
        assertThat(counts.truePositives).isEqualTo(1)
        assertThat(counts.falsePositives).isEqualTo(1)
    }

    @Test
    fun `첫 사용자 답변만 채택하고 질문을 재전송해도 중복 생성하지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val request = AskOperatorRequest(UUID.randomUUID(), "어느 화면으로 이동할까요?")
        questions.ask(run.tryId, request)
        questions.ask(run.tryId, request)
        assertThat(questions.list(run.projectId, run.ownerId)).hasSize(1)
        questions.answer(run.projectId, request.id, run.ownerId, AnswerOperatorRequest("설정 화면"))
        assertThatThrownBy { runBlocking {
            questions.answer(run.projectId, request.id, run.ownerId, AnswerOperatorRequest("다른 답변"))
        } }.isInstanceOf(kr.artel.orchestration.common.error.ConflictException::class.java)
        assertThat(questions.get(run.tryId, request.id)!!.answer).isEqualTo("설정 화면")
    }

    @Test
    fun `사람 평가를 판단 불가로 수정하면 원래 실패 판정으로 돌아간다`(): Unit = runBlocking {
        val run = runningRun()
        val started = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        val failed = attempts.report(run.tryId, started.id, ReportCaseAttempt(CaseOutcome.FAILED, "실패"))
        reviewService.decideOutcome(run.qaRunId, failed.logId!!.toLong(), run.ownerId, "PASSED", null)
        assertThat(testCaseRepository.findById(run.failedCaseId)!!.verificationStatus).isEqualTo("VERIFIED")
        reviewService.decideOutcome(run.qaRunId, failed.logId.toLong(), run.ownerId, "INCONCLUSIVE", null)
        assertThat(testCaseRepository.findById(run.failedCaseId)!!.verificationStatus).isEqualTo("FAILED")
        assertThat(issueRepository.findAll().toList().single().status).isEqualTo("OPEN")
    }

    @Test
    fun `TC를 삭제해도 시도와 issue를 보존하고 평가를 수정할 수 있다`(): Unit = runBlocking {
        val run = runningRun()
        val started = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.failedCaseId))
        val failed = attempts.report(run.tryId, started.id, ReportCaseAttempt(CaseOutcome.FAILED, "실패"))
        reviewService.decideOutcome(run.qaRunId, failed.logId!!.toLong(), run.ownerId, "FAILED", null)
        testCaseRepository.deleteById(run.failedCaseId)
        assertThat(attempts.get(run.projectId, run.ownerId, started.id).testCaseId).isEqualTo(run.failedCaseId.toString())
        assertThat(issueRepository.findAll().toList()).hasSize(1)
        reviewService.decideOutcome(run.qaRunId, failed.logId.toLong(), run.ownerId, "PASSED", "삭제 이후 평가 수정")
        assertThat(reliability.history(run.projectId, failed.logId.toLong(), run.ownerId, null)).hasSize(2)
        assertThatThrownBy { runBlocking { attempts.get(run.projectId, run.ownerId + 999, started.id) } }
            .isInstanceOf(kr.artel.orchestration.common.error.NotFoundException::class.java)
    }

    @Test
    fun `삭제된 TC의 동시 미탐 평가도 하나의 issue에 모인다`(): Unit = runBlocking {
        val run = runningRun()
        val results = (1..2).map {
            val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
            attempts.report(run.tryId, attempt.id, ReportCaseAttempt(CaseOutcome.PASSED, "통과"))
        }
        testCaseRepository.deleteById(run.passedCaseId)
        coroutineScope { results.map { result -> async {
            reviewService.decideOutcome(run.qaRunId, result.logId!!.toLong(), run.ownerId, "FAILED", "미탐")
        } }.awaitAll() }
        assertThat(issueRepository.findAll().toList()).hasSize(1)
        assertThat(reliability.report(run.projectId, run.ownerId, null, null).overall.falseNegatives).isEqualTo(2)
    }

    @BeforeEach
    fun cleanBefore(): Unit = runBlocking { wipe() }

    @AfterEach
    fun cleanAfter(): Unit = runBlocking { wipe() }

    private suspend fun wipe() {
        issueRepository.deleteAll()
        scoreRepository.deleteAll()
        qaLogRepository.deleteAll()
        qaTryRepository.deleteAll()
        qaRunRepository.deleteAll()
        gameInstanceRepository.deleteAll()
        testRunRepository.deleteAll()
        scenarioRepository.deleteAll()
        versions.deleteAll()
        testCaseRepository.deleteAll()
        gameBuildRepository.deleteAll()
        projectMemberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `검증 스텝의 통과와 실패가 TC 상태와 판정 build 로 남는다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = false)
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)

        val passed = testCaseRepository.findById(run.passedCaseId)!!
        assertThat(passed.verificationStatus).isEqualTo("VERIFIED")
        assertThat(passed.lastVerifiedBuildId).isEqualTo(run.gameBuildId)
        val failed = testCaseRepository.findById(run.failedCaseId)!!
        assertThat(failed.verificationStatus).isEqualTo("FAILED")
        assertThat(failed.lastVerifiedBuildId).isEqualTo(run.gameBuildId)

        // 판정은 그 build 의 버전 몫에 적히고(ARTEL-1018), 정체는 그 몫을 비춘다.
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.failedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("FAILED")

        val verdicts = caseVerdictLogs(run.tryId)
        assertThat(verdicts.map { it.path("case_id").asLong() to it.path("verification_status").asText() })
            .containsExactly(run.passedCaseId to "VERIFIED", run.failedCaseId to "FAILED")
        assertThat(verdicts).allSatisfy { assertThat(it.path("game_build_id").asLong()).isEqualTo(run.gameBuildId) }
    }

    @Test
    fun `판정 근거가 TC 판정에 이어지고 런의 판정 목록으로 읽힌다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = true)
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true) { payload ->
            payload.put("reason", "Stage 글자가 떴다").put("evidence", "verified")
            payload.putArray("checks").addObject()
                .put("expected", "Stage 글자").put("observed", "Stage : 0").put("met", "yes")
                .putArray("sources").addObject()
                .put("kind", "pulse").put("key", "MapScene::stage").put("value", "Stage : 0")
                .put("recorded", "Stage : 0").put("citation", "match")
        }
        // 스텝 2 에서 Return 을 두 번 눌렀다. SDK 로 나간 같은 행은 세지 않는다.
        repeat(2) {
            qaLogRepository.save(
                QaLogEntity(
                    qaTryId = run.tryId, direction = "AGENT_TO_ORCHE", type = "ACTION", message = "Pressing Return",
                    payload = Json.of("""{"step":2,"message":"Pressing Return","actions":[{"method":"key_click"}]}"""),
                )
            )
        }
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = run.tryId, direction = "ORCHE_TO_SDK", type = "ACTION", message = "Pressing Return",
                payload = Json.of("""{"step":2,"message":"Pressing Return"}"""),
            )
        )
        issueRepository.save(
            IssueEntity(
                qaTryId = run.tryId, severity = "MAJOR", title = "카드가 안 생긴다",
                detail = Json.of("""{"step":3}"""), reportedAt = Instant.now(),
            )
        )
        // 근거 칸이 없는 판정의 이유는 바로 앞 판정 도구 호출의 `thought` 다.
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = run.tryId, direction = "AGENT_TO_ORCHE", type = "TOOL", message = "report_step",
                payload = Json.of("""{"tool":"report_step","args":{"step":3,"passed":false,"thought":"적이 남아 있다"}}"""),
            )
        )
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)

        // TC 판정은 그 판정을 실은 STATUS 행을 가리킨다.
        val stepTwo = qaLogRepository.findStepVerdicts(run.tryId).toList()
            .single { objectMapper.readTree(it.payload.asString()).path("step").asInt() == 2 }
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verdictQaLogId)
            .isEqualTo(stepTwo.id)
        assertThat(caseVerdictLogs(run.tryId).first().path("verdict_log_id").asLong()).isEqualTo(stepTwo.id)
        val history = caseVerdictService.history(run.projectId, run.passedCaseId, run.ownerId).items.single()
        assertThat(listOf(history.qaRunId, history.qaTryId, history.verdictLogId))
            .containsExactly(run.qaRunId.toString(), run.tryId.toString(), stepTwo.id.toString())

        // 런의 판정 목록은 출처(TR · 빌드), 시나리오의 모든 스텝과 TC 기대, 스텝에 붙은 이슈를 함께 싣는다.
        val listed = qaVerdictService.list(run.qaRunId, run.ownerId)
        assertThat(listed.testRunName).isEqualTo("런")
        assertThat(listed.gameBuildVersion).isEqualTo("1.0.0")
        val scenario = listed.tries.single()
        assertThat(scenario.steps.map { it.step to it.expected }).containsExactly(1 to "x", 2 to "x", 3 to "y")
        assertThat(scenario.issues.map { it.step to it.severity }).containsExactlyInAnyOrder(3 to "MAJOR", null to "MAJOR")
        // 런의 판정 목록은 스텝 순서로 근거를 싣고, 근거 칸이 없는 판정은 `none` 이다.
        val verdicts = scenario.verdicts
        assertThat(verdicts[1].actions.map { Triple(it.kind, it.label, it.count) }).containsExactly(Triple("key", "Return", 2))
        assertThat(verdicts.map { it.step }).containsExactly(1, 2, 3)
        val judged = verdicts[1]
        assertThat(judged.logId).isEqualTo(stepTwo.id.toString())
        assertThat(judged.stepTitle).isEqualTo("2")
        assertThat(judged.reason).isEqualTo("Stage 글자가 떴다")
        assertThat(judged.evidence).isEqualTo("verified")
        assertThat(judged.checks!!.path(0).path("sources").path(0).path("citation").asText()).isEqualTo("match")
        assertThat(verdicts[2].passed).isFalse()
        assertThat(verdicts[2].evidence).isEqualTo("none")
        assertThat(verdicts[2].reason).isEqualTo("적이 남아 있다")
        assertThat(verdicts[0].reason).isNull()

        // 보이지 않는 런은 없는 것이다.
        assertThatThrownBy { runBlocking { qaVerdictService.list(run.qaRunId, run.ownerId + 999) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `판정 목록은 검증 판정마다 코드 검사 결과와 다시 볼 판정을 싣는다`(): Unit = runBlocking {
        val run = runningRun()
        // 두 TC 모두 "S 화면에 Canvas/Stage 가 보인다" 를 기대한다(ARTEL-1252).
        for (caseId in listOf(run.passedCaseId, run.failedCaseId)) {
            val version = versions.findByTestCaseIdAndGameBuildId(caseId, run.gameBuildId)!!
            versions.save(version.copy(metadata = Json.of("""{"case_key":"screen\u001fS\u001fCanvas/Stage"}""")))
        }
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = run.tryId, direction = "SDK_TO_ORCHE", type = "PULSE", message = "Pulse received.",
                payload = Json.of("""{"scene":"S","active":[{"path":"Canvas/Stage","selector":"Canvas/Stage[3]"}]}"""),
            )
        )

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = true)
        // AI 통과 · 코드 확정 통과: 다시 볼 필요 없다.
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)
        // AI 실패 · 코드 확정 통과: 서로 반대라 다시 본다.
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)

        val scenario = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single()
        val (nonVerification, passed, failed) = scenario.verdicts
        assertThat(nonVerification.codeCheck).isNull()
        assertThat(nonVerification.needsReview).isFalse()
        assertThat(passed.codeCheck!!.result).isEqualTo("PASS")
        val item = passed.codeCheck!!.items.single()
        assertThat(listOf(item.target, item.expected, item.observed, item.result)).containsExactly("Canvas/Stage", "true", "true", "PASS")
        assertThat(passed.needsReview).isFalse()
        assertThat(failed.codeCheck!!.result).isEqualTo("PASS")
        assertThat(failed.needsReview).isTrue()
        assertThat(scenario.reviewCount).isEqualTo(1)
    }

    @Test
    fun `사람이 추가 검토 판정을 확정하면 TC 판정이 따르고 남은 검토 수가 준다`(): Unit = runBlocking {
        val run = runningRun()
        for (caseId in listOf(run.passedCaseId, run.failedCaseId)) {
            val version = versions.findByTestCaseIdAndGameBuildId(caseId, run.gameBuildId)!!
            versions.save(version.copy(metadata = Json.of("""{"case_key":"screen\u001fS\u001fCanvas/Stage"}""")))
        }
        qaLogRepository.save(
            QaLogEntity(
                qaTryId = run.tryId, direction = "SDK_TO_ORCHE", type = "PULSE", message = "Pulse received.",
                payload = Json.of("""{"scene":"S","active":[{"path":"Canvas/Stage","selector":"Canvas/Stage[3]"}]}"""),
            )
        )
        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)
        // AI 실패 · 코드 확정 통과: 추가 검토로 고른다.
        reportStep(run.tryId, step = 3, caseId = run.failedCaseId, verification = true, passed = false)
        assertThat(versionOf(run.failedCaseId).verificationStatus).isEqualTo("FAILED")

        reviewService.flagRun(run.qaRunId, run.gameBuildId)
        reviewService.flagRun(run.qaRunId, run.gameBuildId)
        val listed = launchReadService.listRuns(run.projectId, run.ownerId, 20).items.single()
        assertThat(listOf(listed.reviewFlagged, listed.reviewPending)).containsExactly(1, 1)
        assertThat(listed.startedBy).isEqualTo(run.ownerId.toString())

        val stepThree = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single().verdicts.single { it.step == 3 }
        assertThat(stepThree.needsReview).isTrue()
        reviewService.decide(run.qaRunId, stepThree.logId.toLong(), run.ownerId, passed = true, note = "  영역이 켜졌다  ")

        // TC 판정은 사람 결정을 따른다.
        assertThat(versionOf(run.failedCaseId).verificationStatus).isEqualTo("VERIFIED")
        val scenario = qaVerdictService.list(run.qaRunId, run.ownerId).tries.single()
        val decided = scenario.verdicts.single { it.step == 3 }
        assertThat(decided.passed).isFalse()
        assertThat(decided.needsReview).isFalse()
        assertThat(decided.humanDecision!!.passed).isTrue()
        assertThat(decided.humanDecision!!.note).isEqualTo("영역이 켜졌다")
        assertThat(decided.humanDecision!!.decidedBy).isEqualTo("verdict")
        assertThat(decided.humanDecision!!.flagged).isTrue()
        assertThat(listOf(scenario.reviewCount, scenario.reviewedCount)).containsExactly(0, 1)
        assertThat(launchReadService.listRuns(run.projectId, run.ownerId, 20).items.single().reviewPending).isEqualTo(0)

        // 다음 QA 가 같은 TC 를 판정할 때 사람 판정 기록을 함께 받는다(ARTEL-1256). 기록이 없는 TC 는 키가 없다.
        val draft = ScenarioDraft(
            title = "다음",
            description = "",
            steps = listOf(ScenarioStep(action = "a", caseId = run.failedCaseId), ScenarioStep(action = "b", caseId = run.passedCaseId)),
        )
        val (reviewed, plain) = composition.agentScenario(draft).steps
        val review = reviewed.case!!.reviews!!.single()
        assertThat(listOf(review.aiPassed, review.humanPassed, review.note, review.gameBuildVersion))
            .containsExactly(false, true, "영역이 켜졌다", "1.0.0")
        // agent 는 `decided_at` 을 문자열로 받는다. 숫자로 가면 세션 오픈이 422 다.
        assertThat(objectMapper.readTree(objectMapper.writeValueAsString(reviewed.case)).path("reviews").path(0).path("decided_at").isTextual).isTrue()
        assertThat(plain.case!!.reviews).isNull()
        assertThat(objectMapper.writeValueAsString(plain.case)).doesNotContain("reviews")

        // 다시 정하면 덮어쓴다. 보이지 않는 실행은 없는 것이다.
        reviewService.decide(run.qaRunId, stepThree.logId.toLong(), run.ownerId, passed = false, note = null)
        assertThat(versionOf(run.failedCaseId).verificationStatus).isEqualTo("FAILED")
        assertThat(composition.agentScenario(draft).steps.first().case!!.reviews!!.single().humanPassed).isFalse()
        assertThatThrownBy { runBlocking { reviewService.decide(run.qaRunId, stepThree.logId.toLong(), run.ownerId + 999, true, null) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    @Test
    fun `앞 버전 몫은 새 버전에서 난 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        val testCase = testCaseRepository.findById(run.passedCaseId)!!
        val project = testCase.projectId
        val now = Instant.now()
        val older = gameBuildRepository.save(
            GameBuildEntity(projectId = project, version = "0.9.0", createdAt = now.minusSeconds(3600), updatedAt = now)
        )
        versions.save(TestCaseVersionEntity.snapshotOf(testCase, older.id!!).copy(verificationStatus = "VERIFIED"))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = false)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, older.id)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("FAILED")
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("FAILED")
    }

    @Test
    fun `검증 스텝이 아니거나 case_id 가 없는 프레임은 상태를 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()

        reportStep(run.tryId, step = 1, caseId = run.passedCaseId, verification = false, passed = true)
        reportStep(run.tryId, step = 2, caseId = null, verification = true, passed = true)

        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    @Test
    fun `그 게임 버전에서 BROKEN 인 TC 는 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        versions.save(versionOf(run.passedCaseId).copy(verificationStatus = "BROKEN"))
        versions.syncIdentities(listOf(run.passedCaseId))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versionOf(run.passedCaseId).verificationStatus).isEqualTo("BROKEN")
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("BROKEN")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** 깨졌는지는 판정할 게임 버전의 몫으로 본다(ARTEL-1069). 다른 버전에서 깨졌어도 이 버전 몫에는 적는다. */
    @Test
    fun `다른 버전에서만 BROKEN 인 TC 는 이 게임 버전에 판정을 적는다`(): Unit = runBlocking {
        val run = runningRun()
        testCaseRepository.save(testCaseRepository.findById(run.passedCaseId)!!.copy(verificationStatus = "BROKEN"))

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versionOf(run.passedCaseId).verificationStatus).isEqualTo("VERIFIED")
        assertThat(caseVerdictLogs(run.tryId)).hasSize(1)
    }

    private suspend fun versionOf(testCaseId: Long) = versions.findAll().toList().single { it.testCaseId == testCaseId }

    @Test
    fun `같은 프레임의 재전송은 판정 기록을 하나만 남긴다`(): Unit = runBlocking {
        val run = runningRun()
        val messageId = UUID.randomUUID().toString()

        repeat(2) {
            reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true, messageId)
        }

        assertThat(caseVerdictLogs(run.tryId)).hasSize(1)
    }

    @Test
    fun `다른 프로젝트의 TC id 가 와도 그 TC 를 바꾸지 않는다`(): Unit = runBlocking {
        val run = runningRun()
        val now = Instant.now()
        val otherProject = projectRepository.save(
            ProjectEntity(name = "other", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val foreign = testCaseRepository.save(
            TestCaseEntity(projectId = otherProject.id!!, scene = "S", step = "남의 것", expectedValue = "x")
        )

        reportStep(run.tryId, step = 2, caseId = foreign.id!!, verification = true, passed = true)

        assertThat(testCaseRepository.findById(foreign.id)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    @Test
    fun `이 게임 빌드에 없는 TC 는 판정이 와도 그대로다`(): Unit = runBlocking {
        val run = runningRun()
        versions.delete(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!)

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)).isNull()
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.verificationStatus).isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** editor 버전에 TC 가 없으면(ARTEL-1180 이전에 쌓인 것 등) 예전처럼 같은 버전의 게임 빌드 몫이다(ARTEL-1014). */
    @Test
    fun `editor 실행의 판정은 같은 버전의 게임 빌드에 적힌다`(): Unit = runBlocking {
        val run = runningRun(onEditor = true)

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(run.runBuildId).isNotEqualTo(run.gameBuildId)
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("VERIFIED")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.runBuildId)).isNull()
        assertThat(testCaseRepository.findById(run.passedCaseId)!!.lastVerifiedBuildId).isEqualTo(run.gameBuildId)
        assertThat(caseVerdictLogs(run.tryId).single().path("game_build_id").asLong()).isEqualTo(run.gameBuildId)
    }

    @Test
    fun `같은 버전의 게임 빌드가 없는 editor 실행은 판정을 적지 않는다`(): Unit = runBlocking {
        val run = runningRun(onEditor = true, editorVersion = "1.1.0")

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("DRAFT")
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.runBuildId)).isNull()
        assertThat(caseVerdictLogs(run.tryId)).isEmpty()
    }

    /** editor 버전에서 TC 를 만들었으면 판정은 그 editor 버전 몫이고, 이어받았던 표시는 지운다(ARTEL-1180). */
    @Test
    fun `TC 가 있는 editor 버전의 판정은 그 버전에 적히고 이어받은 표시를 지운다`(): Unit = runBlocking {
        val run = runningRun(onEditor = true)
        val carried = versions.save(
            TestCaseVersionEntity.snapshotOf(testCaseRepository.findById(run.passedCaseId)!!, run.runBuildId)
                .copy(verificationStatus = "FAILED", verdictCarriedFromBuildId = run.gameBuildId)
        )

        reportStep(run.tryId, step = 2, caseId = run.passedCaseId, verification = true, passed = true)

        val judged = versions.findById(carried.id!!)!!
        assertThat(judged.verificationStatus).isEqualTo("VERIFIED")
        assertThat(judged.verdictCarriedFromBuildId).isNull()
        assertThat(versions.findByTestCaseIdAndGameBuildId(run.passedCaseId, run.gameBuildId)!!.verificationStatus)
            .isEqualTo("DRAFT")
        assertThat(caseVerdictLogs(run.tryId).single().path("game_build_id").asLong()).isEqualTo(run.runBuildId)
    }

    @Test
    fun `TC 보고에서 agent가 제외한 knowledge ID 수를 원본 로그에 보존한다`(): Unit = runBlocking {
        val run = runningRun()
        val attempt = attempts.start(run.tryId, StartCaseAttempt(UUID.randomUUID(), run.passedCaseId))
        val result = attempts.report(run.tryId, attempt.id,
            ReportCaseAttempt(CaseOutcome.PASSED, "화면 확인", rejectedKnowledgeIdCount = 2))
        val log = qaLogRepository.findById(result.logId!!.toLong())!!
        assertThat(objectMapper.readTree(log.payload.asString()).path("rejected_knowledge_id_count").asInt()).isEqualTo(2)
    }

    /** [gameBuildId] 는 케이스가 든 게임 빌드, [runBuildId] 는 qa_run 이 붙은 build 다. editor 실행이면 둘이 다르다. */
    private data class RunningRun(
        val tryId: Long,
        val gameBuildId: Long,
        val runBuildId: Long,
        val passedCaseId: Long,
        val failedCaseId: Long,
        val qaRunId: Long,
        val projectId: Long,
        val ownerId: Long,
    )

    /**
     * 게임 빌드 1.0.0 에 든 케이스 둘을 검증하는 시나리오를 돌리는 중인 run. [onEditor] 면 그 run 은 [editorVersion] 을
     * 보고한 editor 행에 붙는다.
     */
    private suspend fun runningRun(onEditor: Boolean = false, editorVersion: String = "1.0.0"): RunningRun {
        val owner = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "994", login = "verdict",
                displayName = "verdict", avatarUrl = null, email = "verdict@example.com"
            )
        )
        val ownerId = owner.userId.toLong()
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "verdict-p", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = ownerId, role = ProjectRole.OWNER.name, createdAt = now)
        )
        val build = gameBuildRepository.save(
            GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now, updatedAt = now)
        )
        val runBuild = if (!onEditor) build else gameBuildRepository.save(
            GameBuildEntity(projectId = projectId, version = editorVersion, isEditor = true, createdAt = now, updatedAt = now)
        )
        val instance = gameInstanceRepository.save(
            GameInstanceEntity(
                projectId = projectId, name = "inst", platform = "UNITY", sdkUuid = UUID.randomUUID().toString(),
                lastGameBuildId = runBuild.id, createdAt = now, updatedAt = now
            )
        )
        val passedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "통과할 것", expectedValue = "x")
        )
        val failedCase = testCaseRepository.save(
            TestCaseEntity(projectId = projectId, scene = "S", step = "실패할 것", expectedValue = "y")
        )
        versions.save(TestCaseVersionEntity.snapshotOf(passedCase, build.id!!))
        versions.save(TestCaseVersionEntity.snapshotOf(failedCase, build.id))
        val testRun = testRunRepository.save(TestRunEntity(projectId = projectId, name = "런"))
        val draft = ScenarioDraft(
            title = "판정",
            description = "",
            steps = listOf(
                ScenarioStep(action = "1", caseId = passedCase.id),
                ScenarioStep(action = "2", caseId = passedCase.id),
                ScenarioStep(action = "3", caseId = failedCase.id),
            )
        )
        val scenarioId = scenarioRepository.save(TestScenarioEntity(projectId = projectId).withDraft(draft, objectMapper)).id!!
        val started = persistence.createRunStarting(
            testRun.id!!, instance.id!!, ownerId, listOf(scenarioId), gameBuildId = runBuild.id
        )
        val tryId = requireNotNull(started.tries.first().id)
        persistence.attachRunAndMarkRunning(
            started.qaRun, tryId, "session-verdict-1",
            objectMapper.readTree("""{"model":"anthropic/claude-sonnet-5"}""")
        )
        return RunningRun(
            tryId, build.id, runBuild.id!!, passedCase.id!!, failedCase.id!!, started.qaRun.id!!, projectId, ownerId
        )
    }

    private suspend fun reportStep(
        qaTryId: Long,
        step: Int,
        caseId: Long?,
        verification: Boolean,
        passed: Boolean,
        messageId: String = UUID.randomUUID().toString(),
        evidence: (ObjectNode) -> Unit = {},
    ) {
        val payload = objectMapper.createObjectNode()
            .put("status", if (passed) "COMPLETED" else "FAILED")
            .put("step", step)
            .put("is_verification", verification)
            .put("message", "step $step")
        caseId?.let { payload.put("case_id", it) }
        evidence(payload)
        router.handle(
            QaAgentEnvelope(
                messageId = messageId,
                type = "STATUS",
                qaTryId = qaTryId.toString(),
                timestamp = Instant.now(),
                payload = payload,
            )
        )
    }

    private suspend fun caseVerdictLogs(qaTryId: Long) =
        qaLogRepository.findAll().toList()
            .filter { it.qaTryId == qaTryId && it.type == "CASE_VERDICT" }
            .sortedBy { it.id }
            .map { objectMapper.readTree(it.payload.asString()) }
}
