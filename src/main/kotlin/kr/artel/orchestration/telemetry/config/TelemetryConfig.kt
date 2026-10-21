package kr.artel.orchestration.telemetry.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

@Configuration
@EnableConfigurationProperties(TelemetryProperties::class, ArtelVersionProperties::class)
class TelemetryConfig
