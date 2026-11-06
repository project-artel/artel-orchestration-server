package kr.artel.orchestration.settings

import kr.artel.orchestration.knowledge.config.KnowledgeBackfillProperties
import kr.artel.orchestration.settings.config.AgentServerProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.boot.test.context.runner.ApplicationContextRunner
import org.springframework.boot.test.context.TestConfiguration
import java.time.Duration

class AgentClientPropertiesTest {
    private val contextRunner = ApplicationContextRunner().withUserConfiguration(Config::class.java)

    @Test
    fun `preserves client defaults and the backfill timeout fallback`() {
        contextRunner.run { context ->
            val agent = context.getBean(AgentServerProperties::class.java)
            val backfill = context.getBean(KnowledgeBackfillProperties::class.java)
            assertThat(agent.baseUrl).isEqualTo("http://localhost:8000")
            assertThat(agent.wsBaseUrl).isEqualTo("ws://localhost:8000")
            assertThat(agent.embedTimeout).isNull()
            assertThat(backfill.timeout).isEqualTo(Duration.ofMinutes(2))
            assertThat(agent.extract.enabled).isTrue()
            assertThat(agent.extract.timeout).isEqualTo(Duration.ofMinutes(3))
            assertThat(agent.extract.model).isEmpty()
            assertThat(agent.stepPhrasingTimeout).isEqualTo(Duration.ofSeconds(20))
        }
    }

    @Test
    fun `binds existing client property keys`() {
        contextRunner.withPropertyValues(
            "artel.agent.base-url=http://agent.test/internal",
            "artel.agent.ws-base-url=ws://agent.test/internal",
            "artel.agent.embed-timeout=PT41S",
            "artel.knowledge.backfill.timeout=PT42S",
            "artel.agent.extract.enabled=false",
            "artel.agent.extract.timeout=PT43S",
            "artel.agent.extract.model=test-model",
            "artel.agent.step-phrasing-timeout=PT44S",
        ).run { context ->
            val agent = context.getBean(AgentServerProperties::class.java)
            val backfill = context.getBean(KnowledgeBackfillProperties::class.java)
            assertThat(agent.baseUrl).isEqualTo("http://agent.test/internal")
            assertThat(agent.wsBaseUrl).isEqualTo("ws://agent.test/internal")
            assertThat(agent.embedTimeout).isEqualTo(Duration.ofSeconds(41))
            assertThat(backfill.timeout).isEqualTo(Duration.ofSeconds(42))
            assertThat(agent.extract.enabled).isFalse()
            assertThat(agent.extract.timeout).isEqualTo(Duration.ofSeconds(43))
            assertThat(agent.extract.model).isEqualTo("test-model")
            assertThat(agent.stepPhrasingTimeout).isEqualTo(Duration.ofSeconds(44))
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    @EnableConfigurationProperties(AgentServerProperties::class, KnowledgeBackfillProperties::class)
    class Config
}
