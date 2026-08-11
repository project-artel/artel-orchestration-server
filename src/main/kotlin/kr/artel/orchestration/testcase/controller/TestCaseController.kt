package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.testcase.dto.TestCaseCatalogResponse
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
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
import org.springframework.web.bind.annotation.RequestParam
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

    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) status: String?,
        @CurrentUserId appUserId: Long
    ): TestCaseListResponse =
        service.list(projectId, appUserId, category, status)

    /**
     * 저작 Agent 세션에 싣는 전량 목록을 그대로 낸다(ARTEL-318).
     *
     * 세션을 열지 않고 "Agent가 실제로 무엇을 보고 있는지"를 확인하는 창구다. 본문(사전조건/기대결과)은
     * 없다 — 목록의 일은 "무엇이 존재하는가"까지이고, 본문은 Agent가 id로 따로 조회한다.
     *
     * `/{caseId}`(Long)와 같은 자리를 다투는 것처럼 보이지만, PathPattern은 변수보다 리터럴 구간을
     * 먼저 고르므로 `/catalog`는 이쪽으로 온다.
     */
    @GetMapping("/catalog")
    suspend fun getTestCaseCatalog(
        @PathVariable projectId: Long,
        @CurrentUserId appUserId: Long
    ): TestCaseCatalogResponse =
        service.getTestCaseCatalog(projectId, appUserId)

    @PostMapping
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestCaseCreateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseResponse> =
        service.create(projectId, appUserId, request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{caseId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseResponse> =
        service.get(caseId, appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{caseId}")
    suspend fun update(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @RequestBody request: TestCaseUpdateRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<TestCaseResponse> =
        service.update(caseId, appUserId, request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{caseId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.delete(caseId, appUserId)
        return ResponseEntity.noContent().build()
    }
}
