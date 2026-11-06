package kr.artel.orchestration.llmmodel.controller

import kr.artel.orchestration.llmmodel.dto.InternalLlmModelResponse
import kr.artel.orchestration.llmmodel.service.LlmModelService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `artel-agent-server` 가 model 카탈로그를 읽는 경로. 꺼진 행도 모두 싣는다 — 지난 사용량의 이름과 단가를
 * 되짚고, router 와 compaction 같은 내부 호출은 꺼진 model 도 쓸 수 있어야 해서다.
 *
 * `/internal/` 아래라 내부 포트에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 */
@RestController
@RequestMapping("/internal/llm-models")
class InternalLlmModelController(private val service: LlmModelService) {

    @GetMapping
    suspend fun list(): List<InternalLlmModelResponse> = service.listForAgent()
}
