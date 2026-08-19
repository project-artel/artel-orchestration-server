package kr.artel.orchestration.qa.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QaRunRollupService(
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository
) {
    /**
     * 모든 try가 끝났을 때 가장 강한 결과를 부모 run에 올린다.
     * 실패는 취소보다, 취소는 정상 완료보다 우선한다.
     */
    suspend fun rollUpIfAllTriesDone(qaRunId: Long?, completedAt: Instant) {
        if (qaRunId == null) return
        val tries = tryRepository.findByQaRunId(qaRunId).toList()
        if (tries.isEmpty() || tries.any { it.status !in TERMINAL_TRY_STATUSES }) return

        val runStatus = when {
            tries.any { it.status == "FAILED" } -> "FAILED"
            tries.any { it.status == "CANCELLED" } -> "CANCELLED"
            else -> "COMPLETED"
        }
        runRepository.transition(qaRunId, "RUNNING", runStatus, completedAt, completedAt)
    }

    private companion object {
        val TERMINAL_TRY_STATUSES = setOf("COMPLETED", "FAILED", "CANCELLED")
    }
}
