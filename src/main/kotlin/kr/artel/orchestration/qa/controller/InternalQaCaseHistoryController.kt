package kr.artel.orchestration.qa.controller

import kr.artel.orchestration.qa.dto.QaCaseHistoryResponse
import kr.artel.orchestration.qa.service.QaCaseHistoryService
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RequestParam
import org.springframework.web.bind.annotation.RestController
import java.util.UUID

@RestController
@RequestMapping("/internal/qa-tries/{qaTryId}/test-cases/{caseId}/verification-history")
class InternalQaCaseHistoryController(private val history: QaCaseHistoryService) {
    @GetMapping
    suspend fun readCaseVerificationHistory(
        @PathVariable qaTryId: Long,
        @PathVariable caseId: Long,
        @RequestParam(required = false) previousTryId: Long?,
        @RequestParam(required = false) beforeTryId: Long?,
        @RequestParam(defaultValue = "0") afterLogId: Long,
        @RequestParam(defaultValue = "12") limit: Int,
        @RequestParam(required = false) afterQuestionId: UUID?,
        @RequestParam(required = false) beforeCaseAttemptId: UUID?,
    ): QaCaseHistoryResponse = history.read(qaTryId, caseId, previousTryId, beforeTryId, afterLogId, limit, afterQuestionId, beforeCaseAttemptId)
}
