package kr.artel.orchestration.testscenario.repository

import kr.artel.orchestration.testscenario.entity.TestScenarioMessageEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux

/**
 * TestScenario 채팅 메시지 R2DBC 리포지토리.
 *
 * 대화는 사용자별 프라이빗이므로 조회는 (testScenarioId, appUserId)로 스코프하여 시간순으로 반환한다.
 */
interface TestScenarioMessageRepository : ReactiveCrudRepository<TestScenarioMessageEntity, Long> {
    fun findByTestScenarioIdAndAppUserIdOrderByCreatedAtAsc(
        testScenarioId: Long,
        appUserId: Long
    ): Flux<TestScenarioMessageEntity>
}
