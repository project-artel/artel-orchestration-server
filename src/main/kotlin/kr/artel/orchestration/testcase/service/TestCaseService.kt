package kr.artel.orchestration.testcase.service

import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.dto.toTestCaseResponse
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.VerificationStatus
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import org.springframework.http.HttpStatus
import org.springframework.stereotype.Service
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/**
 * TestCase 도메인 서비스. 재사용 케이스 라이브러리의 CRUD를 담당한다.
 *
 * 접근은 프로젝트 참여(project_member)로 인가하며, 비참여자에겐 **빈 결과**(존재하지 않는 것처럼)로 응답한다.
 * 값은 자연어 그대로 저장한다(최적화용 구조화 술어는 후속). 잘못된 입력은 400으로 막는다.
 */
@Service
class TestCaseService(
    private val repository: TestCaseRepository,
    private val projectMemberRepository: ProjectMemberRepository,
) {
    private fun isMember(projectId: Long, userId: Long): Mono<Boolean> =
        projectMemberRepository.findByProjectIdAndAppUserId(projectId, userId).hasElement()

    /** 프로젝트의 케이스 목록. category/verificationStatus로 선택 필터. 비참여자면 빈 목록. */
    fun list(projectId: Long, userId: Long, category: String?, status: String?): Mono<TestCaseListResponse> {
        val statusName = status?.let {
            VerificationStatus.fromWire(it)?.name
                ?: return Mono.error(ResponseStatusException(HttpStatus.BAD_REQUEST, "verificationStatus must be one of ${VerificationStatus.NAMES}"))
        }
        return isMember(projectId, userId).flatMap { member ->
            if (!member) return@flatMap Mono.just(TestCaseListResponse(emptyList()))
            val source: Flux<TestCaseEntity> = when {
                category != null -> repository.findByProjectIdAndCategoryOrderByIdDesc(projectId, category)
                statusName != null -> repository.findByProjectIdAndVerificationStatusOrderByIdDesc(projectId, statusName)
                else -> repository.findByProjectIdOrderByIdDesc(projectId)
            }
            source
                .filter { statusName == null || it.verificationStatus == statusName }
                .filter { category == null || it.category == category }
                .map { it.toTestCaseResponse() }
                .collectList()
                .map { TestCaseListResponse(it) }
        }
    }

    /** 케이스 생성. category/title/expected 필수. 상태는 DRAFT로 시작. 비참여자면 빈 Mono(→404). */
    fun create(projectId: Long, userId: Long, request: TestCaseCreateRequest): Mono<TestCaseResponse> =
        isMember(projectId, userId).flatMap { member ->
            if (!member) return@flatMap Mono.empty()
            val entity = TestCaseEntity(
                projectId = projectId,
                category = request.category.requireField("category"),
                title = request.title.requireField("title"),
                precondition = request.precondition?.ifBlank { null },
                expected = request.expected.requireField("expected"),
                verificationStatus = VerificationStatus.DRAFT.name,
            )
            repository.save(entity).map { it.toTestCaseResponse() }
        }

    /** 케이스 단건 조회(프로젝트 참여자만). 없거나 비참여자면 빈 Mono. */
    fun get(caseId: Long, userId: Long): Mono<TestCaseResponse> =
        accessible(caseId, userId).map { it.toTestCaseResponse() }

    /** 케이스 수정. 준 필드만 반영. verificationStatus는 enum 검증. */
    fun update(caseId: Long, userId: Long, request: TestCaseUpdateRequest): Mono<TestCaseResponse> =
        accessible(caseId, userId).flatMap { existing ->
            val statusName = request.verificationStatus?.let {
                VerificationStatus.fromWire(it)?.name
                    ?: return@flatMap Mono.error<TestCaseResponse>(ResponseStatusException(HttpStatus.BAD_REQUEST, "verificationStatus must be one of ${VerificationStatus.NAMES}"))
            }
            val updated = existing.copy(
                category = request.category?.ifBlank { null } ?: existing.category,
                title = request.title?.ifBlank { null } ?: existing.title,
                precondition = if (request.precondition == null) existing.precondition else request.precondition.ifBlank { null },
                expected = request.expected?.ifBlank { null } ?: existing.expected,
                verificationStatus = statusName ?: existing.verificationStatus,
            )
            repository.save(updated).map { it.toTestCaseResponse() }
        }

    /** 케이스 삭제(참여자만). 조합(test_scenario_case)은 별도 정리 대상 — 여기선 케이스 행만 지운다. */
    fun delete(caseId: Long, userId: Long): Mono<Void> =
        accessible(caseId, userId).flatMap { repository.delete(it) }

    private fun accessible(caseId: Long, userId: Long): Mono<TestCaseEntity> =
        repository.findById(caseId).filterWhen { isMember(it.projectId, userId) }

    private fun String?.requireField(name: String): String =
        this?.takeIf { it.isNotBlank() }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "$name is required")
}
