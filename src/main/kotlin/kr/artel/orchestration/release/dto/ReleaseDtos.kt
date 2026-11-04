package kr.artel.orchestration.release.dto

import java.time.Instant

/**
 * 출시 기준의 숫자(ARTEL-1386). 기준 항목은 고정이고 숫자만 프로젝트가 정한다. 비율은 0~100 의 정수 % 다.
 *
 * - [maxPending]: 판정 전 TC 가 이 수 이하
 * - [maxRegression]: 회귀가 이 수 이하
 * - [minPassRate]: 판정한 TC 중 통과 비율이 이 % 이상
 * - [maxOpenBlockers]: 이 빌드의 QA 에서 나온 안 닫힌 `BLOCKER` · `CRITICAL` 이슈가 이 수 이하
 * - [maxInconclusiveRate]: 이 빌드의 TC 중 판정 못함 비율이 이 % 이하
 */
data class ReleaseCriteria(
    val maxPending: Int,
    val maxRegression: Int,
    val minPassRate: Int,
    val maxOpenBlockers: Int,
    val maxInconclusiveRate: Int
) {
    companion object {
        val DEFAULT = ReleaseCriteria(maxPending = 0, maxRegression = 0, minPassRate = 95, maxOpenBlockers = 0, maxInconclusiveRate = 5)
    }
}

data class ReleaseCriteriaResponse(
    val criteria: ReleaseCriteria,
    /** 프로젝트가 아직 숫자를 정하지 않아 기본값이면 true. */
    val isDefault: Boolean,
    val defaults: ReleaseCriteria,
    val updatedAt: Instant?
)

data class BuildRefResponse(
    val gameBuildId: String,
    val version: String,
    val isEditor: Boolean,
    val createdAt: Instant
)

/**
 * 한 빌드의 TC 를 상태마다 센 것. TC 하나는 그 빌드의 마지막 판정 하나로 센다.
 *
 * - [passed] · [failed]: 마지막 판정이 통과 · 실패. 사람이 검토해 바꿨으면 그 판정을 쓴다. [failed] 에 [regression] 은 들지 않는다.
 * - [regression]: 실패 중 앞 버전에서 통과(`VERIFIED`)였던 것.
 * - [inconclusive]: 시도는 했지만 통과 · 실패가 한 번도 안 난 것(`INCONCLUSIVE`, 검토에서 판정 못함 · TC 내용 문제).
 * - [pending]: 이 빌드에서 한 번도 판정하지 않은 것.
 * - [carried]: 이 빌드에서 직접 보지 않고 앞 버전 판정을 이어받아 [passed] · [failed] 에 든 것.
 */
data class BuildVerificationSummaryResponse(
    val build: BuildRefResponse,
    val total: Int,
    val passed: Int,
    val failed: Int,
    val regression: Int,
    val inconclusive: Int,
    val pending: Int,
    val carried: Int,
    val openBlockers: Int,
    val lastJudgedAt: Instant?
)

/** 그날이 끝났을 때의 누적 상태와, 그날 시작한 QA 실행 수. */
data class BuildVerificationDayResponse(
    /** `yyyy-MM-dd`, 요청한 시간대의 달력. */
    val date: String,
    val passed: Int,
    val failed: Int,
    val regression: Int,
    val inconclusive: Int,
    val runs: Int
)

data class BuildVerificationProgressResponse(
    val summary: BuildVerificationSummaryResponse,
    /** 회귀를 가른 앞 버전. 없으면 null 이고 회귀도 0 이다. */
    val previousBuild: BuildRefResponse?,
    val timeZone: String,
    /** 빌드가 올라온 날부터 오늘까지(최대 90일), 오래된 것부터. */
    val days: List<BuildVerificationDayResponse>
)

data class BuildVerificationSummariesResponse(val items: List<BuildVerificationSummaryResponse>)
