package kr.artel.orchestration.testscenario.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/** 저작 기록 설정 바인딩. 꺼져 있어도 빈은 있어야 한다 — 부르는 쪽이 분기하지 않기 위해서다. */
@Configuration
@EnableConfigurationProperties(AuthoringTraceProperties::class, ScenarioRepairProperties::class)
class AuthoringTraceConfig
