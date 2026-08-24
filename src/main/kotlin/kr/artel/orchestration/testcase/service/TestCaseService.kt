package kr.artel.orchestration.testcase.service

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.AllTestCasesResponse
import kr.artel.orchestration.testcase.dto.AuthoringTestCase
import kr.artel.orchestration.testcase.dto.CaseGuard
import kr.artel.orchestration.testcase.dto.TestCaseCoverageResponse
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.dto.toTestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testscenario.service.ScenarioStateReader
import org.springframework.stereotype.Service

/**
 * TestCase 도메인 서비스(코루틴). 재사용 케이스 라이브러리의 CRUD를 담당한다.
 *
 * 접근은 프로젝트 참여(project_member)로 인가하며, 비참여자에겐 **null**(존재하지 않는 것처럼)로 응답한다
 * — 컨트롤러가 404로 매핑한다. 값은 자연어 그대로 저장한다. 잘못된 입력은 400으로 막는다.
 *
 * 참고(코루틴 전환): Reactor의 `Mono.empty()` = "숨김" 은 코루틴에서 **nullable 반환(null)** 로 표현된다.
 */
@Service
class TestCaseService(
    private val repository: TestCaseRepository,
    private val projectAccessService: ProjectAccessService,
    private val objectMapper: ObjectMapper,
) {
    /**
     * 화면이 읽는 케이스 목록(최근 것부터). 비참여자면 빈 목록.
     *
     * 씬/검증상태 필터가 있었지만 지웠다 — 보내는 쪽이 없었다. 화면은 전량을 받아 브라우저에서
     * 거르고, 필터를 타는 경로가 없으니 그 코드는 "동작한다"고 말할 근거도 없었다.
     * 서버에서 걸러야 할 만큼 목록이 커지면 그때 실제 소비자에 맞춰 다시 넣는 편이 낫다.
     */
    suspend fun listTestCases(projectId: Long, userId: Long): TestCaseListResponse {
        if (!projectAccessService.isMember(projectId, userId)) return TestCaseListResponse(emptyList())
        val items = repository.findByProjectIdOrderByIdDesc(projectId)
            .map { it.toTestCaseResponse() }
            .toList()
        return TestCaseListResponse(items)
    }

    /**
     * 저작 Agent에 실을 프로젝트 TestCase 전량 목록(ARTEL-318).
     *
     * [listTestCases]와 달리 좁게 낸다. **거르지 않는 것이 이 조회의 목적**이기 때문이다 — 지금 Agent는
     * 벡터 검색으로 30~40건만 보고, 나머지는 존재조차 모른 채 시나리오를 만든다. 그 실패를 없애려면
     * 전량이어야 한다. 전량을 실어도 되는지는 측정으로 답이 났다(1000건 기준 74.4k, 캐시 대상).
     *
     * 정렬을 `id ASC`로 고정하는 이유는 [TestCaseRepository.findTestCaseListByProjectIdOrderByIdAsc]에 적었다.
     *
     * 비참여자에겐 빈 목록 — [listTestCases]와 같은 판단이다(존재 자체를 숨긴다).
     */
    suspend fun getAllTestCases(projectId: Long, userId: Long): AllTestCasesResponse {
        if (!projectAccessService.isMember(projectId, userId)) return AllTestCasesResponse(emptyList())
        return AllTestCasesResponse(repository.findTestCaseListByProjectIdOrderByIdAsc(projectId).toList())
    }

    /**
     * 저작 세션에 실을 전량. [getAllTestCases]에 **정규화된 상태**를 얹은 것이다(ARTEL-466).
     *
     * 사전조건 문장을 Agent와 오케가 각자 해석하던 것을 한쪽으로 모은다 — 두 해석이 어긋나면
     * Agent가 짠 순서를 코드가 다른 상태로 계산해 메우게 되고, 그 어긋남은 화면에 아무 표시도
     * 남기지 않는다. 읽는 규칙은 경로 계산이 쓰는 것과 같은 [ScenarioStateReader]다.
     *
     * 비참여자에겐 빈 목록 — 다른 조회와 같은 판단이다(존재 자체를 숨긴다).
     */
    suspend fun getAuthoringCases(projectId: Long, userId: Long): List<AuthoringTestCase> {
        if (!projectAccessService.isMember(projectId, userId)) return emptyList()
        return repository.findByProjectIdOrderByIdAsc(projectId).map { case ->
            AuthoringTestCase(
                id = case.id!!,
                scene = ScenarioStateReader.sceneOf(case) ?: case.scene,
                step = case.step,
                precondition = case.precondition,
                expectedValue = case.expectedValue,
                verificationStatus = case.verificationStatus,
                stateBefore = ScenarioStateReader.guardsOf(case.precondition)
                    .map { CaseGuard(it.variable, it.operator, it.value) },
                stateAfter = ScenarioStateReader.stateAfter(case, objectMapper),
            )
        }.toList()
    }

    /**
     * 프로젝트의 커버리지(ARTEL-403). 비참여자면 전부 0 — 목록과 같은 판단이다(존재를 숨긴다).
     *
     * **두 축을 함께 낸다.** 저작 커버리지(어떤 시나리오가 참조하는가)와 검증 커버리지(QA 런이
     * 무엇을 냈는가)는 다른 질문이고, 사용자가 할 일도 다르다 — 저작만 되고 안 돌린 케이스는
     * 실행할 것이고, 깨진 케이스는 고칠 것이다.
     *
     * `unauthored`를 따로 내는 것은 화면이 빼기를 하지 않게 하기 위해서다. 같은 수를 두 곳에서
     * 계산하면 언젠가 두 값이 갈리고, 그때 어느 쪽이 맞는지 알 수 없다.
     */
    suspend fun coverage(projectId: Long, userId: Long): TestCaseCoverageResponse {
        if (!projectAccessService.isMember(projectId, userId)) {
            return TestCaseCoverageResponse(0, 0, 0, 0, 0, 0, emptyList())
        }
        val total = repository.countByProjectId(projectId).toInt()
        val uncoveredScenes = repository.findScenesOfUncovered(projectId).toList()
        val unauthored = uncoveredScenes.sumOf { it.count }.toInt()
        return TestCaseCoverageResponse(
            total = total,
            authored = total - unauthored,
            unauthored = unauthored,
            verified = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.VERIFIED.name
            ).toInt(),
            draft = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.DRAFT.name
            ).toInt(),
            broken = repository.countByProjectIdAndVerificationStatus(
                projectId, VerificationStatus.BROKEN.name
            ).toInt(),
            uncoveredScenes = uncoveredScenes,
        )
    }

    /** 케이스 생성. scene/step/expectedValue 필수. 상태는 DRAFT로 시작. 비참여자면 null(→404). */
    suspend fun createTestCase(projectId: Long, userId: Long, request: TestCaseCreateRequest): TestCaseResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val entity = TestCaseEntity(
            projectId = projectId,
            scene = request.scene.requireField("scene"),
            step = request.step.requireField("step"),
            precondition = request.precondition?.ifBlank { null },
            expectedValue = request.expectedValue.requireField("expectedValue"),
            verificationStatus = VerificationStatus.DRAFT.name,
        )
        return repository.save(entity).toTestCaseResponse()
    }

    /**
     * 케이스 단건 조회(프로젝트 참여자만). 없거나 비참여자면 null.
     *
     * 목록과 달리 `evidenceGaps`까지 낸다 — 왜 상세에만 싣는지는 [TestCaseDetailResponse] 참조.
     */
    suspend fun getTestCase(caseId: Long, userId: Long): TestCaseDetailResponse? =
        accessible(caseId, userId)?.toTestCaseDetailResponse(objectMapper)

    /** 케이스 수정. 준 필드만 반영. verificationStatus는 enum 검증. */
    suspend fun updateTestCase(caseId: Long, userId: Long, request: TestCaseUpdateRequest): TestCaseResponse? {
        val existing = accessible(caseId, userId) ?: return null
        val statusName = request.verificationStatus?.let {
            VerificationStatus.fromWire(it)?.name
                ?: throw BadRequestException("verificationStatus must be one of ${VerificationStatus.NAMES}")
        }
        val updated = existing.copy(
            scene = request.scene?.ifBlank { null } ?: existing.scene,
            step = request.step?.ifBlank { null } ?: existing.step,
            precondition = if (request.precondition == null) existing.precondition else request.precondition.ifBlank { null },
            expectedValue = request.expectedValue?.ifBlank { null } ?: existing.expectedValue,
            verificationStatus = statusName ?: existing.verificationStatus,
        )
        return repository.save(updated).toTestCaseResponse()
    }

    /** 케이스 삭제(참여자만). 접근 불가면 조용히 no-op(존재 숨김). 조합 정리는 별도. */
    suspend fun deleteTestCase(caseId: Long, userId: Long) {
        accessible(caseId, userId)?.let { repository.delete(it) }
    }

    private suspend fun accessible(caseId: Long, userId: Long): TestCaseEntity? {
        val entity = repository.findById(caseId) ?: return null
        return if (projectAccessService.isMember(entity.projectId, userId)) entity else null
    }

    private fun String?.requireField(name: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw BadRequestException("$name is required")
}
