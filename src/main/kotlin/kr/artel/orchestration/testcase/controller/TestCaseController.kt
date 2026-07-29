package kr.artel.orchestration.testcase.controller

import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testcase.dto.TestCaseCreateRequest
import kr.artel.orchestration.testcase.dto.TestCaseListResponse
import kr.artel.orchestration.testcase.dto.TestCaseResponse
import kr.artel.orchestration.testcase.dto.TestCaseUpdateRequest
import kr.artel.orchestration.testcase.service.TestCaseService
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
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
 * 컨트롤러는 얇게: JWT→userId 추출 + 상태코드 매핑(서비스가 null이면 404), 비즈니스는 [TestCaseService].
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-cases")
class TestCaseController(
    private val service: TestCaseService,
    private val sessionUserResolver: SessionUserResolver
) {

    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @RequestParam(required = false) category: String?,
        @RequestParam(required = false) status: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): TestCaseListResponse =
        service.list(projectId, requireUser(jwt), category, status)

    @PostMapping
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestCaseCreateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestCaseResponse> =
        service.create(projectId, requireUser(jwt), request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{caseId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestCaseResponse> =
        service.get(caseId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{caseId}")
    suspend fun update(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @RequestBody request: TestCaseUpdateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestCaseResponse> =
        service.update(caseId, requireUser(jwt), request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{caseId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable caseId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.delete(caseId, requireUser(jwt))
        return ResponseEntity.noContent().build()
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
