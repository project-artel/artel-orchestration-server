package kr.artel.orchestration.testcase.dto

import java.time.Instant

/**
 * FE가 케이스를 직접 만들 때. 값은 자연어.
 *
 * 명세 적재로 들어오는 케이스와 달리 `spec_id`/`spec_revision`/`metadata`가 없다 — 사람이 만든
 * 케이스는 명세 봉투에서 온 것이 아니고, 지어낸 출처를 붙이면 나중에 "어디서 왔나"가 거짓이 된다.
 */
data class TestCaseCreateRequest(
    val scene: String? = null,
    val step: String? = null,
    val precondition: String? = null,
    val expectedValue: String? = null,
    /** 넣을 folder(ARTEL-969). null 이거나 빈 문자열이면 folder 없음이다. */
    val folderId: String? = null,
    /**
     * 넣을 버전(game build)(ARTEL-1031). 주면 그 버전에 `DRAFT` 몫을 만들어 버전 라이브러리에 바로 보인다. null 이거나
     * 빈 문자열이면 어느 버전에도 넣지 않는다("버전 없음").
     */
    val gameBuildId: String? = null,
    /** 이 케이스를 돌릴 때 agent 에게 허용할 cheat · behavior(ARTEL-1053). null 이면 둘 다 `all` 이다. */
    val functions: TestCaseFunctions? = null,
)

/** 케이스 수정. 검증상태(verificationStatus)도 여기서 바꿀 수 있다. */
data class TestCaseUpdateRequest(
    val gameBuildId: String? = null,
    val scene: String? = null,
    val step: String? = null,
    val precondition: String? = null,
    val expectedValue: String? = null,
    val verificationStatus: String? = null,
    /**
     * 허용 범위를 바꾼다(ARTEL-1053). null 이면 건드리지 않는다. 처음 상태로 되돌릴 때는 null 이 아니라
     * `{"cheats":"all","behaviors":"all"}` 를 보낸다 — 키가 없는 것과 값이 null 인 것을 Jackson 이 가르지 못한다.
     */
    val functions: TestCaseFunctions? = null,
)

/**
 * test case 일괄 삭제 요청(ARTEL-967). id 는 응답과 같이 문자열이다.
 *
 * 원소 타입을 `String?` 로 둔다. `List<String>` 이면 `[null]` 이 Jackson 을 그대로 지나 서비스에서
 * 500 이 된다 — nullable 로 받아 서비스가 400 으로 막는다.
 */
data class TestCaseBulkDeleteRequest(
    val caseIds: List<String?>? = null,
)

/** 일괄 삭제 응답. [deleted] 는 실제로 지운 행 수이고, 다른 프로젝트나 없는 id 는 세지 않는다. */
data class TestCaseBulkDeleteResponse(val deleted: Int)

/**
 * 조회 응답 한 줄. id 계열은 FE 64비트 정밀도 손실 방지로 문자열로 낸다.
 *
 * @property status 명세를 만든 쪽이 매긴 상태. [verificationStatus](우리 QA 런의 결과)와 다른 축이라
 *   둘 다 낸다 — 화면에서 하나로 합치면 "명세는 준비됐지만 아직 검증 안 됨"을 표현할 수 없다.
 */
data class TestCaseResponse(
    val id: String,
    val projectId: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val verificationStatus: String,
    val lastVerifiedBuildId: String?,
    val createdAt: Instant,
    /**
     * 이 케이스가 든 folder(ARTEL-969). null 이면 folder 없음이다. 케이스가 여러 folder 에 들면 처음 담긴
     * folder 다(ARTEL-1023) — 전부는 folder 항목 API 가 낸다.
     */
    val folderId: String?,
    /** `MAP`(지도가 낸 케이스, 자동 생성) 또는 `MANUAL`(사용자 생성)(ARTEL-1031). */
    val origin: String,
    /** agent 에게 허용할 cheat · behavior(ARTEL-1053). 정한 적 없으면 null 이고, 그때는 둘 다 `all` 이다. */
    val functions: TestCaseFunctions? = null,
)

data class TestCaseListResponse(val items: List<TestCaseResponse>)

/**
 * 케이스 **단건** 조회 응답. [TestCaseResponse]와 같은 평면 모양에 `evidenceGaps` 하나가 더 붙는다
 * (평면이라 FE가 목록과 같은 파서로 읽는다).
 *
 * 목록과 DTO를 나눈 이유는 이 한 필드를 목록에 실으면 **행 수만큼** 늘기 때문이다. 목록은 프로젝트
 * 전량(1000건 규모)이고 목록을 읽는 쪽은 이 값을 쓰지 않는다 — 상세는 사용자가 케이스 하나를 열었을
 * 때 1건이다. 보여줄 곳이 상세 하나뿐이면 실어 나르는 곳도 상세 하나여야 한다.
 *
 * @property evidenceGaps 명세 쪽이 이 케이스를 [status]로 매긴 **이유 코드**
 *   (`metadata.source.evidence_gaps`). 없으면 빈 목록이다. 저작 Agent에는 보내지 않는다 —
 *   왜 근거가 부족한지에 대해 Agent가 할 수 있는 일이 없다(ARTEL-380).
 */
data class TestCaseDetailResponse(
    val id: String,
    val projectId: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
    val status: String?,
    val verificationStatus: String,
    val lastVerifiedBuildId: String?,
    val createdAt: Instant,
    val folderId: String?,
    val origin: String,
    val evidenceGaps: List<String>,
    /** [TestCaseResponse.functions] 와 같다. */
    val functions: TestCaseFunctions? = null,
)
