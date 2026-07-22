package kr.artel.orchestration.testscenario.repository

import kr.artel.orchestration.testscenario.entity.TestScenarioMessageEntity
import org.springframework.data.repository.reactive.ReactiveCrudRepository
import reactor.core.publisher.Flux
import reactor.core.publisher.Mono

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

    /** Approve 시 시나리오는 남기고 해당 사용자의 채팅 스레드(부산물)만 정리한다. */
    fun deleteByTestScenarioIdAndAppUserId(
        testScenarioId: Long,
        appUserId: Long
    ): Mono<Void>
}
