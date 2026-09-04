package kr.artel.orchestration.qa.dto

import java.math.BigDecimal
import java.time.Instant

/**
 * 실행 설정 축으로 접은 QA 런 집계(ARTEL-239 후속).
 *
 * [cells]는 `(model, reasoningEffort, promptVersion, agentArch)` 4-튜플로 런을 **분할**한 결과다.
 * 런 하나는 정확히 한 셀에 속하므로, 클라이언트는 이 목록의 부분합만으로 단일 축 분해와 두 축
 * 매트릭스를 모두 만들 수 있다. 축 이름을 서버에 되묻는 왕복이 필요 없다.
 *
 * @property total 자르기 전 전체 합계. [truncated]일 때 [cells]의 합과 다르며, 이쪽이 프로젝트의
 *   실제 런 수다.
 * @property truncated 조합 수가 [cellLimit]을 넘어 [cells]가 잘렸는지. 잘린 셀은 런 수 내림차순
 *   기준 하위다.
 */
data class QaStatsResponse(
    /** 물어본 프로젝트. 생략하고 부르면 null이고, 그때 집계는 볼 수 있는 전 프로젝트의 합이다. */
    val projectId: String?,
    /**
     * 물어본 test run. 생략하고 부르면 null이고, 그때 집계는 단독 실행 런까지 포함한 전부다.
     *
     * [projectId] 와 같은 이유로 되돌려 준다 — 화면이 여러 층을 나란히 놓고 비교할 때 어느 응답이
     * 어느 층의 것인지가 응답 자체에 없으면 요청과 응답을 짝지어 들고 있어야 한다.
     */
    val testRunId: String?,
    /**
     * 물어본 실험 묶음. 생략하고 부르면 null이고, 그때 집계는 어느 실험에도 안 묶인 런까지 전부다.
     *
     * [testRunId] 와 **독립이다.** 둘을 함께 걸면 "1차 실험의 9013 런" 이 되고, 응답이 둘을 다
     * 되돌려 주므로 화면은 자기가 무엇을 보고 있는지를 응답만 보고 말할 수 있다.
     */
    val label: String?,
    val from: Instant,
    val to: Instant,
    val total: QaStatsTotals,
    val cells: List<QaRunConfigStatsCell>,
    val truncated: Boolean,
    val cellLimit: Int
)

/**
 * 이미 쓰인 실험 묶음 이름의 목록.
 *
 * 화면의 `label` 자리를 자유 입력이 아니라 **고르는 자리**로 만들려고 있다. 자유 문자열의 실질
 * 위험은 값의 형식이 아니라 `content map 1차` 와 `content map 1차 실험` 이 두 칸으로 갈리는
 * 것이고, 고르게 만들면 tag 체계를 세우지 않고도 그것이 막힌다.
 *
 * @property labels 최근에 쓴 것부터. 새 이름을 여기서 만들지 않는다 — 이름은 run 을 걸 때 정한다.
 */
data class QaStatsLabelsResponse(
    /** 물어본 프로젝트. 생략하고 부르면 null이고, 그때 목록은 볼 수 있는 전 프로젝트의 것이다. */
    val projectId: String?,
    val labels: List<String>
)

/**
 * 축 하나 조합의 집계 한 줄.
 *
 * 축 값 4개는 모두 nullable이고 null은 "미상"이다 — ARTEL-239 이전에 끝난 런과, 세션 응답에
 * `run_config`를 싣지 않는 구버전 Agent가 여기 들어온다. 집계에서 빼지 않는 이유는 빼면 셀 합이
 * [QaStatsResponse.total]과 어긋나고 그 차이를 화면에서 설명할 수 없기 때문이다.
 *
 * @property completed 끝까지 돈 런. **QA 통과가 아니다** — `qa_try.status`는 런 생명주기이지
 *   테스트 판정이 아니다. 화면 라벨도 완주로 읽혀야 한다.
 * @property cancelled 운영자가 중단시킨 런. 실패와 섞지 않는다.
 * @property active 아직 도는 런(`STARTING`/`RUNNING`). 완주율 분모에서 빠진다.
 * @property costUsd 단가를 아는 호출이 하나도 없으면 null. 0(공짜)과 다르다.
 * @property avgCompletedDurationMs 완주한 런만의 평균 소요 ms. 완주 런이 없으면 null.
 * @property verdictKnown 판정을 아는 런 수(ARTEL-299). 아래 네 합계의 분모다. [runs]와 이 값의
 *   차이가 판정을 **모르는** 런이고, 그것은 판정이 0점인 런과 다르다 — 소켓 사망·취소로 요약
 *   없이 끝난 런이 여기 들어간다.
 * @property stepsPassed 판정을 아는 런들의 통과 스텝 수 합. 합격률은 이 값을 [stepsTotal]로
 *   나눠 화면이 낸다. 서버가 평균을 내지 않는 것은 의도다 — 평균만 주면 그것이 몇 개의 런에
 *   얹힌 값인지가 응답에서 사라지고, 잘 죽는 축일수록 그 비율이 위로 편향된다.
 */
data class QaRunConfigStatsCell(
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val runs: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val active: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val llmCalls: Long,
    val avgCompletedDurationMs: Double?,
    val verdictKnown: Long,
    val stepsTotal: Long,
    val stepsPassed: Long,
    val casesTotal: Long,
    val casesPassed: Long,
    val scoredRuns: Long,
    val correctPass: Long,
    val falseAlarm: Long,
    val miss: Long,
    val correctFail: Long,
    val unreported: Long
)

/** [QaRunConfigStatsCell]에서 축만 뺀 전체 합계. 축이 없으므로 별도 타입이다. */
data class QaStatsTotals(
    val runs: Long,
    val completed: Long,
    val failed: Long,
    val cancelled: Long,
    val active: Long,
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val llmCalls: Long,
    val avgCompletedDurationMs: Double?,
    val verdictKnown: Long,
    val stepsTotal: Long,
    val stepsPassed: Long,
    val casesTotal: Long,
    val casesPassed: Long,
    val scoredRuns: Long,
    val correctPass: Long,
    val falseAlarm: Long,
    val miss: Long,
    val correctFail: Long,
    val unreported: Long
)
