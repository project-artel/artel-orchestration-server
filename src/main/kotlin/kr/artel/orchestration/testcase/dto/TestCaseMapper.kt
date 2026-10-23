package kr.artel.orchestration.testcase.dto

import com.fasterxml.jackson.core.JsonFactory
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.generator.MapTestCaseWriter
import java.time.Instant

/** 저장된 JSON 칸을 읽는다. 상태가 없는 읽기라 화면용 매핑마다 새로 만들지 않는다. */
private val metadataReader = jacksonObjectMapper()

/** metadata 의 `origin` 만 훑는 파서를 만든다. */
private val metadataFactory = JsonFactory()

/**
 * metadata 맨 위의 `origin` 이 지도(`content-map`)인가. 트리를 짓지 않고 앞에서부터 훑다가 그 칸을 만나면 멈춘다.
 *
 * 케이스 목록은 줄마다 이 값을 본다. 예전에는 줄마다 metadata 전체를 트리로 읽어(2천여 줄이면 약 2MB) 목록 응답의
 * 절반 가까이가 여기서 쓰였다(ARTEL-1212). 읽지 못하는 metadata 는 지도가 아니다.
 */
fun isMapOrigin(metadata: String): Boolean =
    runCatching {
        metadataFactory.createParser(metadata).use { parser ->
            if (parser.nextToken() != JsonToken.START_OBJECT) return@use false
            while (parser.nextToken() == JsonToken.FIELD_NAME) {
                val name = parser.currentName()
                val value = parser.nextToken()
                if (name == MapTestCaseWriter.ORIGIN_FIELD) {
                    return@use value == JsonToken.VALUE_STRING && parser.text == MapTestCaseWriter.ORIGIN
                }
                parser.skipChildren()
            }
            false
        }
    }.getOrDefault(false)

/**
 * 케이스의 출처(ARTEL-1031). 지도가 낸 케이스는 `MAP`(화면의 "자동 생성"), 나머지는 `MANUAL`("사용자 생성")이다.
 * 사람이 화면에서 쓴 케이스와 예전 엑셀 경로로 들어온 케이스는 `origin` 표시가 없어 `MANUAL` 이다.
 */
fun TestCaseEntity.originLabel(): String = if (isMapOrigin(metadata.asString())) "MAP" else "MANUAL"

/**
 * TestCase 엔티티 → 응답 DTO. 여러 도메인(케이스/시나리오 조합)이 공유한다. id 계열은 문자열.
 *
 * [folderId] 는 케이스가 처음 담긴 folder 다(ARTEL-1023). 케이스가 folder 를 칸으로 들지 않으므로 부르는 쪽이
 * 항목 표에서 읽어 넘긴다.
 */
fun TestCaseEntity.toTestCaseResponse(folderId: Long? = null): TestCaseResponse =
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
        folderId = folderId?.toString(),
        origin = originLabel(),
        functions = functionsOrNull(),
    )

/** 저장된 허용 범위. 정한 적 없으면(컬럼이 NULL) null 이다. 읽을 수 없는 값도 null 로 읽어 목록 전체를 막지 않는다. */
fun TestCaseEntity.functionsOrNull(): TestCaseFunctions? =
    functions?.let { runCatching { metadataReader.readValue(it.asString(), TestCaseFunctions::class.java) }.getOrNull() }

/**
 * 엔티티 → 단건 조회 응답. 목록용 [toTestCaseResponse]에 `evidenceGaps`만 얹는다.
 *
 * `evidence_gaps`를 컬럼으로 승격하지 않았으므로 여기서 [metadata]를 읽는다. 별도 질의를 쏘지
 * 않는 이유는 단건 조회가 이미 이 행을 통째로 읽어와 **metadata가 손에 있기** 때문이다 —
 * `metadata->'source'->'evidence_gaps'` SQL을 한 번 더 도는 쪽이 오히려 왕복이 하나 는다.
 */
fun TestCaseEntity.toTestCaseDetailResponse(objectMapper: ObjectMapper, folderId: Long? = null): TestCaseDetailResponse {
    val base = toTestCaseResponse(folderId)
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
        folderId = base.folderId,
        origin = base.origin,
        evidenceGaps = evidenceGaps(objectMapper),
        functions = base.functions,
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
