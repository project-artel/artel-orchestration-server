package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * 케이스 라이브러리의 "버전" 트리 한 줄(ARTEL-1021). 최신 build 부터.
 *
 * @property latest 프로젝트의 가장 최근 build 인가. 라이브러리가 기본으로 고르고 "현재 버전" 으로 표시한다.
 * @property total 이 버전 표에 보일 케이스 수 — 이 버전에 몫이 있는 케이스다(ARTEL-1023).
 * @property counts 상태별 수. 다섯 상태가 늘 다 실린다(0 포함).
 * @property groupId 이 버전을 넣은 묶음. 없으면 null.
 * @property hidden 목록에서 숨긴 버전. 목록에는 그대로 실리고, 화면이 숨긴 버전 보기에서만 그린다.
 */
data class TestCaseVersionSummary(
    val gameBuildId: String,
    val version: String,
    val label: String?,
    val createdAt: Instant,
    /** editor 행인가(ARTEL-1180). 같은 version 의 build 행과 따로 있다. */
    val isEditor: Boolean = false,
    val latest: Boolean,
    val total: Int,
    val counts: Map<String, Int>,
    val groupId: String?,
    val hidden: Boolean,
)

/** 버전에 사용자 생성 케이스를 넣거나 빼는 요청(ARTEL-1031). */
data class TestCaseVersionCasesRequest(val caseIds: List<String?>? = null)

/** 실제로 넣거나 뺀 케이스. 지도 케이스 · 다른 프로젝트 · 이미 그 상태인 것은 싣지 않는다. */
data class TestCaseVersionCasesChangeResponse(val caseIds: List<String>)

/** 버전 묶음 한 줄(ARTEL-1023). 버전과 같은 층에 있고, 묶음 안에 묶음은 없다. */
data class TestCaseVersionGroupResponse(val id: String, val name: String)

/**
 * @property groups 버전 묶음. 이름순이다.
 * @property unversionedTotal 어느 버전에도 몫이 없는 케이스 수. 라이브러리의 "버전 없음" 줄이다 — 사람이 쓴 케이스가
 *   QA 판정을 받기 전, 또는 모든 버전에서 사라졌지만 시나리오가 인용해 남은 케이스다.
 */
data class TestCaseVersionListResponse(
    val items: List<TestCaseVersionSummary>,
    val groups: List<TestCaseVersionGroupResponse>,
    val unversionedTotal: Int,
)

/** 묶음 만들기 · 이름 바꾸기. */
data class TestCaseVersionGroupRequest(val name: String? = null)

/** 버전을 묶음에 넣거나(`groupId`) 묶음에서 뺀다(null · 빈 문자열). */
data class TestCaseVersionGroupAssignRequest(val groupId: String? = null)

data class TestCaseVersionHiddenRequest(val hidden: Boolean? = null)

/**
 * 버전의 케이스를 담은 새 folder 를 만든다(ARTEL-1023).
 *
 * @property name folder 이름. 없으면 `"<버전> 사본"` 이고, 같은 자리에 같은 이름이 있으면 ` (2)` 를 붙인다.
 * @property parentId 둘 부모 folder. 없으면 최상위다.
 */
data class TestCaseVersionCopyRequest(val name: String? = null, val parentId: String? = null)

/**
 * 버전 표의 한 줄(ARTEL-1021). 내용은 이 버전의 몫이고, 이 버전에 없으면 비교 버전의 몫이다.
 * 사람이 쓴 케이스는 사람이 고친 내용(정체)을 싣는다.
 *
 * @property origin `MAP`(지도가 낸 케이스) 또는 `MANUAL`(사람이 쓴 케이스). 둘 다 그 버전에 몫이 있어야 그 버전에
 *   있다(ARTEL-1023) — 사람이 쓴 케이스는 그 버전에서 QA 판정을 받으면 몫이 생긴다.
 * @property verificationStatus 이 버전의 판정. 이 버전에 없는 케이스(`ONLY_OTHER`)면 null 이다. "버전 없음"
 *   목록에서는 케이스의 상태다.
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

/** @property gameBuildId "버전 없음" 목록이면 null 이다. */
data class TestCaseVersionCasesResponse(
    val gameBuildId: String?,
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

/**
 * 케이스 목록에 보일 칸만 읽은 케이스 정체(ARTEL-1212). 목록에 쓰지 않는 JSON 칸(metadata · condition 등)은 읽지 않는다.
 *
 * @property fromMap metadata 맨 위 `origin` 이 지도(`content-map`)인가. DB 가 답한다.
 */
data class CaseListFields(
    val id: Long,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val verificationStatus: String,
    val createdAt: Instant?,
    val fromMap: Boolean,
)

/** 케이스 목록에 보일 칸만 읽은 버전 몫(ARTEL-1212). */
data class VersionListFields(
    val testCaseId: Long,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val verificationStatus: String,
)
