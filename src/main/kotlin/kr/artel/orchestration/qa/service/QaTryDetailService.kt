package kr.artel.orchestration.qa.service

import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.qa.dto.QaTryDetailResponse
import kr.artel.orchestration.qa.dto.QaTryDetailUsage
import kr.artel.orchestration.qa.dto.QaTryToolCall
import kr.artel.orchestration.qa.entity.QaTryEntity
import kr.artel.orchestration.qa.repository.QaTryDetailRepository
import kr.artel.orchestration.qa.repository.QaTryDetailRow
import kr.artel.orchestration.qa.repository.QaTryRepository
import org.springframework.stereotype.Service

/**
 * QA 히스토리에서 런 하나를 펼쳤을 때 내려 줄 것(ARTEL-819).
 *
 * [QaTryService]에 얹지 않았다 — 저쪽은 런의 생명주기(생성·취소·프레임 라우팅)를 쓰고 이쪽은
 * 끝난 런을 읽기만 한다. `IssueService`가 같은 이유로 `/api/qa-tries/{id}/issues`를 자기 쪽에
 * 두고 있다.
 */
@Service
class QaTryDetailService(
    private val tryRepository: QaTryRepository,
    private val detailRepository: QaTryDetailRepository
) {

    /**
     * 런 하나의 상세. 실행이 없거나 참여하지 않는 프로젝트면 404.
     *
     * 접근 판정을 이 서비스가 먼저 하고([QaTryRepository.findAccessibleById]), 뒤따르는 집계
     * 질의는 멤버십을 다시 따지지 않는다 — `IssueService.listByQaTry`와 같은 규약이다.
     *
     * **도는 중인 런도 거절하지 않는다.** 그때의 수는 중간값이라 화면이 안 부르기로 했지만
     * (ARTEL-819), 그 판단은 화면의 것이고 서버가 강제할 성질이 아니다. CLI나 다른 소비자가
     * 물으면 그 시점까지의 합을 그대로 준다.
     */
    suspend fun detail(qaTryId: Long, userId: Long): QaTryDetailResponse {
        val qaTry = tryRepository.findAccessibleById(qaTryId, userId) ?: throw NotFoundException()
        // 접근이 확인된 행이므로 상세 질의가 비면 그 사이에 지워진 것이다. 404가 맞다.
        val detail = detailRepository.findDetail(qaTryId) ?: throw NotFoundException()
        val toolCalls = detailRepository.findToolCalls(qaTryId)
        return qaTry.toDetailResponse(detail, toolCalls.map { QaTryToolCall(it.tool, it.calls) })
    }

    /**
     * 승격 컬럼과 집계를 한 응답으로 합친다.
     *
     * `stepsPassed`/`stepsTotal`은 그대로 통과시킨다. 완주하지 않은 런에서 둘 다 null인데, 여기서
     * 0으로 채우면 화면이 "없음"과 "0 통과"를 갈라 볼 근거를 잃는다.
     */
    private fun QaTryEntity.toDetailResponse(
        detail: QaTryDetailRow,
        toolCalls: List<QaTryToolCall>
    ) = QaTryDetailResponse(
        qaTryId = id.toString(),
        status = status,
        scenarioTitle = detail.scenarioTitle,
        model = model,
        promptVersion = promptVersion,
        reasoningEffort = reasoningEffort,
        startedAt = startedAt,
        completedAt = completedAt,
        stepsPassed = stepsPassed,
        stepsTotal = stepsTotal,
        issues = detail.issues,
        feedback = detail.feedback,
        usage = QaTryDetailUsage(
            calls = detail.llmCalls,
            pricedCalls = detail.pricedCalls,
            costUsd = detail.costUsd,
            // 하나라도 우리가 계산한 것이면 합계는 청구액이 아니다. 섞였다는 사실이 그 합계에
            // 대한 판단을 바꾸므로, 몇 개인지가 아니라 섞였는지를 내려 준다.
            costEstimated = detail.estimatedCalls > 0,
            inputTokens = detail.inputTokens,
            cachedInputTokens = detail.cachedInputTokens,
            cacheWriteTokens = detail.cacheWriteTokens,
            outputTokens = detail.outputTokens
        ),
        toolCalls = toolCalls
    )
}
