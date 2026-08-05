package kr.artel.orchestration.llmusage.service

import kotlinx.coroutines.flow.collect
import kr.artel.orchestration.llmusage.dto.LlmUsageRecord
import kr.artel.orchestration.llmusage.entity.LlmUsageEntity
import kr.artel.orchestration.llmusage.repository.LlmUsageRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * agent가 보낸 LLM 사용량 배치의 적재 담당.
 *
 * 이 티켓은 저장까지다 — 집계·쿼터·대시보드는 없다. 사용량 기록은 서비스 동작에 영향을 주지 않는
 * 부가 데이터이고, 보내는 쪽은 재시도하지 않는다(그래서 멱등키도 유니크 제약도 없다).
 */
@Service
class LlmUsageService(
    private val llmUsageRepository: LlmUsageRepository,
    private val clock: Clock
) {
    /**
     * 배치를 그대로 적재한다. 건수 검증(1~200)은 요청 DTO가 이미 했다.
     *
     * [LlmUsageRecord.calledAt]은 agent가 provider를 부른 시각이라 그대로 보존하고,
     * `createdAt`은 여기서 수신 시각으로 stamp한다 — 컬럼 기본값에 맡기면 R2DBC가 insert 후
     * 그 값을 다시 읽지 않아 저장 결과의 createdAt이 null이다(IssueService와 동일).
     */
    suspend fun record(records: List<LlmUsageRecord>) {
        val now = Instant.now(clock)
        val entities = records.map { it.toEntity(now) }
        // ponytail: saveAll은 왕복 1회지만 INSERT 문은 N개다(건당 하나). 배치 200건까지는 이 정도로
        // 충분하고, 프로파일링에서 여기가 병목으로 잡히면 DatabaseClient 다중 VALUES 한 문장으로 올린다.
        // saveAll이 돌려주는 Flow는 소비해야 실제로 실행된다 — collect()를 빼면 조용히 0행이 된다.
        llmUsageRepository.saveAll(entities).collect()
    }

    private fun LlmUsageRecord.toEntity(receivedAt: Instant) = LlmUsageEntity(
        service = requireNotNull(service).name,
        referenceId = referenceId,
        provider = provider,
        model = model,
        inputTokens = inputTokens,
        outputTokens = outputTokens,
        cachedInputTokens = cachedInputTokens,
        reasoningTokens = reasoningTokens,
        costUsd = costUsd,
        latencyMs = latencyMs,
        calledAt = requireNotNull(calledAt),
        createdAt = receivedAt
    )
}
