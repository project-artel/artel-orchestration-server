package kr.artel.orchestration.qa.controller

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.common.error.UnauthorizedException
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.QaRunResponse
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.qa.service.toRunSettings
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 런(TR) 단위 QA 실행 시작(ARTEL-259). 런의 시나리오들을 한 세션이 순차 실행하고 사이에 게임을
 * 초기화한다. 시나리오별 결과·이슈·통계는 여전히 qa_try 기준이므로 조회는 /api/qa-tries로 한다.
 */
@RestController
@RequestMapping("/api/qa-runs")
class QaRunController(
    private val service: QaTryService,
    private val userResolver: SessionUserResolver,
    private val objectMapper: ObjectMapper
) {
    @PostMapping
    suspend fun create(
        @RequestBody request: CreateQaRunRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): ResponseEntity<QaRunResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            service.createRun(
                parseId(request.testRunId),
                parseId(request.gameInstanceId),
                requireUser(jwt),
                request.toRunSettings(objectMapper)
            )
        )

    /** 런 하나 + 시나리오별 try 상태. FE 런 화면이 종단까지 폴링하는 개요. */
    @GetMapping("/{id}")
    suspend fun get(
        @PathVariable id: String,
        @AuthenticationPrincipal jwt: Jwt
    ): QaRunResponse =
        service.getRun(parseId(id), requireUser(jwt)) ?: throw NotFoundException()

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw UnauthorizedException()

    private fun parseId(raw: String): Long =
        raw.toLongOrNull() ?: throw BadRequestException("invalid id: $raw")
}
