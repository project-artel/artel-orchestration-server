package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.common.error.BadRequestException
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.issue.dto.IssuePageResponse
import kr.artel.orchestration.issue.service.IssueService
import kr.artel.orchestration.qa.dto.CreateQaTryRequest
import kr.artel.orchestration.qa.dto.QaLogPageResponse
import kr.artel.orchestration.qa.dto.QaLogResponse
import kr.artel.orchestration.qa.dto.QaTryDetailResponse
import kr.artel.orchestration.qa.dto.QaTryResponse
import kr.artel.orchestration.qa.dto.SendQaMessageRequest
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.qa.service.QaTryDetailService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.qa.service.toContentMapMode
import kr.artel.orchestration.qa.service.toKnowledgeSettings
import kr.artel.orchestration.qa.service.toRunSettings
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.http.ResponseEntity
import org.springframework.http.codec.ServerSentEvent
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestHeader
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController

/** Matches the Agent's own payload guard; longer input is a paste, not a message. */
private const val MAX_QA_MESSAGE_LENGTH = 4000

@RestController
@RequestMapping("/api/qa-tries")
class QaTryController(
    private val service: QaTryService,
    private val issueService: IssueService,
    private val detailService: QaTryDetailService,
    private val objectMapper: ObjectMapper
) {
    @PostMapping
    suspend fun create(
        @RequestBody request: CreateQaTryRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaTryResponse> =
        ResponseEntity.status(HttpStatus.CREATED).body(
            service.create(
                parseId(request.testScenarioId),
                parseId(request.gameInstanceId),
                appUserId,
                request.toRunSettings(objectMapper),
                request.toKnowledgeSettings(),
                request.toContentMapMode()
            )
        )

    /** One project's runs, newest first — the way back to a run after its URL is lost. */
    @GetMapping
    suspend fun list(
        @RequestParam projectId: String,
        @RequestParam(defaultValue = "20") size: Int,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<List<QaTryResponse>> {
        if (size !in 1..100) throw BadRequestException("size must be between 1 and 100")
        return ResponseEntity.ok(service.listByProject(parseId(projectId), appUserId, size))
    }

    @GetMapping("/{qaTryId}")
    suspend fun get(
        @PathVariable qaTryId: String,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaTryResponse> =
        service.get(parseId(qaTryId), appUserId)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()

    /**
     * Sends one operator message to the running Agent.
     *
     * Accepted, not answered: the reply arrives on the log stream like every other
     * frame, so the caller does not block on the model.
     */
    @PostMapping("/{qaTryId}/messages")
    suspend fun sendMessage(
        @PathVariable qaTryId: String,
        @RequestBody request: SendQaMessageRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        val message = request.message.trim()
        if (message.isEmpty()) {
            throw BadRequestException("message must not be blank")
        }
        if (message.length > MAX_QA_MESSAGE_LENGTH) {
            throw BadRequestException("message is too long")
        }
        service.sendMessage(parseId(qaTryId), appUserId, message)
        return ResponseEntity.accepted().build()
    }

    /** Ends a running QA Try. Already-ended runs answer 409. */
    @PostMapping("/{qaTryId}/cancel")
    suspend fun cancel(
        @PathVariable qaTryId: String,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        service.cancel(parseId(qaTryId), appUserId)
        return ResponseEntity.noContent().build()
    }

    @GetMapping("/{qaTryId}/logs")
    suspend fun logs(
        @PathVariable qaTryId: String,
        @RequestParam(required = false) beforeId: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaLogPageResponse> {
        if (size !in 1..100) throw BadRequestException("size must be between 1 and 100")
        return service.logs(parseId(qaTryId), appUserId, beforeId?.let(::parseId), size)
            ?.let { ResponseEntity.ok(it) }
            ?: ResponseEntity.notFound().build()
    }

    /**
     * QA 히스토리에서 이 실행을 펼쳤을 때의 상세(ARTEL-819).
     *
     * 목록 응답([QaTryResponse])에 안 실은 값들이다 — 네 표를 접어야 나오는 수라, 수십 행을
     * 그리는 목록마다 지고 가면 안 펼친 행의 값까지 매번 계산하게 된다.
     */
    @GetMapping("/{qaTryId}/detail")
    suspend fun detail(
        @PathVariable qaTryId: String,
        @CurrentUserId appUserId: Long
    ): QaTryDetailResponse = detailService.detail(parseId(qaTryId), appUserId)

    /**
     * 이 실행이 남긴 이슈, 최신순 한 페이지(ARTEL-245).
     *
     * 로그와 같은 자리에 있지만 다른 도메인이다 — `logs`가 `QaLogService`에 위임하듯 이쪽은
     * `IssueService`에 위임한다. 접근 판정도 그 서비스가 한다.
     */
    @GetMapping("/{qaTryId}/issues")
    suspend fun issues(
        @PathVariable qaTryId: String,
        @RequestParam(required = false) beforeId: String?,
        @RequestParam(defaultValue = "50") size: Int,
        @CurrentUserId appUserId: Long
    ): IssuePageResponse = issueService.listByQaTry(
        parseId(qaTryId),
        appUserId,
        beforeId?.let(::parseId),
        size
    )

    @GetMapping("/{qaTryId}/events", produces = [MediaType.TEXT_EVENT_STREAM_VALUE])
    fun events(
        @PathVariable qaTryId: String,
        @RequestParam(required = false) afterId: String?,
        @RequestHeader(name = "Last-Event-ID", required = false) lastEventId: String?,
        @CurrentUserId appUserId: Long
    ): Flow<ServerSentEvent<QaLogResponse>> {
        val cursor = (lastEventId ?: afterId)?.let(::parseId) ?: 0L
        return service.events(parseId(qaTryId), appUserId, cursor)
            .map { log ->
                ServerSentEvent.builder(log)
                    .id(log.id)
                    .event("log")
                    .build()
            }
    }

    private fun parseId(value: String): Long =
        value.takeIf { it.isNotEmpty() && it.all(Char::isDigit) }
            ?.toLongOrNull()
            ?.takeIf { it >= 0 }
            ?: throw BadRequestException("ID must be a signed 64-bit decimal string")
}
