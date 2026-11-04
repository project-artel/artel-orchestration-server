package kr.artel.orchestration.qa.dto

import java.time.Instant

/** QA 판정 추이를 묶는 단위(ARTEL-1386). */
enum class QaVerdictTrendBucket { DAY, WEEK, RUN }

/**
 * 대시보드의 QA 판정 추이(ARTEL-1386). [buckets] 는 시간순이고, 판정이 없는 날 · 주는 칸이 없다 — 빈 칸을 채우는
 * 것은 화면이 고른 기간에 맞춰 한다.
 */
data class QaVerdictTrendResponse(
    val bucket: QaVerdictTrendBucket,
    val from: Instant,
    val to: Instant,
    /** 날 · 주의 경계를 정한 시간대. */
    val timeZone: String,
    val buckets: List<QaVerdictTrendBucketResponse>,
    /** 기간 안에 올라온 게임 빌드. 화면이 새 빌드 세로선을 긋는다. */
    val builds: List<QaVerdictTrendBuildResponse>
)

/**
 * 칸 하나. 수는 모두 Test Case 판정 건수다 — 같은 Test Case 를 두 번 판정했으면 두 번 센다.
 *
 * - [passed] · [failed]: 그 칸의 `CASE_VERDICT` 중 통과 · 실패. [failed] 에는 [regression] 이 들지 않는다.
 * - [regression]: 실패 중 같은 Test Case 의 바로 앞 판정이 통과였던 것.
 * - [undecided]: 판정하지 못한 Test Case — agent 가 결론을 못 낸 시도(`INCONCLUSIVE` · `INTERRUPTED`)와,
 *   모델 API 오류로 멈춰 미판정(`UNDECIDED`)이 된 시나리오에 담긴 Test Case.
 */
data class QaVerdictTrendBucketResponse(
    /** `DAY` · `WEEK` 은 그 날 · 그 주 월요일(`yyyy-MM-dd`), `RUN` 은 qa_run id. */
    val key: String,
    /** `RUN` 이면 그 실행의 시작 시각, 아니면 null. */
    val startedAt: Instant? = null,
    val runs: Int,
    val passed: Int,
    val failed: Int,
    val regression: Int,
    val undecided: Int,
    /** 그 칸에서 가장 많이 실패한(회귀 포함) Test Case. 실패가 없으면 null. */
    val topFailing: QaVerdictTrendCaseResponse? = null
)

data class QaVerdictTrendCaseResponse(
    val testCaseId: String,
    /** 프로젝트 안의 Test Case 번호. 없으면 null. */
    val caseNumber: Long?,
    /** 화면에 보일 한 줄(Test Case 의 단계 문장). */
    val title: String,
    val failures: Int
)

data class QaVerdictTrendBuildResponse(
    val gameBuildId: String,
    val version: String,
    val isEditor: Boolean,
    val createdAt: Instant
)
