package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.AllTestCasesResponse
import kr.artel.orchestration.testcase.dto.TestCaseCoverageResponse
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseDetailResponse
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.DeleteMapping
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.PutMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 재사용 TestCase 라이브러리 REST(외부/인증, 코루틴). FE 캔버스 좌측 사이드바가 프로젝트의 케이스를 나열·편집한다.
 * 컨트롤러는 얇게: 상태코드 매핑(서비스가 null이면 404)만 하고, 비즈니스는 [TestCaseService].
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-cases")
class TestCaseController(
    private val service: TestCaseService
) {

    /**
     * 화면이 읽는 케이스 목록. 프로젝트의 케이스를 최근 것부터(`id DESC`) 전부 낸다.
     *
     * 필터는 없다. 유일한 소비자인 케이스 라이브러리 화면이 전량을 받아 브라우저에서 거르기
     * 때문이다 — 서버에 씬/상태 파라미터가 있었지만 **보내는 쪽이 없어** 지웠다.
     *
     * [getAllTestCases]와 둘 다 "전량"이지만 받는 쪽이 다르다: 이쪽은 화면용이라 id를 문자열로
     * 내고 명세 등급·생성시각까지 담는 [TestCaseResponse]이고, 저쪽은 Agent 계약이라 필드가 좁고
     * snake_case이며 순서가 `id ASC`로 고정된다(캐시 접두사).
     */
    @GetMapping
    suspend fun listTestCases(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseListResponse =
        service.listTestCases(projectId, appUserId)

    /**
     * 저작 Agent 세션에 싣는 전량 목록을 그대로 낸다(ARTEL-318).
     *
     * 세션을 열지 않고 "Agent가 실제로 무엇을 보고 있는지"를 확인하는 창구다. 본문(사전조건/기대결과)까지
     * 담기며, 왜 담는지는 [kr.artel.orchestration.testcase.dto.TestCaseListItem]에 적었다.
     *
     * `/{caseId}`(Long)와 같은 자리를 다투는 것처럼 보이지만, PathPattern은 변수보다 리터럴 구간을
     * 먼저 고르므로 `/all`은 이쪽으로 온다.
     */
    @GetMapping("/all")
    suspend fun getAllTestCases(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): AllTestCasesResponse =
        service.getAllTestCases(projectId, appUserId)

    /**
     * 프로젝트의 커버리지 수치(ARTEL-403). 화면이 "무엇이 아직 안 다뤄졌나"를 보여주는 근거다.
     *
     * `/all`과 같은 이유로 리터럴 경로가 `/{caseId}`보다 먼저 잡힌다.
     */
    @GetMapping("/coverage")
    suspend fun getCoverage(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseCoverageResponse =
        service.coverage(projectId, appUserId)

    @PostMapping
    suspend fun createTestCase(
        @PathVariable projectId: Long,
        @RequestBody request: TestCaseCreateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseResponse> =
        service.createTestCase(projectId, appUserId, request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    /** 케이스 단건. 목록보다 한 필드(`evidenceGaps`) 더 낸다 — [TestCaseDetailResponse] 참조. */
    @GetMapping("/{caseId}")
    suspend fun getTestCase(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseDetailResponse> =
        service.getTestCase(caseId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{caseId}")
    suspend fun updateTestCase(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @RequestBody request: TestCaseUpdateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseResponse> =
        service.updateTestCase(caseId, appUserId, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{caseId}")
    suspend fun deleteTestCase(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.deleteTestCase(caseId, appUserId)
        return ResponseEntity.noContent().build()
    }
}
