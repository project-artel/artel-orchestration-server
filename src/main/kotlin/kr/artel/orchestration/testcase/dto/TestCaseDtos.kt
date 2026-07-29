package kr.artel.orchestration.testcase.dto

import java.time.Instant

/** FE가 케이스를 만들 때. 값은 자연어. */
data class TestCaseCreateRequest(
    val category: String? = null,
    val title: String? = null,
    val precondition: String? = null,
    val expected: String? = null,
)

/** 케이스 수정. 검증상태(verificationStatus)도 여기서 바꿀 수 있다. */
data class TestCaseUpdateRequest(
    val category: String? = null,
    val title: String? = null,
    val precondition: String? = null,
    val expected: String? = null,
    val verificationStatus: String? = null,
)

/** 조회 응답 한 줄. id 계열은 FE 64비트 정밀도 손실 방지로 문자열로 낸다. */
data class TestCaseResponse(
    val id: String,
    val projectId: String,
    val category: String,
    val title: String,
    val precondition: String?,
    val expected: String,
    val verificationStatus: String,
    val lastVerifiedBuildId: String?,
    val createdAt: Instant,
)

data class TestCaseListResponse(val items: List<TestCaseResponse>)
