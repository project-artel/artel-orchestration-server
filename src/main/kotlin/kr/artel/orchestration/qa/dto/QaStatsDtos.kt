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
/**
 * 에이전트가 무엇을 했나 (ARTEL-681).
 *
 * [QaStatsResponse] 와 가른 것은 접는 축이 다르기 때문이다. 저쪽은 런을 실행 설정 4-튜플로
 * **분할**하고 이쪽은 도구로 접는다. 한 응답에 섞으면 셀마다 도구 목록이 붙어 곱집합이 되고,
 * 정작 이 집계가 답해야 할 질문 — "한 번도 안 불린 도구가 있나" — 은 그 안에서 더 안 보인다.
 *
 * 같은 창을 본다. 두 응답을 한 화면에 나란히 놓는 것이 이 값의 쓸모라, 창이 어긋나면 안 된다.
 *
 * @property tools 부른 횟수 오름차순. **0 이 맨 위에 온다** — 안 쓰인 도구를 찾는 것이 이
 *   목록의 목적이므로 눈이 먼저 닿는 자리에 둔다.
 */
data class QaToolStatsResponse(
    val projectId: String,
    val from: Instant,
    val to: Instant,
    val tools: List<QaToolStatsCell>,
    val citations: QaCitationStats,
    val issues: List<QaIssueStatsCell>
)

/**
 * 도구 하나.
 *
 * @property calls **0 이 유효한 답이다.** 런이 쥐고 있었는데 한 번도 안 부른 도구가 그렇게
 *   나온다 — `record_knowledge` 가 모든 런에서 그랬고, 그것을 사람이 로그를 세서 알아냈다.
 * @property runsHeld 그 도구를 쥔 런 수.
 * @property runsCalled 실제로 부른 런 수. [runsHeld] 와의 차이가 "줬는데 안 쓴" 런이다.
 */
data class QaToolStatsCell(
    val tool: String,
    val calls: Long,
    val runsHeld: Long,
    val runsCalled: Long
)

/**
 * 스텝 판정이 근거를 댔는가.
 *
 * 비율을 서버가 내지 않는 것은 [QaRunConfigStatsCell.stepsPassed] 와 같은 이유다 — 비율만 주면
 * 그것이 몇 건에 얹힌 값인지가 응답에서 사라진다.
 *
 * @property verdicts `report_step` 총 호출 수.
 * @property withCitation 그중 `used_knowledge_ids` 가 채워진 것.
 */
data class QaCitationStats(
    val verdicts: Long,
    val withCitation: Long
)

/** severity 하나에 몇 건. */
data class QaIssueStatsCell(
    val severity: String,
    val issues: Long
)

data class QaStatsResponse(
    /** 물어본 프로젝트. 생략하고 부르면 null이고, 그때 집계는 볼 수 있는 전 프로젝트의 합이다. */
    val projectId: String?,
    val from: Instant,
    val to: Instant,
    val total: QaStatsTotals,
    val cells: List<QaRunConfigStatsCell>,
    val truncated: Boolean,
    val cellLimit: Int
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
