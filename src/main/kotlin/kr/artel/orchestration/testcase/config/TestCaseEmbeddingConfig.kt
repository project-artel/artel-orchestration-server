package kr.artel.orchestration.testcase.config

import org.springframework.boot.context.properties.EnableConfigurationProperties
import org.springframework.context.annotation.Configuration

/**
 * test_case 임베딩·검색 설정 바인딩. 스케줄러(enabled 조건부)와 분리해 항상 활성이라, 백필을 꺼도
 * 워커/검색/테스트가 프로퍼티 빈을 주입받을 수 있다(knowledge의 KnowledgeConfig와 같은 방식).
 */
@Configuration
@EnableConfigurationProperties(TestCaseEmbeddingProperties::class, TestCaseSearchProperties::class)
class TestCaseEmbeddingConfig
