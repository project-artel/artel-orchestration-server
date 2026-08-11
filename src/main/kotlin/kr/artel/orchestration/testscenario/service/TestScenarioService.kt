package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.common.error.NotFoundException
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testscenario.dto.ScenarioDraft
import kr.artel.orchestration.testscenario.dto.ScenarioListResponse
import kr.artel.orchestration.testscenario.dto.ScenarioResponse
import kr.artel.orchestration.testscenario.dto.ScenarioSummary
import kr.artel.orchestration.testscenario.entity.TestScenarioEntity
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.entity.withDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.executeAndAwait
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.transaction.reactive.TransactionalOperator

/**
 * TestScenario 도메인 서비스(코루틴). 시나리오 본체의 생성/조회/수정/삭제를 담당한다(챗봇 대화는 런 단위이므로
 * [kr.artel.orchestration.testrun.service.TestRunChatService]로 분리됨 — ARTEL-206 Step 6).
 *
 * 모든 작업은 **프로젝트 참여자(project_member)인지 검증**한다. 비참여자에게는 프로젝트/시나리오가
 * 존재하지 않는 것처럼(null → 404) 보인다. 인증(JWT)만으로는 부족하고 해당 프로젝트 소속이어야 한다.
 */
@Service
class TestScenarioService(
    private val scenarioRepository: TestScenarioRepository,
    private val accessService: TestScenarioAccessService,
    private val runScenarioRepository: TestRunScenarioRepository,
    private val qaTryRepository: QaTryRepository,
    private val transactionalOperator: TransactionalOperator,
    private val objectMapper: ObjectMapper
) {

    /** 새 시나리오를 빈 본문으로 생성하고 testScenarioId를 반환한다. 비참여자면 null(→404). */
    suspend fun createScenario(projectId: Long, appUserId: Long): Long? {
        if (!accessService.isMember(projectId, appUserId)) return null
        val saved = scenarioRepository.save(TestScenarioEntity(projectId = projectId))
        return saved.id!!
    }

    /** 시나리오 단건 조회. 없거나 비참여자면 null(→404). */
    suspend fun getScenario(testScenarioId: Long, appUserId: Long): ScenarioResponse? {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId) ?: return null
        return ScenarioResponse(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            payload = entity.toDraft(objectMapper)
        )
    }

    /**
     * 프로젝트에 속한 시나리오 목록을 조회한다. FE 목록 화면용 요약(제목/시간)만 담는다.
     * 비참여자면 404(존재하지 않는 것처럼 감춤).
     */
    suspend fun listScenarios(projectId: Long, appUserId: Long): ScenarioListResponse {
        if (!accessService.isMember(projectId, appUserId)) {
            throw NotFoundException()
        }
        val items = scenarioRepository.findByProjectId(projectId)
            .map { toSummary(it) }
            .toList()
        return ScenarioListResponse(items)
    }

    /** 프로젝트 스코프 시나리오 단건 조회. projectId 불일치·비참여자·미존재면 null(→404). */
    suspend fun getScenarioInProject(projectId: Long, testScenarioId: Long, appUserId: Long): ScenarioResponse? {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId) ?: return null
        if (entity.projectId != projectId) return null
        return ScenarioResponse(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            payload = entity.toDraft(objectMapper)
        )
    }

    /** 목록 응답용 요약 변환. 제목은 컬럼이라 스텝을 역직렬화하지 않는다. */
    private fun toSummary(entity: TestScenarioEntity): ScenarioSummary =
        ScenarioSummary(
            testScenarioId = entity.id!!,
            projectId = entity.projectId,
            title = entity.title,
            createdAt = entity.createdAt,
            updatedAt = entity.updatedAt
        )

    /**
     * canvas 편집을 실시간 저장한다(자동저장). Agent를 거치지 않은 순수 FE 편집을 본문 컬럼에 덮어쓰고
     * (last-write-wins), 저장된 결과를 그대로 되돌려 FE가 로컬 draft와 DB 상태의 정합성을 맞추게 한다.
     * 접근 불가면 404.
     */
    suspend fun testScenarioUpdate(appUserId: Long, testScenarioId: Long, draft: ScenarioDraft): ScenarioResponse {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        // 기대 판정 라벨은 이 경로로 못 들어온다(ARTEL-301). 저작 화면은 라벨을 모르므로 자동저장이
        // 라벨 없는 steps를 보내고, 그대로 저장하면 스텝을 한 글자 고쳤을 뿐인데 그 시나리오의
        // 정답지가 통째로 사라진다. 규칙과 이유는 [ExpectedLabelPolicy]에 있다.
        val safe = draft.copy(
            steps = ExpectedLabelPolicy.carryOver(draft.steps, entity.toDraft(objectMapper).steps)
        )
        val saved = scenarioRepository.save(entity.withDraft(safe, objectMapper))
        return ScenarioResponse(
            testScenarioId = saved.id!!,
            projectId = saved.projectId,
            payload = saved.toDraft(objectMapper)
        )
    }

    /**
     * 시나리오를 승인(확정)한다. 최종 draft가 있으면 본문 컬럼에 저장한다. 대화 세션은 런 단위라 시나리오
     * 하나의 승인으로 닫지 않는다(런 대화는 계속된다). 접근 불가면 404.
     */
    suspend fun testScenarioApprove(appUserId: Long, testScenarioId: Long, draft: ScenarioDraft?) {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        if (draft != null) {
            // 승인도 본문 저장 경로라 같은 규칙을 진다 — 라벨은 여기로 들어오지도 나가지도 않는다.
            val safe = draft.copy(
                steps = ExpectedLabelPolicy.carryOver(draft.steps, entity.toDraft(objectMapper).steps)
            )
            scenarioRepository.save(entity.withDraft(safe, objectMapper))
        }
    }

    /**
     * 스텝의 기대 판정 라벨만 갈아끼운다(ARTEL-301). 본문은 건드리지 않는다.
     *
     * **라벨을 바꿀 수 있는 유일한 경로다.** 다른 저장 경로는 전부 [ExpectedLabelPolicy.carryOver]를
     * 지나며 들어온 라벨을 버리므로, 라벨을 모르는 클라이언트가 정답지를 지울 방법이 없다.
     *
     * 본문과 함께 받지 않는 것도 의도다. 한 요청으로 받으면 라벨링 도구가 본문까지 덮어쓸 수 있고,
     * 그러면 저작자가 방금 고친 스텝이 라벨 저장 한 번에 되돌아간다.
     *
     * @param labels 스텝 번호(1부터) → 라벨. 값이 `null`이면 **미지정으로 되돌린다**(키를 빼는 것과
     *   다르다 — 키가 없는 스텝은 손대지 않는다). 범위 밖 번호는 조용히 버린다.
     *
     * ⚠️ 접근 제어는 프로젝트 멤버십까지다. 이 리포에는 프로젝트 밖의 관리자 개념이 없어
     *   "내부 도구만 만진다"를 역할로 강제하지는 못한다 — 경로를 가른 것과 라벨링 화면을
     *   admin-page에만 두는 것이 현재의 경계다.
     */
    suspend fun updateExpectedLabels(
        appUserId: Long,
        testScenarioId: Long,
        labels: Map<Int, Boolean?>
    ): ScenarioResponse {
        val entity = accessService.accessibleScenario(testScenarioId, appUserId)
            ?: throw NotFoundException()
        val draft = entity.toDraft(objectMapper)
        val saved = scenarioRepository.save(
            entity.withDraft(
                draft.copy(steps = ExpectedLabelPolicy.apply(draft.steps, labels)),
                objectMapper
            )
        )
        return ScenarioResponse(
            testScenarioId = saved.id!!,
            projectId = saved.projectId,
            payload = saved.toDraft(objectMapper)
        )
    }

    /**
     * 시나리오를 완전히 삭제한다. 접근 불가/미존재면 404.
     *
     * QA 실행 이력(qa_try) 보호: 한 번이라도 실행된 시나리오는 실행 기록·발견 이슈가 붙어 있어
     * 기본적으로 삭제를 **차단(409)**한다 — `qa_try.test_scenario_id` FK(RESTRICT)가 실제로도 막고,
     * 그 이력이 조용히 사라지지 않게 하기 위함이다. [force]가 true면 그 시나리오의 qa_try를 먼저 지우고
     * (qa_log·issue는 FK CASCADE로 함께 삭제) 본체까지 삭제한다.
     *
     * 정리 순서: (force면) qa_try → 조합 링크(케이스 조합·런 조합) → 시나리오 본체. 한 트랜잭션으로 묶어
     * 부분 삭제 상태가 남지 않게 한다. 대화 세션은 런 단위라 여기서 닫지 않는다(런 대화는 계속된다).
     */
    suspend fun delete(appUserId: Long, testScenarioId: Long, force: Boolean = false) {
        accessService.accessibleScenario(testScenarioId, appUserId) ?: throw NotFoundException()
        val runCount = qaTryRepository.countByTestScenarioId(testScenarioId)
        if (runCount > 0 && !force) {
            throw ConflictException(
                message = "이 시나리오에는 QA 실행 이력이 있어 삭제할 수 없습니다. 실행 기록과 발견된 이슈까지 함께 지우려면 강제 삭제를 사용하세요.",
                code = "scenario_has_qa_history"
            )
        }
        transactionalOperator.executeAndAwait {
            if (runCount > 0) {
                qaTryRepository.deleteByTestScenarioId(testScenarioId)
            }
            runScenarioRepository.deleteByTestScenarioId(testScenarioId)
            scenarioRepository.deleteById(testScenarioId)
        }
    }
}
