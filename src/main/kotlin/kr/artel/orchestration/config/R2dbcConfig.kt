package kr.artel.orchestration.config

import org.springframework.context.annotation.Configuration
import org.springframework.data.r2dbc.config.EnableR2dbcAuditing

/**
 * R2DBC Auditing 활성화 — 엔티티의 @CreatedDate / @LastModifiedDate 필드를 저장 시점에 자동 채운다.
 */
@Configuration
@EnableR2dbcAuditing
class R2dbcConfig
