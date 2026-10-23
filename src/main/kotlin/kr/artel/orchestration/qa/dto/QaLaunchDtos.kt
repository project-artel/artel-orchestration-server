package kr.artel.orchestration.qa.dto

import java.time.Instant

/**
 * QA 실행 화면이 읽는 실행(`qa_run`) 한 줄(ARTEL-1083).
 *
 * [QaRunResponse] 와 따로 둔다. 저쪽은 실행 하나의 시나리오별 try 를 다 싣는 개요이고, 이쪽은
 * 목록 한 행이다 — 빠른 시작 조합과 최근 실행 표가 쓰는 값만 담는다.
 *
 * 모델 · 추론은 `qa_run` 에 없고 그 아래 try 에 있다. 한 실행의 try 는 같은 설정으로 시작되므로
 * 첫 try 의 값을 쓴다. try 가 하나도 없는 실행(시작 직후 실패)이면 null 이다.
 *
 * [casesPassed] · [casesTotal] 은 완주한 try 의 합이다. 판정이 하나도 없으면 둘 다 null —
 * 0 이 아니라 모르는 것이다([QaTryDetailResponse] 와 같은 규칙).
 *
 * @property testRunName 실행 뒤 런이 지워졌으면 null.
 */
data class QaRunSummaryResponse(
    val id: String,
    val testRunId: String,
    val testRunName: String?,
    val gameInstanceId: String,
    val gameInstanceName: String?,
    val gameBuildVersion: String?,
    val model: String?,
    val reasoningEffort: String?,
    val keySource: String?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val casesPassed: Int?,
    val casesTotal: Int?,
    val issueCount: Int,
    /** 실행을 시작한 사람(app_user id). 화면이 "내가 시작한 실행" 의 끝 알림을 고른다. */
    val startedBy: String = "",
    /** 추가 검토로 고른 판정 수와, 그중 사람이 아직 정하지 않은 수(ARTEL-1254). */
    val reviewFlagged: Int = 0,
    val reviewPending: Int = 0,
)

data class QaRunSummaryListResponse(val items: List<QaRunSummaryResponse>)

/**
 * 테스트 런의 마지막 실행에서 나온 이슈 한 건(ARTEL-1083). 화면은 제목만 나열하고, 누르면 이슈로 간다.
 *
 * @property step 이슈가 나온 스텝 번호. 에이전트가 안 실었으면 null.
 */
data class SinceLastQaIssue(
    val id: String,
    val title: String,
    val severity: String?,
    val status: String,
    val qaTryId: String,
    val scenarioId: String,
    val scenarioTitle: String,
    val step: Int?,
    val reportedAt: Instant,
)

/**
 * 마지막 실행 뒤로 바뀐 것 한 건(ARTEL-1083).
 *
 * **기록 시각으로 계산한다.** 변경 내역 표가 따로 있는 것이 아니라, 각 표의 생성 · 수정 · 해결 시각이
 * 마지막 실행 시작보다 뒤인 것을 모은다. 그래서 "무엇이" 바뀌었는지(스텝 단위 차이)는 말하지 못하고,
 * 지워진 행(런에서 뺀 시나리오)은 남지 않는다.
 *
 * @property kind `ISSUE_RESOLVED` · `BUILD_REPORTED` · `SCENARIO_UPDATED` · `SCENARIO_ADDED` · `CASE_VERSION_ADDED`.
 * @property subjectId 대상 id(이슈 · 빌드 · 시나리오). `CASE_VERSION_ADDED` 는 묶음이라 null.
 * @property subjectName 대상 이름(이슈 제목 · 빌드 버전 · 시나리오 제목).
 * @property count `CASE_VERSION_ADDED` 의 건수. 나머지는 1.
 */
data class SinceLastQaChange(
    val kind: String,
    val at: Instant,
    val subjectId: String?,
    val subjectName: String?,
    val count: Int = 1,
)

/**
 * 테스트 런의 "지난 실행 이후"(ARTEL-1083). 마지막 실행이 없으면 [lastQaRun] 이 null 이고 두 목록은 비어 있다.
 */
data class SinceLastQaResponse(
    val lastQaRun: QaRunSummaryResponse?,
    val issues: List<SinceLastQaIssue>,
    val changes: List<SinceLastQaChange>,
)
