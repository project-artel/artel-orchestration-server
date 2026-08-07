package kr.artel.orchestration.knowledge.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * knowledge 도메인 설정 등록.
 *
 * 백필 설정은 스케줄러가 꺼져 있어도 만들어져야 한다. 워커 빈이 이 설정을 주입받고, 워커는
 * 스케줄과 무관하게(테스트·수동 실행) 쓰이기 때문이다. 그래서 등록을 조건부인
 * [KnowledgeBackfillScheduler]가 아니라 여기에 둔다.
 */
@Configuration
@EnableConfigurationProperties(
    KnowledgeBackfillProperties::class,
    KnowledgeSearchProperties::class,
    KnowledgeGraphProperties::class
)
class KnowledgeConfig
