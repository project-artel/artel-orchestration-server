package kr.artel.orchestration.testrun.controller

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.testrun.dto.RunScenariosResponse
import kr.artel.orchestration.testrun.dto.SetRunScenariosRequest
import kr.artel.orchestration.testrun.dto.TestRunCreateRequest
import kr.artel.orchestration.testrun.dto.TestRunListResponse
import kr.artel.orchestration.testrun.dto.TestRunResponse
import kr.artel.orchestration.testrun.dto.TestRunUpdateRequest
import kr.artel.orchestration.testrun.service.TestRunService
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
import org.springframework.web.bind.annotation.RestController

/**
 * TestRun REST(외부/인증, 코루틴). 여러 시나리오를 묶은 실행 세트를 만들고 시나리오 조합을 편집한다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-runs")
class TestRunController(
    private val service: TestRunService,
    private val sessionUserResolver: SessionUserResolver
) {

    @GetMapping
    suspend fun list(
        @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): TestRunListResponse =
        service.list(projectId, requireUser(jwt))

    @PostMapping
    suspend fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestRunCreateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.create(projectId, requireUser(jwt), request)
            ?.let { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            ?: ResponseEntity.notFound().build()

    @GetMapping("/{runId}")
    suspend fun get(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.get(runId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}")
    suspend fun update(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunUpdateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<TestRunResponse> =
        service.update(runId, requireUser(jwt), request)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @DeleteMapping("/{runId}")
    suspend fun delete(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<Void> {
        service.delete(runId, requireUser(jwt))
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{runId}/scenarios")
    suspend fun getScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<RunScenariosResponse> =
        service.getScenarios(runId, requireUser(jwt))
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    @PutMapping("/{runId}/scenarios")
    suspend fun setScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: SetRunScenariosRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<RunScenariosResponse> {
        val scenarioIds = request.scenarioIds.map {
            it.toLongOrNull() ?: throw BadRequestException("invalid scenarioId: $it")
        }
        return service.setScenarios(runId, requireUser(jwt), scenarioIds)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw UnauthorizedException()
}
