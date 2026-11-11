package kr.artel.orchestration.settings.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(
    LlmKeyProperties::class,
    SecretsProperties::class,
    AnthropicProperties::class,
    OpenAiProperties::class,
    ModelDiscoveryProperties::class,
    AgentServerProperties::class
)
class SettingsConfig
