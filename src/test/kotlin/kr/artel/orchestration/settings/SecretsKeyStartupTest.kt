package kr.artel.orchestration.settings

import kr.artel.orchestration.settings.config.SecretsProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.context.annotation.Configuration

/** `ARTEL_SECRETS_KEY` 가 약하면 서버가 뜨지 않고, 비어 있거나 `openssl rand -hex 32` 값이면 뜬다. */
class SecretsKeyStartupTest {

    @Configuration
    @EnableConfigurationProperties(SecretsProperties::class)
    class TestConfig

    private val runner = ApplicationContextRunner().withUserConfiguration(TestConfig::class.java)

    @ParameterizedTest
    @ValueSource(
        strings = [
            "short",
            "0123456789012345678901234567890", // 31 글자
            "CHANGE_ME0000000000000000000000000000000000", // 40 글자, 예시 문구
            "change-me",
            "a-perfectly-long-but-Replace-With-this-one",
            "0123456789abcdef-Your-Secret-0123456789abcdef"
        ]
    )
    fun `refuses to start with a weak key and does not print it`(weak: String) {
        runner.withPropertyValues("artel.secrets.key=$weak").run { context ->
            assertThat(context).hasFailed()
            val message = generateSequence(context.startupFailure) { it.cause }.joinToString(" | ") { it.message.orEmpty() }
            assertThat(message).contains("ARTEL_SECRETS_KEY").contains("openssl rand -hex 32")
            assertThat(message).doesNotContain(weak)
        }
    }

    @Test
    fun `starts with a blank key`() {
        runner.withPropertyValues("artel.secrets.key=").run { context ->
            assertThat(context).hasNotFailed()
            assertThat(context.getBean(SecretsProperties::class.java).key).isEmpty()
        }
    }

    @Test
    fun `starts with a 64 character hex key`() {
        val hex = "9f86d081884c7d659a2feaa0c55ad015a3bf4f1b2b0b822cd15d6c15b0f00a08"
        runner.withPropertyValues("artel.secrets.key=$hex").run { context ->
            assertThat(context).hasNotFailed()
        }
    }
}
