package kr.artel.orchestration.contentmap.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** content_map 도메인 설정 등록. */
@Configuration
@EnableConfigurationProperties(ActionObservationProperties::class)
class ContentMapConfig
