package kr.artel.orchestration.llmusage.dto

import java.math.BigDecimal
import java.time.Instant
import java.time.LocalDate

/**
 * 기간 지출 집계(ARTEL-233 후속).
 *
 * 네 축은 같은 호출 집합을 다르게 접은 것이라 서로 겹친다 — [byService]의 합, [byModel]의 합,
 * [byProject]의 합, [daily]의 합은 모두 [total]과 같다. 한 호출이 service·model·project·일자를
 * 동시에 갖기 때문이며, 이 점이 런을 4-튜플로 **분할**하는 QA 통계와 다르다. 축을 두 개 겹쳐
 * 매트릭스를 만들 수는 없다.
 *
 * @property projectId null이면 사용자가 속한 전 프로젝트 합산이다.
 * @property zone [daily]의 하루 경계를 자른 시간대. 이 값이 없으면 월말 하루가 어느 쪽에 붙었는지
 *   알 수 없다.
 * @property unattributedCalls 프로젝트를 못 푼 호출 수. 이 응답의 어느 합계에도 **안 들어간다** —
 *   `reference_id`가 비었거나 가리키던 행이 지워진 호출이다. 0이 아니면 [total]은 배포 전체 지출의
 *   부분합이다. 토큰과 금액을 싣지 않는 이유는 그 행들을 멤버십으로 거를 수 없어서다(관리자
 *   role이 없다).
 */
data class LlmUsageStatsResponse(
    val projectId: String?,
    val from: Instant,
    val to: Instant,
    val zone: String,
    val total: LlmUsageTotals,
    val byService: List<LlmUsageServiceCell>,
    val byModel: List<LlmUsageModelCell>,
    val byProject: List<LlmUsageProjectCell>,
    val daily: List<LlmUsageDailyCell>,
    val unattributedCalls: Long
)

/**
 * 접힌 합계 한 벌.
 *
 * @property costUsd 단가를 아는 호출이 하나도 없으면 null. **0과 다르다** — provider가 단가를
 *   안 알려주는 경우가 있고, 둘을 같은 0으로 그리면 비용 비교가 조용히 틀린다.
 * @property pricedCalls [costUsd]가 몇 건의 호출 위에 얹힌 값인지. [calls]보다 작으면 그 금액은
 *   실제 지출의 하한이다. 화면은 이 둘이 다를 때 그 사실을 같이 보여야 한다.
 */
data class LlmUsageTotals(
    val inputTokens: Long,
    val outputTokens: Long,
    val cachedInputTokens: Long,
    val reasoningTokens: Long,
    val costUsd: BigDecimal?,
    val calls: Long,
    val pricedCalls: Long
)

/** service 축 한 줄. `EMBEDDING`은 `outputTokens`가 항상 0이다(임베딩은 토큰을 만들지 않는다). */
data class LlmUsageServiceCell(
    val service: String,
    val totals: LlmUsageTotals
)

/** provider·model 축 한 줄. provider는 OpenRouter 슬러그 `<provider>/<model>`의 앞부분이다. */
data class LlmUsageModelCell(
    val provider: String,
    val model: String,
    val totals: LlmUsageTotals
)

/**
 * 프로젝트 축 한 줄.
 *
 * @property projectName 조회 시점의 이름이다. 이름이 바뀌면 지난 달 집계도 새 이름으로 보인다 —
 *   지출은 프로젝트 id에 붙지 이름에 붙지 않는다.
 */
data class LlmUsageProjectCell(
    val projectId: String,
    val projectName: String,
    val totals: LlmUsageTotals
)

/**
 * 일별 한 줄. 호출이 하나도 없던 날은 **줄이 없다** — 0인 날을 서버가 채우지 않는다.
 * 추이를 그리는 쪽이 빈 날을 어떻게 다룰지(0으로 이을지, 끊을지) 정한다.
 */
data class LlmUsageDailyCell(
    val date: LocalDate,
    val totals: LlmUsageTotals
)

/**
 * QA 런 한 건의 지출.
 *
 * @property calls 이 런에 귀속된 LLM 호출 수. 0은 "안 썼다"가 아니라 "아직 안 왔거나 유실됐다"일
 *   수 있다 — agent는 배치로 보내고 실패한 배치를 재시도하지 않는다(V24 주석).
 * @property costUsd 단가를 아는 호출이 없으면 null. 0과 다르다.
 */
data class QaRunUsageResponse(
    val qaTryId: String,
    val projectId: String,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val model: String?,
    val reasoningEffort: String?,
    val promptVersion: String?,
    val agentArch: String?,
    val totals: LlmUsageTotals
)
