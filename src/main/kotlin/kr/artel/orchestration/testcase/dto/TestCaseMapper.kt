package kr.artel.orchestration.testcase.dto

import kr.artel.orchestration.testcase.entity.TestCaseEntity
import java.time.Instant

/** TestCase 엔티티 → 응답 DTO. 여러 도메인(케이스/시나리오 조합)이 공유한다. id 계열은 문자열. */
fun TestCaseEntity.toTestCaseResponse(): TestCaseResponse =
    TestCaseResponse(
        id = requireNotNull(id).toString(),
        projectId = projectId.toString(),
        category = category,
        title = title,
        precondition = precondition,
        expected = expected,
        verificationStatus = verificationStatus,
        lastVerifiedBuildId = lastVerifiedBuildId?.toString(),
        createdAt = createdAt ?: Instant.EPOCH,
    )
