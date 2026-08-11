package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.filter
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.testcase.dto.TestCaseCatalogResponse
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
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
) {
    /** 프로젝트의 케이스 목록. category/verificationStatus로 선택 필터. 비참여자면 빈 목록. */
    suspend fun list(projectId: Long, userId: Long, category: String?, status: String?): TestCaseListResponse {
        val statusName = status?.let {
            VerificationStatus.fromWire(it)?.name
                ?: throw BadRequestException("verificationStatus must be one of ${VerificationStatus.NAMES}")
        }
        if (!projectAccessService.isMember(projectId, userId)) return TestCaseListResponse(emptyList())
        val source = when {
            category != null -> repository.findByProjectIdAndCategoryOrderByIdDesc(projectId, category)
            statusName != null -> repository.findByProjectIdAndVerificationStatusOrderByIdDesc(projectId, statusName)
            else -> repository.findByProjectIdOrderByIdDesc(projectId)
        }
        val items = source
            .filter { statusName == null || it.verificationStatus == statusName }
            .filter { category == null || it.category == category }
            .map { it.toTestCaseResponse() }
            .toList()
        return TestCaseListResponse(items)
    }

    /**
     * 저작 Agent에 실을 프로젝트 TestCase 전량 목록(ARTEL-318).
     *
     * [list]와 달리 필터가 없다. **거르지 않는 것이 이 조회의 목적**이기 때문이다 — 지금 Agent는
     * 벡터 검색으로 30~40건만 보고, 나머지는 존재조차 모른 채 시나리오를 만든다. 그 실패를 없애려면
     * 전량이어야 한다. 전량을 실어도 되는지는 측정으로 답이 났다(1000건 기준 74.4k, 캐시 대상).
     *
     * 정렬을 `id ASC`로 고정하는 이유는 [TestCaseRepository.findCatalogByProjectIdOrderByIdAsc]에 적었다.
     *
     * 비참여자에겐 빈 목록 — [list]와 같은 판단이다(존재 자체를 숨긴다).
     */
    suspend fun getTestCaseCatalog(projectId: Long, userId: Long): TestCaseCatalogResponse {
        if (!projectAccessService.isMember(projectId, userId)) return TestCaseCatalogResponse(emptyList())
        return TestCaseCatalogResponse(repository.findCatalogByProjectIdOrderByIdAsc(projectId).toList())
    }

    /** 케이스 생성. category/title/expected 필수. 상태는 DRAFT로 시작. 비참여자면 null(→404). */
    suspend fun create(projectId: Long, userId: Long, request: TestCaseCreateRequest): TestCaseResponse? {
        if (!projectAccessService.isMember(projectId, userId)) return null
        val entity = TestCaseEntity(
            projectId = projectId,
            category = request.category.requireField("category"),
            title = request.title.requireField("title"),
            precondition = request.precondition?.ifBlank { null },
            expected = request.expected.requireField("expected"),
            verificationStatus = VerificationStatus.DRAFT.name,
        )
        return repository.save(entity).toTestCaseResponse()
    }

    /** 케이스 단건 조회(프로젝트 참여자만). 없거나 비참여자면 null. */
    suspend fun get(caseId: Long, userId: Long): TestCaseResponse? =
        accessible(caseId, userId)?.toTestCaseResponse()

    /** 케이스 수정. 준 필드만 반영. verificationStatus는 enum 검증. */
    suspend fun update(caseId: Long, userId: Long, request: TestCaseUpdateRequest): TestCaseResponse? {
        val existing = accessible(caseId, userId) ?: return null
        val statusName = request.verificationStatus?.let {
            VerificationStatus.fromWire(it)?.name
                ?: throw BadRequestException("verificationStatus must be one of ${VerificationStatus.NAMES}")
        }
        val updated = existing.copy(
            category = request.category?.ifBlank { null } ?: existing.category,
            title = request.title?.ifBlank { null } ?: existing.title,
            precondition = if (request.precondition == null) existing.precondition else request.precondition.ifBlank { null },
            expected = request.expected?.ifBlank { null } ?: existing.expected,
            verificationStatus = statusName ?: existing.verificationStatus,
        )
        return repository.save(updated).toTestCaseResponse()
    }

    /** 케이스 삭제(참여자만). 접근 불가면 조용히 no-op(존재 숨김). 조합 정리는 별도. */
    suspend fun delete(caseId: Long, userId: Long) {
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
