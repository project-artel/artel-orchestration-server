package kr.artel.orchestration.qa.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * `GET /api/qa-runs/{qaRunId}/verdicts` (ARTEL-1134). 런 하나의 스텝 판정과 그 근거, 시나리오 실행 순서대로.
 *
 * 근거는 agent 가 스텝 STATUS 로 보낸 것을 그대로 옮긴다(ARTEL-1133): 판정한 이유, 기대 항목마다 무엇을 보았고
 * 그 근거가 판독 기록과 맞았는지, 근거 전체의 상태. 근거 칸이 생기기 전의 판정은 `evidence` 가 `none` 이고
 * `message` 만 있다.
 */
data class QaRunVerdictsResponse(
    val qaRunId: String,
    /** 화면 머리의 출처: 어느 TR 을 어느 빌드로 돌린 실행인가. */
    val testRunId: String,
    val testRunName: String,
    val gameBuildVersion: String?,
    val status: String,
    val startedAt: Instant,
    val completedAt: Instant?,
    val tries: List<QaTryVerdicts>,
)

data class QaTryVerdicts(
    val qaTryId: String,
    val testScenarioId: String,
    val scenarioTitle: String,
    /** `ORDERED` 면 같은 TC 가 이어진 구간의 마지막 스텝이 검증 스텝이고, `FREE` 면 TC 가 있는 스텝이 모두 검증 스텝이다. */
    val executionMode: String,
    val status: String,
    val model: String?,
    val startedAt: Instant,
    val completedAt: Instant?,
    /** 시나리오의 모든 스텝. 판정이 아직 없는 스텝도 실린다 — 진행 중 화면이 대기 스텝을 그린다. */
    val steps: List<QaScenarioStepView>,
    val verdicts: List<QaStepVerdict>,
    /** 이 시나리오 실행이 보고한 결함. `step` 으로 스텝에 붙는다. */
    val issues: List<QaStepIssue>,
    /** 사람이 다시 볼 판정 수(ARTEL-1252). */
    val reviewCount: Int = 0,
)

data class QaScenarioStepView(
    val step: Int,
    val title: String,
    val caseId: String?,
    /** 그 TC 의 기대 결과. 이 실행의 빌드 버전 내용이다. TC 가 지워졌으면 null. */
    val expected: String?,
)

data class QaStepIssue(
    val issueId: String,
    val title: String,
    val severity: String,
    val step: Int?,
)

/** 스텝 하나에서 한 행동을 종류별로 센 것: `Return ×2`, `클릭 ×1`. */
data class QaStepAction(
    /** `key` · `click` · `drag` · `other`. */
    val kind: String,
    /** 누른 키나 그 밖의 행동 이름. 클릭 · 드래그는 null. */
    val label: String?,
    val count: Int,
)

data class QaStepVerdict(
    /** 이 판정을 실은 STATUS 행. TC 판정 이력이 가리키는 곳이다. */
    val logId: String,
    val step: Int,
    /** 시나리오 스텝의 문장. 시나리오가 그 뒤에 고쳐졌으면 지금 문장이다. */
    val stepTitle: String?,
    val caseId: String?,
    val isVerification: Boolean,
    val passed: Boolean,
    val message: String,
    val reason: String?,
    /** `verified` · `uncertain` · `none`. */
    val evidence: String,
    /** agent 가 보낸 항목 그대로: `[{expected, observed, met, sources:[{kind, key, value, recorded, citation}]}]`. */
    val checks: JsonNode?,
    /** 근거로 댄 캡처. 내려받는 주소는 이 응답을 만들 때 새로 발급한다. */
    val captures: List<QaVerdictCapture>,
    /** 이 스텝에서 한 행동. */
    val actions: List<QaStepAction>,
    val judgedAt: Instant?,
    /** 판정을 코드로 다시 본 결과(ARTEL-1252). 검증 판정에만 있다. 판정을 바꾸지 않는다. */
    val codeCheck: QaCodeCheck? = null,
    /** 사람이 다시 볼 판정인가. 기준은 `needsReview` 다. */
    val needsReview: Boolean = false,
)

data class QaCodeCheck(
    /** `PASS` · `FAIL`(기록으로 확정) · `WEAK_PASS` · `WEAK_FAIL`(바뀌었는지만 봄) · `UNKNOWN`(확인 못 함). */
    val result: String,
    /** 효과마다 확인한 내용. TC 에 효과 정보(`case_key`)가 없으면 비어 있다. */
    val items: List<QaCodeCheckItem>,
)

data class QaCodeCheckItem(
    /** `active-state` · `scene` · `screen` · `ui-value` · `instantiate` 등 효과의 종류. */
    val kind: String,
    val target: String,
    val expected: String?,
    /** 판정 시점의 기록에서 읽은 값. 확인하지 못했으면 null. */
    val observed: String?,
    val result: String,
    /** 확인하지 못한 까닭. */
    val note: String?,
)

data class QaVerdictCapture(
    val captureId: String,
    val url: String,
    val expiresAt: Instant,
)
