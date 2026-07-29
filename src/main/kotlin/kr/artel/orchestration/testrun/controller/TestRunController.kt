package kr.artel.orchestration.testrun.controller

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
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Mono

/**
 * TestRun REST(외부/인증). 여러 시나리오를 묶은 실행 세트를 만들고 시나리오 조합을 편집한다.
 */
@RestController
@RequestMapping("/api/projects/{projectId}/test-runs")
class TestRunController(
    private val service: TestRunService,
    private val sessionUserResolver: SessionUserResolver
) {

    @GetMapping
    fun list(
        @PathVariable projectId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<TestRunListResponse> =
        service.list(projectId, requireUser(jwt))

    @PostMapping
    fun create(
        @PathVariable projectId: Long,
        @RequestBody request: TestRunCreateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<TestRunResponse>> =
        service.create(projectId, requireUser(jwt), request)
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    @GetMapping("/{runId}")
    fun get(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<TestRunResponse>> =
        service.get(runId, requireUser(jwt))
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    @PutMapping("/{runId}")
    fun update(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: TestRunUpdateRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<TestRunResponse>> =
        service.update(runId, requireUser(jwt), request)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    @DeleteMapping("/{runId}")
    fun delete(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<Void>> =
        service.delete(runId, requireUser(jwt))
            .then(Mono.just(ResponseEntity.noContent().build<Void>()))
            .defaultIfEmpty(ResponseEntity.noContent().build())

    @GetMapping("/{runId}/scenarios")
    fun getScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<RunScenariosResponse>> =
        service.getScenarios(runId, requireUser(jwt))
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())

    @PutMapping("/{runId}/scenarios")
    fun setScenarios(
        @PathVariable projectId: Long,
        @PathVariable runId: Long,
        @RequestBody request: SetRunScenariosRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<RunScenariosResponse>> {
        val scenarioIds = request.scenarioIds.map {
            it.toLongOrNull() ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "invalid scenarioId: $it")
        }
        return service.setScenarios(runId, requireUser(jwt), scenarioIds)
            .map { ResponseEntity.ok(it) }
            .defaultIfEmpty(ResponseEntity.notFound().build())
    }

    private fun requireUser(jwt: Jwt): Long =
        sessionUserResolver.resolve(jwt)?.userId
            ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)
}
