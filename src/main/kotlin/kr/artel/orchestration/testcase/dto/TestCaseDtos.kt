package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.testcase.entity.TestCaseKind
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
    /** `FUNCTION`(기능) 또는 `EXCEPTION`(예외)(ARTEL-1306). null 이면 기능이다. */
    val kind: String? = null,
    /** 하고 나면 가는 화면(ARTEL-1312). null 이거나 빈 문자열이면 화면이 바뀌지 않는 TC 다. */
    val arrivesAt: String? = null,
    /** true면 검토 전 초안이다. 기존 화면의 생성 동작은 유지한다. */
    val draft: Boolean = false,
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
    /** 종류를 바꾼다(ARTEL-1306). null 이면 건드리지 않는다. */
    val kind: String? = null,
    /** 도착 화면을 바꾼다(ARTEL-1312). null 이면 건드리지 않고, 빈 문자열이면 "화면이 바뀌지 않는다" 로 정한다. */
    val arrivesAt: String? = null,
    val reviewStatus: String? = null,
    /**
     * 이 케이스가 돌 때 SDK 가 읽어 보낼 관측 이름(ARTEL-1280). null 이면 건드리지 않고, 빈 목록이면 `metadata.observe`
     * 를 지운다. 원소를 `String?` 로 받는 이유는 [TestCaseBulkDeleteRequest] 와 같다 — `[null]` 을 서비스가 400 으로 막는다.
     */
    val observe: List<String?>? = null,
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
 */
data class TestCaseResponse(
    val id: String,
    val projectId: String,
    val scene: String,
    val step: String,
    val precondition: String?,
    val expectedValue: String,
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
    /** `FUNCTION`(기능) 또는 `EXCEPTION`(예외)(ARTEL-1306). */
    val kind: String = TestCaseKind.FUNCTION.name,
    /** 하고 나면 가는 화면(씬 전이 TC)(ARTEL-1312). 화면이 안 바뀌면 null. */
    val arrivesAt: String? = null,
    val reviewStatus: String = "READY",
    /** 승인을 요청한 사람의 `app_user.id`. 요청 중이 아니면 null. */
    val reviewRequestedBy: String? = null,
    /** 이 케이스가 돌 때 SDK 가 읽어 보낼 관측 이름(ARTEL-1280). `metadata.observe` 그대로이고, 정한 적 없으면 null 이다. */
    val observe: List<String>? = null,
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
 * @property evidenceGaps 명세 근거에서 확인이 필요한 이유 코드
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
    val verificationStatus: String,
    val lastVerifiedBuildId: String?,
    val createdAt: Instant,
    val folderId: String?,
    val origin: String,
    val evidenceGaps: List<String>,
    /** [TestCaseResponse.functions] 와 같다. */
    val functions: TestCaseFunctions? = null,
    /** [TestCaseResponse.kind] 와 같다. */
    val kind: String = TestCaseKind.FUNCTION.name,
    /** [TestCaseResponse.arrivesAt] 와 같다. */
    val arrivesAt: String? = null,
    val reviewStatus: String = "READY",
    /** 승인을 요청한 사람의 `app_user.id`. 요청 중이 아니면 null. */
    val reviewRequestedBy: String? = null,
    /** [TestCaseResponse.observe] 와 같다. */
    val observe: List<String>? = null,
)

/** 생성 전에 검토 상태 API 지원 여부를 확인하는 응답. */
data class TestCaseReviewStatusOptions(val statuses: List<String> = listOf("DRAFT", "READY"))
