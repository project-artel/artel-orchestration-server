package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import java.time.Instant

/** TestCase 엔티티 → 응답 DTO. 여러 도메인(케이스/시나리오 조합)이 공유한다. id 계열은 문자열. */
fun TestCaseEntity.toTestCaseResponse(): TestCaseResponse =
    TestCaseResponse(
        id = requireNotNull(id).toString(),
        projectId = projectId.toString(),
        scene = scene,
        step = step,
        precondition = precondition,
        expectedValue = expectedValue,
        status = status,
        verificationStatus = verificationStatus,
        lastVerifiedBuildId = lastVerifiedBuildId?.toString(),
        createdAt = createdAt ?: Instant.EPOCH,
    )

/**
 * 엔티티 → 단건 조회 응답. 목록용 [toTestCaseResponse]에 `evidenceGaps`만 얹는다.
 *
 * `evidence_gaps`를 컬럼으로 승격하지 않았으므로 여기서 [metadata]를 읽는다. 별도 질의를 쏘지
 * 않는 이유는 단건 조회가 이미 이 행을 통째로 읽어와 **metadata가 손에 있기** 때문이다 —
 * `metadata->'source'->'evidence_gaps'` SQL을 한 번 더 도는 쪽이 오히려 왕복이 하나 는다.
 */
fun TestCaseEntity.toTestCaseDetailResponse(objectMapper: ObjectMapper): TestCaseDetailResponse {
    val base = toTestCaseResponse()
    return TestCaseDetailResponse(
        id = base.id,
        projectId = base.projectId,
        scene = base.scene,
        step = base.step,
        precondition = base.precondition,
        expectedValue = base.expectedValue,
        status = base.status,
        verificationStatus = base.verificationStatus,
        lastVerifiedBuildId = base.lastVerifiedBuildId,
        createdAt = base.createdAt,
        evidenceGaps = evidenceGaps(objectMapper),
    )
}

/**
 * `metadata.source.evidence_gaps`의 이유 코드들. 경로가 없으면(사람이 만든 케이스, 이 필드가 없는
 * 명세) 빈 목록이다.
 *
 * JSONB 컬럼이라 파싱은 실패할 수 없다(Postgres가 이미 유효성을 보장한다). 값 모양이 생성기 쪽
 * 사정으로 바뀔 수 있어 스칼라만 취하고 빈 문자열은 버린다 — 객체가 섞여 들어와도 화면에 원시
 * JSON이 찍히지 않는다.
 */
private fun TestCaseEntity.evidenceGaps(objectMapper: ObjectMapper): List<String> =
    objectMapper.readTree(metadata.asString())
        .path("source")
        .path("evidence_gaps")
        .map { it.asText() }
        .filter { it.isNotBlank() }
