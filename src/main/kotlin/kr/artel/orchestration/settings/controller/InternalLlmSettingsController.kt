package kr.artel.orchestration.settings.controller

import kr.artel.orchestration.settings.service.BedrockKeyService
import kr.artel.orchestration.settings.service.LlmKeyService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * `GET /internal/settings/llm` 응답. key 원문이 나가는 유일한 응답이다.
 *
 * [toString] 을 덮어쓰는 이유는 `UpdateLlmKeyRequest` 와 같다 — Spring 이 DEBUG 로그에 응답 객체를 남긴다.
 */
data class InternalLlmSettingsResponse(
    /** 관리 화면에서 넣은 OpenRouter API key. 없으면 null 이고, agent-server 는 OpenRouter 를 부르지 못한다. */
    val openrouterApiKey: String?,
    /** 관리 화면에서 넣은 Bedrock API key. 없으면 null 이고, agent-server 는 AWS 기본 credential chain 을 쓴다. */
    val bedrockApiKey: String?
) {
    override fun toString(): String =
        "InternalLlmSettingsResponse(openrouterApiKeySet=${openrouterApiKey != null}, bedrockApiKeySet=${bedrockApiKey != null})"
}

/**
 * `artel-agent-server` 가 OpenRouter 와 Bedrock API key 를 묻는 경로.
 *
 * `/internal/` 아래라 내부 포트(8081)에만 있고 인증이 없다(`.agents/docs/project.md` 의 신뢰 경계).
 * 공개 포트에서 이 경로는 404 다.
 */
@RestController
@RequestMapping("/internal/settings/llm")
class InternalLlmSettingsController(
    private val llmKeyService: LlmKeyService,
    private val bedrockKeyService: BedrockKeyService
) {

    @GetMapping
    suspend fun llmSettings(): InternalLlmSettingsResponse = InternalLlmSettingsResponse(
        openrouterApiKey = llmKeyService.effectiveKey().apiKey,
        bedrockApiKey = bedrockKeyService.storedKey()
    )
}
