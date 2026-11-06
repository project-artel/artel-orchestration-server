package kr.artel.orchestration.qa.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.qa.repository.QaRunRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.qa.review.QaVerdictReviewService
import org.springframework.stereotype.Service
import java.time.Instant

@Service
class QaRunRollupService(
    private val tryRepository: QaTryRepository,
    private val runRepository: QaRunRepository,
    private val reviews: QaVerdictReviewService,
) {
    /**
     * 모든 try가 끝났을 때 가장 강한 결과를 부모 run에 올린다.
     * 실패는 취소보다, 취소는 미판정보다, 미판정은 정상 완료보다 우선한다.
     *
     * 미판정으로 끝났고 판정이 하나도 없는 run 은 버림으로 둔다(ARTEL-1371). 모델 API 오류로 게임을 한 번도
     * 검증하지 못한 실행 흔적이라 히스토리 · 통계 · 알림함에서 뺀다. 지우지는 않는다. 사용자가 취소한 run 과
     * 실패 · 통과가 하나라도 있는 run 은 미판정이 아니므로 그대로 남는다.
     */
    suspend fun rollUpIfAllTriesDone(qaRunId: Long?, completedAt: Instant) {
        if (qaRunId == null) return
        val tries = tryRepository.findByQaRunId(qaRunId).toList()
        if (tries.isEmpty() || tries.any { it.status !in QaEndStatus.TERMINAL }) return

        val runStatus = when {
            tries.any { it.status == "FAILED" } -> "FAILED"
            tries.any { it.status == "CANCELLED" } -> "CANCELLED"
            tries.any { it.status == QaEndStatus.UNDECIDED } -> QaEndStatus.UNDECIDED
            else -> "COMPLETED"
        }
        if (runRepository.transition(qaRunId, "RUNNING", runStatus, completedAt, completedAt) != 1) return
        if (runStatus == QaEndStatus.UNDECIDED && tries.none { it.status == "COMPLETED" } &&
            !runRepository.hasStepVerdicts(qaRunId)
        ) {
            runRepository.discard(qaRunId, completedAt)
        }
        // 실행이 방금 끝났다. 추가 검토를 고르고 화면에 알린다(ARTEL-1254). 예외를 내지 않는다.
        reviews.onRunFinished(qaRunId)
    }
}
