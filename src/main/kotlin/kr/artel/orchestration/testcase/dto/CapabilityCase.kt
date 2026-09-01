package kr.artel.orchestration.testcase.dto

/**
 * 기능 하나와 그것을 검증하는 케이스 하나(ARTEL-674).
 *
 * 빈 구간을 메울 때 *"이 조작은 이미 케이스로 있나"* 를 답한다.
 */
data class CapabilityCase(
    val capabilityId: Long,
    val testCaseId: Long,
)
