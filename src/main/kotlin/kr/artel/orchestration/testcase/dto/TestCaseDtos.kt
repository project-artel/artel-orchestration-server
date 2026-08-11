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
)

/** 케이스 수정. 검증상태(verificationStatus)도 여기서 바꿀 수 있다. */
data class TestCaseUpdateRequest(
    val scene: String? = null,
    val step: String? = null,
    val precondition: String? = null,
    val expectedValue: String? = null,
    val verificationStatus: String? = null,
)

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
)

data class TestCaseListResponse(val items: List<TestCaseResponse>)
