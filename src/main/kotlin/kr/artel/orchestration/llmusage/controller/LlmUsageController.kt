package kr.artel.orchestration.llmusage.controller

import jakarta.validation.Valid
import kr.artel.orchestration.llmusage.dto.LlmUsageBatchRequest
import kr.artel.orchestration.llmusage.service.LlmUsageService
import org.springframework.http.HttpStatus
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.ResponseStatus
import org.springframework.web.bind.annotation.RestController

/**
 * agent-server가 LLM 사용량을 밀어 넣는 수집 엔드포인트(ARTEL-233).
 *
 * 내부 서버-투-서버 경로라 인증이 없다(SecurityConfig가 `/internal/`를 통째로 permitAll로 연다).
 * 응답 본문도 없다 — 보내는 쪽은 결과를 보지 않고 재시도도 하지 않으므로 204면 충분하다.
 */
@RestController
@RequestMapping("/internal/llm-usage")
class LlmUsageController(
    private val llmUsageService: LlmUsageService
) {
    @PostMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    suspend fun record(@Valid @RequestBody request: LlmUsageBatchRequest) {
        llmUsageService.record(request.records)
    }
}
