package kr.artel.orchestration.userllmkey.entity

import com.fasterxml.jackson.annotation.JsonCreator
import com.fasterxml.jackson.annotation.JsonValue

/**
 * LLM 호출이 어느 key 로 나가는지. `qa_run.key_source`, `qa_try.key_source`, `llm_usage.key_source` 의 값이다.
 *
 * - [PLATFORM]: 관리자가 넣은 공용 key(`platform_setting`). 플랫폼이 낸다
 * - [USER]: 사용자가 넣은 key(`user_llm_key`). 사용자가 낸다
 */
enum class KeySource(@get:JsonValue val wire: String) {
    PLATFORM("platform"),
    USER("user");

    companion object {
        val WIRE_NAMES: List<String> = entries.map { it.wire }

        fun fromWire(value: String): KeySource? = entries.firstOrNull { it.wire == value }

        /**
         * 요청 본문의 값을 읽는다. 모르는 값은 예외라 Jackson 이 요청을 400 으로 거절한다. null 을 돌려주면
         * 필드의 기본값(`platform`)으로 조용히 떨어져, 사용자 key 로 보낸 호출이 공용 key 지출로 남는다.
         */
        @JvmStatic
        @JsonCreator
        fun parse(value: String): KeySource =
            fromWire(value) ?: throw IllegalArgumentException("keySource must be one of $WIRE_NAMES")
    }
}
