package kr.artel.orchestration.qa.controller

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.auth.web.CurrentUserId
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.llmmodel.service.LlmModelService
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.QaRunChangeListResponse
import kr.artel.orchestration.qa.dto.QaRunChangeResponse
import kr.artel.orchestration.qa.dto.QaRunResponse
import kr.artel.orchestration.qa.service.QaRunChangeKind
import kr.artel.orchestration.qa.service.QaRunChangeService
import kr.artel.orchestration.qa.service.QaTryService
import kr.artel.orchestration.qa.service.toRunGates
import kr.artel.orchestration.qa.service.toRunLabel
import kr.artel.orchestration.qa.service.toRunSettings
import org.springframework.http.HttpStatus
import org.springframework.http.ResponseEntity
import org.springframework.web.bind.annotation.GetMapping
import org.springframework.web.bind.annotation.PathVariable
import org.springframework.web.bind.annotation.PostMapping
import org.springframework.web.bind.annotation.RequestBody
import org.springframework.web.bind.annotation.RequestMapping
import org.springframework.web.bind.annotation.RestController

/**
 * 런(TR) 단위 QA 실행 시작(ARTEL-259). 런의 시나리오들을 한 세션이 순차 실행하고 사이에 게임을
 * 초기화한다. 시나리오별 결과·이슈·통계는 여전히 qa_try 기준이므로 조회는 /api/qa-tries로 한다.
 */
@RestController
@RequestMapping("/api/qa-runs")
class QaRunController(
    private val qaTryService: QaTryService,
    private val qaRunChangeService: QaRunChangeService,
    private val llmModelService: LlmModelService,
    private val objectMapper: ObjectMapper
) {
    /** 요청의 `model` 이 카탈로그에 없으면 400 `llm_model_unknown`, 꺼져 있으면 400 `llm_model_disabled` 다. */
    @PostMapping
    suspend fun createQaRun(
        @RequestBody request: CreateQaRunRequest,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<QaRunResponse> {
        llmModelService.requireSelectable(request.model)
        return ResponseEntity.status(HttpStatus.CREATED).body(
            qaTryService.createRun(
                parseId(request.testRunId),
                parseId(request.gameInstanceId),
                appUserId,
                request.toRunSettings(objectMapper),
                request.force,
                request.toRunGates(),
                request.toRunLabel()
            )
        )
    }

    /** 런 하나 + 시나리오별 try 상태. FE 런 화면이 종단까지 폴링하는 개요. */
    @GetMapping("/{qaRunId}")
    suspend fun getQaRun(
        @PathVariable qaRunId: String,
        @CurrentUserId appUserId: Long
    ): QaRunResponse =
        qaTryService.getRun(parseId(qaRunId), appUserId) ?: throw NotFoundException()

    /**
     * 이 런이 knowledge 와 macro 에 한 변경. 대상마다 한 줄이고 run 이전과 이후를 line diff 용 텍스트로
     * 싣는다. 접근 규칙은 [getQaRun] 과 같다 — 보이지 않는 런은 404.
     */
    @GetMapping("/{qaRunId}/changes")
    suspend fun listQaRunChanges(
        @PathVariable qaRunId: String,
        @CurrentUserId appUserId: Long
    ): QaRunChangeListResponse =
        qaRunChangeService.list(parseId(qaRunId), appUserId)

    /**
     * 이 런이 한 변경 하나를 revert 한다. `{kind}` 는 `knowledge` 또는 `macro`. 이 런이 그 대상을 바꾸지
     * 않았으면 404, 뒤에 다른 변경이 있거나 이미 되돌렸으면 409 다.
     */
    @PostMapping("/{qaRunId}/changes/{kind}/{targetId}/revert")
    suspend fun revertQaRunChange(
        @PathVariable qaRunId: String,
        @PathVariable kind: String,
        @PathVariable targetId: String,
        @CurrentUserId appUserId: Long
    ): QaRunChangeResponse {
        val changeKind = QaRunChangeKind.fromPath(kind) ?: throw BadRequestException("invalid change kind: $kind")
        return qaRunChangeService.revert(parseId(qaRunId), changeKind, parseId(targetId), appUserId)
    }

    /**
     * 런(TR) 전체를 취소한다. 활성 시나리오는 Agent 세션 종료까지 포함해 취소되고 qa_run이 닫혀,
     * 그 게임 인스턴스로 다시 런을 시작할 수 있게 된다. 이미 끝난 런은 409.
     */
    @PostMapping("/{qaRunId}/cancel")
    suspend fun cancelQaRun(
        @PathVariable qaRunId: String,
        @CurrentUserId appUserId: Long
    ): ResponseEntity<Void> {
        qaTryService.cancelRun(parseId(qaRunId), appUserId)
        return ResponseEntity.noContent().build()
    }

    // testRunId·gameInstanceId·qaRunId 등 여러 엔티티 id 문자열을 공통 파싱하는 범용 헬퍼.
    private fun parseId(rawId: String): Long =
        rawId.toLongOrNull() ?: throw BadRequestException("invalid id: $rawId")
}
