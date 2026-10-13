package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * 케이스 라이브러리의 "버전" 트리 한 줄(ARTEL-1021). 최신 build 부터.
 *
 * @property latest 프로젝트의 가장 최근 build 인가. 라이브러리가 기본으로 고르고 "현재 버전" 으로 표시한다.
 * @property total 이 버전 표에 보일 케이스 수. 지도 케이스는 이 버전에 몫이 있는 것, 사람이 쓴 케이스는 전부다.
 * @property counts 상태별 수. 다섯 상태가 늘 다 실린다(0 포함).
 */
data class TestCaseVersionSummary(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val createdAt: Instant,
    val latest: Boolean,
    val total: Int,
    val counts: Map<String, Int>,
)

data class TestCaseVersionListResponse(val items: List<TestCaseVersionSummary>)

/**
 * 버전 표의 한 줄(ARTEL-1021). 내용은 이 버전의 몫이고, 이 버전에 없으면 비교 버전의 몫이다.
 *
 * @property origin `MAP`(지도가 낸 케이스, 버전마다 몫이 있다) 또는 `MANUAL`(사람이 쓴 케이스, 모든 버전에 있다고 본다).
 * @property verificationStatus 이 버전의 판정. 이 버전에 없는 케이스(`ONLY_OTHER`)면 null 이다. 사람이 쓴
 *   케이스가 이 버전에서 판정을 받은 적 없으면 `DRAFT` 다.
 * @property presence 비교할 때만 뜻이 있다. `BOTH` · `ONLY_THIS`(비교 버전에 없음) · `ONLY_OTHER`(이 버전에 없음).
 *   비교하지 않으면 늘 `BOTH` 다.
 * @property compareVerificationStatus 비교 버전의 판정. 비교하지 않거나 비교 버전에 없으면 null 이다.
 */
data class TestCaseVersionRow(
    val id: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val origin: String,
    val folderId: String?,
    val createdAt: Instant,
    val verificationStatus: String?,
    val presence: String,
    val compareVerificationStatus: String?,
)

data class TestCaseVersionCasesResponse(
    val gameBuildId: String,
    val compareToGameBuildId: String?,
    val items: List<TestCaseVersionRow>,
)

/** [kr.artel.orchestration.testcase.repository.TestCaseVersionRepository.countByBuild] 의 한 줄. */
data class VersionStatusCount(
    val gameBuildId: Long,
    val manual: Boolean,
    val verificationStatus: String,
    val total: Long,
)
