package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.auth.service.SessionUserResolver
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.dto.SendQaMessageRequest
import kr.artel.orchestration.qa.service.QaTryService
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.security.core.annotation.AuthenticationPrincipal
import org.springframework.security.oauth2.jwt.Jwt
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import org.springframework.web.server.ResponseStatusException
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

/** Matches the Agent's own payload guard; longer input is a paste, not a message. */
private const val MAX_QA_MESSAGE_LENGTH = 4000

@RestController
@RequestMapping("/api/qa-tries")
class QaTryController(
    private val service: QaTryService,
    private val userResolver: SessionUserResolver
) {
    @PostMapping
    fun create(
        @RequestBody request: CreateQaTryRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<QaTryResponse>> =
        service.create(parseId(request.testScenarioId), parseId(request.gameInstanceId), requireUser(jwt))
            .map { ResponseEntity.status(HttpStatus.CREATED).body(it) }

    /** One project's runs, newest first — the way back to a run after its URL is lost. */
    @GetMapping
    fun list(
        @RequestParam projectId: String,
        @RequestParam(defaultValue = "20") size: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<List<QaTryResponse>>> {
        if (size !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100")
        return service.listByProject(parseId(projectId), requireUser(jwt), size)
            .collectList()
            .map { ResponseEntity.ok(it) }
    }

    @GetMapping("/{qaTryId}")
    fun get(
        @PathVariable qaTryId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<QaTryResponse>> =
        service.get(parseId(qaTryId), requireUser(jwt))
            .map { ResponseEntity.ok(it) }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))

    /**
     * Sends one operator message to the running Agent.
     *
     * Accepted, not answered: the reply arrives on the log stream like every other
     * frame, so the caller does not block on the model.
     */
    @PostMapping("/{qaTryId}/messages")
    fun sendMessage(
        @PathVariable qaTryId: String,
        @RequestBody request: SendQaMessageRequest,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<Void>> {
        val message = request.message.trim()
        if (message.isEmpty()) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message must not be blank")
        }
        if (message.length > MAX_QA_MESSAGE_LENGTH) {
            throw ResponseStatusException(HttpStatus.BAD_REQUEST, "message is too long")
        }
        return service.sendMessage(parseId(qaTryId), requireUser(jwt), message)
            .thenReturn(ResponseEntity.accepted().build())
    }

    /** Ends a running QA Try. Already-ended runs answer 409. */
    @PostMapping("/{qaTryId}/cancel")
    fun cancel(
        @PathVariable qaTryId: String,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<Void>> =
        service.cancel(parseId(qaTryId), requireUser(jwt))
            .thenReturn(ResponseEntity.noContent().build())

    @GetMapping("/{qaTryId}/logs")
    fun logs(
        @PathVariable qaTryId: String,
        @RequestParam(required = false) beforeId: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @AuthenticationPrincipal jwt: Jwt
    ): Mono<ResponseEntity<QaLogPageResponse>> {
        if (size !in 1..100) throw ResponseStatusException(HttpStatus.BAD_REQUEST, "size must be between 1 and 100")
        return service.logs(parseId(qaTryId), requireUser(jwt), beforeId?.let(::parseId), size)
            .map { ResponseEntity.ok(it) }
            .switchIfEmpty(Mono.just(ResponseEntity.notFound().build()))
    }

    @GetMapping("/{qaTryId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable qaTryId: String,
        @RequestParam(required = false) afterId: String?,
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?,
        @AuthenticationPrincipal jwt: Jwt
    ): Flux<ServerSentEvent<QaLogResponse>> {
        val cursor = (lastEventId ?: afterId)?.let(::parseId) ?: 0L
        return service.events(parseId(qaTryId), requireUser(jwt), cursor)
            .map { log ->
                ServerSentEvent.builder(log)
                    .id(log.id)
                    .event("log")
                    .build()
            }
    }

    private fun requireUser(jwt: Jwt): Long =
        userResolver.resolve(jwt)?.userId ?: throw ResponseStatusException(HttpStatus.UNAUTHORIZED)

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw ResponseStatusException(HttpStatus.BAD_REQUEST, "ID must be a signed 64-bit decimal string")
}
