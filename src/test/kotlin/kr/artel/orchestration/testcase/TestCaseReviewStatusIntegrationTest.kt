package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.support.testAppUser
import kr.artel.orchestration.testcase.contract.CaseBatchItem
import kr.artel.orchestration.testcase.contract.CaseBatchRequest
import kr.artel.orchestration.testcase.contract.CaseBatchService
import kr.artel.orchestration.testcase.contract.CaseContractService
import kr.artel.orchestration.testcase.contract.CaseTermInput
import kr.artel.orchestration.testcase.dto.ReviewDecision
import kr.artel.orchestration.testcase.dto.ReviewDecisionRequest
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.CaseReviewService
import kr.artel.orchestration.testcase.service.TestCaseService
import kr.artel.orchestration.testcase.service.TestCaseVersionService
import kr.artel.orchestration.testcase.service.TestCaseReader
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioStep
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.AuthoringCases
import kr.artel.orchestration.testscenario.service.ScenarioCompositionService
import kr.artel.orchestration.testscenario.service.TestScenarioService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Assertions.assertThrows
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class TestCaseReviewStatusIntegrationTest {
    @Autowired private lateinit var service: TestCaseService
    @Autowired private lateinit var cases: TestCaseRepository
    @Autowired private lateinit var users: AppUserRepository
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var authoring: AuthoringCases
    @Autowired private lateinit var batches: CaseBatchService
    @Autowired private lateinit var contracts: CaseContractService
    @Autowired private lateinit var scenarios: TestScenarioService
    @Autowired private lateinit var composition: ScenarioCompositionService
    @Autowired private lateinit var versions: TestCaseVersionService
    @Autowired private lateinit var reader: TestCaseReader
    @Autowired private lateinit var builds: GameBuildRepository
    @Autowired private lateinit var reviews: CaseReviewService
    @Autowired private lateinit var scenarioEntities: TestScenarioRepository
    @Autowired private lateinit var mapper: ObjectMapper

    private suspend fun fixture(): Pair<Long, Long> {
        val now = Instant.now()
        val user = users.save(testAppUser("review-status", now)).id!!
        val project = projects.save(ProjectEntity(name = "review-status", genre = "ACTION", createdAt = now, updatedAt = now)).id!!
        members.save(ProjectMemberEntity(projectId = project, appUserId = user, role = "MEMBER", createdAt = now))
        return project to user
    }

    private fun request(draft: Boolean = false) = TestCaseCreateRequest(
        scene = "광장", step = "문을 연다", expectedValue = "문이 열린다", draft = draft,
    )

    /** [request] 와 같은 내용에서 조작만 바꾼 수정. */
    private fun edit(id: String, step: String = "문을 연다") =
        CaseBatchItem(testCaseId = id, scene = "광장", step = step, expectedValue = "문이 열린다")

    @Test
    fun `기존 생성은 사용 가능하며 초안 생성은 검증 결과와 별도로 저장된다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val ready = service.createTestCase(project, user, request())!!
        val draft = service.createTestCase(project, user, request(true))!!
        assertThat(ready.reviewStatus).isEqualTo("READY")
        assertThat(draft.reviewStatus).isEqualTo("DRAFT")
        assertThat(ready.verificationStatus).isEqualTo("DRAFT")
        assertThat(draft.verificationStatus).isEqualTo("DRAFT")
        assertThat(service.getTestCase(draft.id.toLong(), user)!!.reviewStatus).isEqualTo("DRAFT")
        assertThat(service.listTestCases(project, user).items.map { it.reviewStatus }).containsExactly("DRAFT", "READY")
        assertThat(versions.unversioned(project, user).items.map { it.reviewStatus }).containsExactly("DRAFT", "READY")
        assertThat(cases.save(TestCaseEntity(projectId = project, scene = "숲", step = "걷는다", expectedValue = "이동한다")).reviewStatus).isEqualTo("READY")
        assertThat(service.reviewStatusOptions(project, user).statuses).containsExactly("DRAFT", "READY")
        assertThrows(NotFoundException::class.java) { runBlocking { service.reviewStatusOptions(project, user + 999999) } }
    }

    @Test
    fun `초안은 후보와 커버리지에서 빠지고 승인하면 미검증 상태로 사용할 수 있다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val tc = service.createTestCase(project, user, request(true))!!
        val id = tc.id.toLong()
        assertThat(authoring.all(project)).isEmpty()
        assertThat(authoring.byId(id)).isNull()
        assertThat(authoring.byIds(listOf(id))).isEmpty()
        assertThat(cases.findIdsByProjectId(project).toList()).isEmpty()
        assertThat(service.getAllTestCases(project, user).items).isEmpty()
        assertThat(contracts.hasPending(project)).isFalse()
        assertThat(contracts.refresh(project).asked).isZero()
        assertThat(service.coverage(project, user).total).isZero()
        val scenario = scenarios.createScenario(project, user)!!
        val body = ScenarioDraft(title = "문 열기", steps = listOf(ScenarioStep(action = "문을 연다", caseId = id)))
        assertThrows(BadRequestException::class.java) { runBlocking { scenarios.testScenarioUpdate(user, scenario, body) } }
        assertThrows(BadRequestException::class.java) { runBlocking { composition.agentScenario(body) } }
        val approved = service.updateTestCase(id, user, TestCaseUpdateRequest(reviewStatus = "READY"))!!
        assertThat(approved.verificationStatus).isEqualTo("DRAFT")
        assertThat(authoring.all(project).map { it.id }).containsExactly(id)
        assertThat(service.coverage(project, user).total).isEqualTo(1)
        scenarios.testScenarioUpdate(user, scenario, body)
        assertThat(composition.agentScenario(body).steps).hasSize(1)
        service.updateTestCase(id, user, TestCaseUpdateRequest(step = "문을 다시 연다"))
        assertThat(cases.findById(id)!!.reviewStatus).isEqualTo("READY")
        assertThrows(BadRequestException::class.java) { runBlocking { service.updateTestCase(id, user, TestCaseUpdateRequest(reviewStatus = "VERIFIED")) } }
        assertThat(cases.findById(id)!!.reviewStatus).isEqualTo("READY")
    }

    @Test
    fun `배치의 기본 초안은 새 TC와 내용이 바뀐 TC에 적용하고 명시적으로 사용 가능하게 생성한다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val ready = service.createTestCase(project, user, request())!!
        val mixed = batches.save(project, user, CaseBatchRequest(cases = listOf(
            CaseBatchItem(testCaseId = ready.id, scene = "광장", step = "문을 고친다", expectedValue = "고쳐진다"),
            CaseBatchItem(scene = "숲", step = "지도를 펼친다", expectedValue = "지도가 보인다"),
        )))
        assertThat(mixed.rejected).isEmpty()
        assertThat(cases.findById(ready.id.toLong())!!.reviewStatus).isEqualTo("DRAFT")
        assertThat(cases.findById(mixed.created.single().toLong())!!.reviewStatus).isEqualTo("DRAFT")
        val created = batches.save(project, user, CaseBatchRequest(draft = false, cases = listOf(
            CaseBatchItem(scene = "숲", step = "지도를 접는다", expectedValue = "지도가 사라진다"),
        )))
        assertThat(created.rejected).isEmpty()
        assertThat(cases.findById(created.created.single().toLong())!!.reviewStatus).isEqualTo("READY")
        assertThat(contracts.list(project).cases.map { it.reviewStatus }).containsExactly("DRAFT", "DRAFT", "READY")
    }

    @Test
    fun `MCP 배치로 READY TC의 내용을 고치면 요청자의 승인 대기가 되고 거절할 수 없다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val id = service.createTestCase(project, user, request())!!.id
        val saved = batches.save(project, user, CaseBatchRequest(cases = listOf(edit(id, step = "문을 두드린다"))))
        assertThat(saved.rejected).isEmpty()
        assertThat(saved.updated).containsExactly(id)
        val edited = cases.findById(id.toLong())!!
        assertThat(edited.reviewStatus).isEqualTo("DRAFT")
        assertThat(edited.reviewRequestedBy).isEqualTo(user)

        val item = reviews.queue(project, user).single()
        assertThat(item.step).isEqualTo("문을 두드린다")
        assertThat(item.rejectable).isFalse()
        val refused = assertThrows(ConflictException::class.java) {
            runBlocking { reviews.decide(project, id.toLong(), user, ReviewDecisionRequest(ReviewDecision.REJECT, item.updatedAt)) }
        }
        assertThat(refused.code).isEqualTo(CaseReviewService.REVIEW_NOT_REJECTABLE)
        assertThat(cases.findById(id.toLong())).isNotNull()

        reviews.decide(project, id.toLong(), user, ReviewDecisionRequest(ReviewDecision.APPROVE, item.updatedAt))
        val approved = cases.findById(id.toLong())!!
        assertThat(approved.reviewStatus).isEqualTo("READY")
        assertThat(approved.reviewRequestedBy).isNull()
        assertThat(approved.reviewEditsApproved).isFalse()
        assertThat(approved.step).isEqualTo("문을 두드린다")
    }

    @Test
    fun `내용이 같은 배치와 draft false 배치와 파일 가져오기와 웹 수정은 READY를 유지한다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val id = service.createTestCase(project, user, request())!!.id
        fun status() = runBlocking { cases.findById(id.toLong())!!.let { it.reviewStatus to it.reviewRequestedBy } }

        // 같은 기능을 새 버전에 넣으려고 그대로 다시 보낸 것이다.
        val now = Instant.now()
        val build = builds.save(GameBuildEntity(projectId = project, version = "2.0.0", createdAt = now, updatedAt = now)).id!!
        assertThat(batches.save(project, user, CaseBatchRequest(cases = listOf(edit(id)))).rejected).isEmpty()
        assertThat(status()).isEqualTo("READY" to null)
        assertThat(contracts.list(project).cases.single().gameBuildIds).containsExactly(build.toString())

        // 승인 팝업에 보이지 않는 계약만 바꾼 것이다.
        val contractOnly = batches.save(project, user, CaseBatchRequest(
            terms = listOf(CaseTermInput("열쇠", "counter", start = "0")),
            cases = listOf(edit(id).copy(needs = listOf("열쇠 >= 1"))),
        ))
        assertThat(contractOnly.rejected).isEmpty()
        assertThat(contracts.list(project).cases.single().needs).containsExactly("열쇠 >= 1")
        assertThat(status()).isEqualTo("READY" to null)

        assertThat(batches.save(project, user, CaseBatchRequest(draft = false, cases = listOf(edit(id, step = "하나")))).rejected).isEmpty()
        assertThat(status()).isEqualTo("READY" to null)

        // 파일 가져오기(CaseFileService)가 부르는 모양이다.
        assertThat(batches.saveInto(project, user, CaseBatchRequest(cases = listOf(edit(id, step = "둘"))), null).rejected).isEmpty()
        assertThat(status()).isEqualTo("READY" to null)

        service.updateTestCase(id.toLong(), user, TestCaseUpdateRequest(step = "셋"))
        assertThat(status()).isEqualTo("READY" to null)
    }

    @Test
    fun `승인 팝업이 보여 주는 칸 중 하나만 바뀌어도 승인 대기로 되돌린다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val changes = listOf<(CaseBatchItem) -> CaseBatchItem>(
            { it.copy(scene = "숲") },
            { it.copy(step = "문을 민다") },
            { it.copy(precondition = "문이 닫혀 있다") },
            { it.copy(expectedValue = "문이 부서진다") },
            { it.copy(kind = "EXCEPTION") },
        )
        changes.forEach { change ->
            val id = service.createTestCase(project, user, request())!!.id
            assertThat(batches.save(project, user, CaseBatchRequest(cases = listOf(change(edit(id))))).rejected).isEmpty()
            assertThat(cases.findById(id.toLong())!!.reviewStatus).isEqualTo("DRAFT")
        }
    }

    @Test
    fun `승인 대기 시나리오가 쓰는 TC는 승인 전이어도 거절할 수 없다고 알린다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val id = batches.save(project, user, CaseBatchRequest(cases = listOf(
            CaseBatchItem(scene = "광장", step = "문을 연다", expectedValue = "문이 열린다"),
        ))).created.single().toLong()
        scenarioEntities.save(
            TestScenarioEntity(projectId = project, reviewStatus = "DRAFT", reviewRequestedBy = user)
                .withDraft(ScenarioDraft(steps = listOf(ScenarioStep(action = "문을 연다", caseId = id))), mapper)
        )
        val item = reviews.queue(project, user).single()
        assertThat(item.rejectable).isFalse()
        val refused = assertThrows(ConflictException::class.java) {
            runBlocking { reviews.decide(project, id, user, ReviewDecisionRequest(ReviewDecision.REJECT, item.updatedAt)) }
        }
        assertThat(refused.code).isEqualTo(CaseReviewService.REVIEW_NOT_REJECTABLE)
    }

    @Test
    fun `승인 전 TC를 다시 고치면 거절할 수 있고 승인을 되돌린 TC를 고치면 거절할 수 없다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val other = users.save(testAppUser("review-status-other", Instant.now())).id!!
        members.save(ProjectMemberEntity(projectId = project, appUserId = other, role = "MEMBER", createdAt = Instant.now()))

        val draft = batches.save(project, user, CaseBatchRequest(cases = listOf(
            CaseBatchItem(scene = "광장", step = "문을 연다", expectedValue = "문이 열린다"),
        ))).created.single()
        batches.save(project, other, CaseBatchRequest(cases = listOf(edit(draft, step = "문을 민다"))))
        val reRequested = cases.findById(draft.toLong())!!
        assertThat(reRequested.reviewRequestedBy).describedAs("알림은 마지막으로 고친 사람에게 간다").isEqualTo(other)
        val item = reviews.queue(project, other).single()
        assertThat(item.rejectable).isTrue()
        reviews.decide(project, draft.toLong(), other, ReviewDecisionRequest(ReviewDecision.REJECT, item.updatedAt))
        assertThat(cases.findById(draft.toLong())).describedAs("한 번도 승인되지 않아 지운다").isNull()

        val reverted = service.createTestCase(project, user, request())!!.id
        service.updateTestCase(reverted.toLong(), user, TestCaseUpdateRequest(reviewStatus = "DRAFT"))
        batches.save(project, user, CaseBatchRequest(cases = listOf(edit(reverted, step = "문을 당긴다"))))
        assertThat(reviews.queue(project, user).single { it.id == reverted }.rejectable).isFalse()
    }

    @Test
    fun `버전 목록도 검토 상태를 보이며 초안은 해당 버전 저작 후보에서 제외된다`(): Unit = runBlocking {
        val (project, user) = fixture()
        val now = Instant.now()
        val build = builds.save(GameBuildEntity(projectId = project, version = "1.0.0", createdAt = now, updatedAt = now)).id!!
        val draft = service.createTestCase(project, user, request(true).copy(gameBuildId = build.toString()))!!
        assertThat(versions.cases(project, build, null, user).items.single().reviewStatus).isEqualTo("DRAFT")
        assertThat(service.listTestCases(project, user, build).items.single().reviewStatus).isEqualTo("DRAFT")
        assertThat(reader.caseIds(build)).isEmpty()
        service.updateTestCase(draft.id.toLong(), user, TestCaseUpdateRequest(gameBuildId = build.toString(), reviewStatus = "READY"))
        assertThat(reader.caseIds(build)).containsExactly(draft.id.toLong())
        assertThat(versions.cases(project, build, null, user).items.single().verificationStatus).isEqualTo("DRAFT")
        // 지도 TC의 버전별 내용 수정 경로에서도 검토 상태는 identity에만 저장한다.
        val identity = cases.findById(draft.id.toLong())!!
        cases.save(identity.copy(metadata = Json.of("""{"origin":"content-map"}""")))
        val changed = service.updateTestCase(draft.id.toLong(), user,
            TestCaseUpdateRequest(gameBuildId = build.toString(), reviewStatus = "DRAFT"))!!
        assertThat(changed.reviewStatus).isEqualTo("DRAFT")
        assertThat(cases.findById(draft.id.toLong())!!.reviewStatus).isEqualTo("DRAFT")
        assertThat(reader.caseIds(build)).isEmpty()
    }
}
