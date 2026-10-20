package kr.artel.orchestration.userllmkey.entity

import com.fasterxml.jackson.annotation.JsonValue

/**
 * 사용자가 key 를 넣을 수 있는 서비스. `user_llm_key.service` 와 `/api/auth/me/llm-keys/{service}` 의 값이고,
 * `/api/qa-models` 의 `keyProvider` 값이다.
 *
 * @property keyPrefix 그 서비스가 발급하는 key 의 접두어. 다르면 서비스에 묻기 전에 400 이다.
 */
enum class LlmKeyProvider(@get:JsonValue val wire: String, val keyPrefix: String) {
    ANTHROPIC("anthropic", "sk-ant-"),
    OPENAI("openai", "sk-"),
    OPENROUTER("openrouter", "sk-or-");

    companion object {
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): LlmKeyProvider? = entries.firstOrNull { it.wire == value }
    }
}
