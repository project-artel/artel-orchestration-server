package kr.artel.orchestration.settings.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

/**
 * OpenRouter API key 를 어디서 가져오는지와 key 를 확인하는 호출.
 *
 * key 는 관리 화면에서 넣은 값(`platform_setting`)이 먼저이고, 없으면 [openrouterApiKey] 다. 이 서버는
 * key 로 LLM 을 부르지 않는다. 쥐고 있다가 `artel-agent-server` 가 내부 포트로 물으면 건넬 뿐이다.
 */
@ConfigurationProperties("artel.llm")
data class LlmKeyProperties(
    /** `OPENROUTER_API_KEY`. 관리 화면에 값이 없을 때 쓰는 key. 비어 있어도 된다. */
    val openrouterApiKey: String = "",
    /** `GET {base}/key` 와 `GET {base}/models` 를 부를 주소. 테스트가 가짜 서버로 돌린다. */
    val openrouterBaseUrl: String = "https://openrouter.ai/api/v1",
    /** 확인 한 번의 응답 상한. 관리 화면의 버튼 하나가 기다리는 시간이다. */
    val checkTimeout: Duration = Duration.ofSeconds(15)
) {
    /** key 가 로그로 나가지 않도록 뺀다. */
    override fun toString(): String =
        "LlmKeyProperties(openrouterApiKeySet=${openrouterApiKey.isNotBlank()}, " +
            "openrouterBaseUrl=$openrouterBaseUrl, checkTimeout=$checkTimeout)"
}

/**
 * `platform_setting` 의 암호화된 값을 여는 비밀. `ARTEL_SECRETS_KEY` 다.
 *
 * 비어 있어도 서버는 뜬다. 그때는 관리 화면에서 key 를 저장할 수 없고(409 `secrets_key_missing`),
 * `OPENROUTER_API_KEY` 만 쓰인다. 이 값을 바꾸면 이미 저장된 값을 열 수 없게 되므로, 그 key 는
 * 관리 화면에서 다시 넣어야 한다.
 */
@ConfigurationProperties("artel.secrets")
data class SecretsProperties(
    val key: String = ""
) {
    init {
        require(key.isEmpty() || key.toByteArray(Charsets.UTF_8).size >= 32) {
            "ARTEL_SECRETS_KEY must contain at least 32 bytes"
        }
    }

    override fun toString(): String = "SecretsProperties(keySet=${key.isNotEmpty()})"
}
