package kr.artel.orchestration.settings.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * OpenRouter API key 를 확인하는 호출.
 *
 * key 는 관리 화면에서 넣은 값(`platform_setting`)뿐이다. 환경 변수로 받는 길은 없다. 이 서버는 key 로 LLM 을
 * 부르지 않는다. 쥐고 있다가 `artel-agent-server` 가 내부 포트로 물으면 건넬 뿐이다.
 */
@ConfigurationProperties("artel.llm")
data class LlmKeyProperties(
    /** `GET {base}/key` 와 `GET {base}/models` 를 부를 주소. 테스트가 가짜 서버로 돌린다. */
    val openrouterBaseUrl: String = "https://openrouter.ai/api/v1",
    /** 확인 한 번의 응답 상한. 관리 화면의 버튼 하나가 기다리는 시간이다. */
    val checkTimeout: Duration = Duration.ofSeconds(15)
)

/**
 * Bedrock key 확인 호출이 가는 곳. key 자체는 `platform_setting` 에만 있고 여기에는 없다.
 */
@ConfigurationProperties("artel.bedrock")
data class BedrockProperties(
    /** `BEDROCK_REGION`. agent-server 의 같은 이름 환경 변수와 같은 값이어야 확인 결과가 실제 호출과 맞는다. */
    val region: String = "us-west-2",
    /**
     * Converse 를 부를 주소. 비우면 `https://bedrock-runtime.{region}.amazonaws.com` 이다. 테스트가 가짜
     * 서버로 돌린다.
     */
    val runtimeBaseUrl: String = "",
    /** model 하나를 확인하는 호출의 응답 상한. */
    val checkTimeout: Duration = Duration.ofSeconds(15)
) {
    init {
        require(region.isNotBlank()) { "artel.bedrock.region must not be blank" }
    }

    /** 실제로 부를 주소. 끝의 `/` 는 뗀다. */
    val resolvedRuntimeBaseUrl: String
        get() = runtimeBaseUrl.trim().ifEmpty { "https://bedrock-runtime.$region.amazonaws.com" }.trimEnd('/')
}

/**
 * Claude API(Anthropic API) key 확인 호출이 가는 곳. key 자체는 `platform_setting` 에만 있고 여기에는 없다.
 */
@ConfigurationProperties("artel.anthropic")
data class AnthropicProperties(
    /** `GET {baseUrl}/v1/models` 를 부를 주소. 테스트가 가짜 서버로 돌린다. */
    val baseUrl: String = "https://api.anthropic.com",
    /** 확인 한 번의 응답 상한. 관리 화면의 버튼 하나가 기다리는 시간이다. */
    val checkTimeout: Duration = Duration.ofSeconds(15)
) {
    init {
        require(baseUrl.isNotBlank()) { "artel.anthropic.base-url must not be blank" }
    }
}

/**
 * 사용자가 넣은 OpenAI API key 를 확인하는 호출이 가는 곳. key 자체는 `user_llm_key` 에만 있고 여기에는 없다.
 */
@ConfigurationProperties("artel.openai")
data class OpenAiProperties(
    /** `GET {baseUrl}/v1/models` 를 부를 주소. 테스트가 가짜 서버로 돌린다. */
    val baseUrl: String = "https://api.openai.com",
    /** 확인 한 번의 응답 상한. 사용자가 key 를 저장하는 요청 하나가 기다리는 시간이다. */
    val checkTimeout: Duration = Duration.ofSeconds(15)
) {
    init {
        require(baseUrl.isNotBlank()) { "artel.openai.base-url must not be blank" }
    }
}

/**
 * agent-server 가 들고 있는 key 와 카탈로그 캐시를 비우는 호출. `artel.agent.base-url` 은 다른 agent 호출도
 * 쓰는 값이고, 그쪽은 아직 `@Value` 로 읽는다.
 */
@ConfigurationProperties("artel.agent")
data class AgentServerProperties(
    /** `/internal` 까지 포함한 agent-server 주소(`http://agent-server:8000/internal`). */
    val baseUrl: String = "http://localhost:8000",
    /** `POST {baseUrl}/llm-cache/invalidate` 의 응답 상한. 저장 응답이 이만큼까지 늦어질 수 있다. */
    val cacheInvalidateTimeout: Duration = Duration.ofSeconds(3),
    /**
     * 관리 화면에서 시나리오 저작 model 을 고르지 않았을 때 쓰는 model(`ARTEL_AGENT_MODEL`). 비어 있으면
     * agent-server 의 `DEFAULT_MODEL` 을 쓴다.
     */
    val model: String = "",
    /**
     * `GET {baseUrl}/macros/grammar` 와 `POST {baseUrl}/macros/check` 의 응답 상한(ARTEL-1088). 검사는 parser 와
     * selector 검사뿐이라 LLM 을 부르지 않는다. 개발자의 에이전트가 이 응답을 기다린다.
     */
    val macroTimeout: Duration = Duration.ofSeconds(10)
)

/** 이 문구 중 하나를 (대소문자 무시하고) 담은 `ARTEL_SECRETS_KEY` 는 예시 값을 그대로 둔 것으로 본다. */
private val PLACEHOLDER_FRAGMENTS = listOf("change_me", "change-me", "changeme", "replace-with", "replace_with", "your-secret", "secret")

private const val MIN_SECRETS_KEY_LENGTH = 32

/**
 * `platform_setting` 의 암호화된 값을 여는 비밀. `ARTEL_SECRETS_KEY` 다.
 *
 * 비어 있어도 서버는 뜬다. 그때는 관리 화면에서 key 를 저장할 수 없고(409 `secrets_key_missing`),
 * agent-server 는 OpenRouter, Bedrock, Claude API key 없이 돈다. 비어 있지 않은 값은 [MIN_SECRETS_KEY_LENGTH] 글자 이상이어야 하고
 * 예시 문구([PLACEHOLDER_FRAGMENTS])를 담으면 안 된다. 어기면 서버가 뜨지 않는다. 값은 오류 메시지에
 * 싣지 않는다. `openssl rand -hex 32` 가 만드는 64 글자 hex 는 통과한다.
 *
 * 이 값을 바꾸면 이미 저장된 값을 열 수 없게 되므로, OpenRouter, Bedrock, Claude API key 를 관리 화면에서 다시 넣어야 한다.
 */
@ConfigurationProperties("artel.secrets")
data class SecretsProperties(
    val key: String = ""
) {
    init {
        if (key.isNotEmpty()) {
            require(key.length >= MIN_SECRETS_KEY_LENGTH) {
                "ARTEL_SECRETS_KEY must contain at least $MIN_SECRETS_KEY_LENGTH characters. " +
                    "Generate one with `openssl rand -hex 32`, or leave it blank."
            }
            require(PLACEHOLDER_FRAGMENTS.none { key.contains(it, ignoreCase = true) }) {
                "ARTEL_SECRETS_KEY still holds a placeholder. " +
                    "Generate one with `openssl rand -hex 32`, or leave it blank."
            }
        }
    }

    override fun toString(): String = "SecretsProperties(keySet=${key.isNotEmpty()})"
}
