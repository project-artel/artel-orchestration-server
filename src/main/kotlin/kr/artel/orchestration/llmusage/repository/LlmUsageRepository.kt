package kr.artel.orchestration.llmusage.repository

import kr.artel.orchestration.llmusage.entity.LlmUsageEntity
import org.springframework.data.repository.kotlin.CoroutineCrudRepository

/**
 * 적재 전용. 조회 API는 이 티켓 범위가 아니라 파생 쿼리를 두지 않는다 —
 * 집계가 필요해지면 그때 `called_at` 기준 쿼리를 여기에 추가한다.
 */
interface LlmUsageRepository : CoroutineCrudRepository<LlmUsageEntity, Long>
