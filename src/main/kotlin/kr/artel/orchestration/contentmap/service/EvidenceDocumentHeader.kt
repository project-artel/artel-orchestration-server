package kr.artel.orchestration.contentmap.service

import com.fasterxml.jackson.core.JsonParser
import com.fasterxml.jackson.core.JsonToken
import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.contentmap.entity.Capture

/**
 * 근거 문서의 머리에서 읽어낸 것. 문서 전체를 파싱하지 않는다.
 *
 * 근거 문서는 헤더를 맨 앞에 쓴다 — `schema` · `capture` · `capabilities` · `build` 순이고, 그 뒤에
 * `scenes` · `types` · `objects` 같은 큰 표가 온다. 실측 문서에서 헤더는 앞 400 바이트 안에 전부
 * 들어 있다.
 */
data class EvidenceDocumentHeader(
    val schemaVersion: Int,
    val capture: String,
    val evidenceDigest: String,
    val promises: Json,
    val unity: String?,
    val platform: String?,
    val backend: String?,
    val development: Boolean?,
    val sdkVersion: String?,
)

/**
 * 문서 앞부분만 읽어 헤더를 되살린다.
 *
 * 앞부분만 가져오므로 JSON 은 반드시 중간에서 잘린다. 그래서 문자열을 손으로 닫아 붙이지 않고
 * **스트리밍 파서로 필요한 필드까지만 읽고 멈춘다** — 잘린 자리에 도달하기 전에 그만두면 잘렸다는
 * 사실 자체가 문제가 되지 않는다. 중괄호를 세어 닫아 주는 방식은 잘린 지점이 중첩 안이면 틀린다.
 *
 * 못 읽으면 예외를 던진다. **조용히 null 로 넘어가면 SDK 가 필드 순서를 바꾼 날 아무도 모른 채
 * 빈 지도가 쌓인다.**
 */
object EvidenceDocumentHeaderReader {

    /**
     * 이 서버가 이해하는 문서 세대.
     *
     * 늘어나기만 하는 버전과 **뜻이 좁아진** 버전은 다르다. schema 6 에서 `label` 이 "오브젝트가
     * 보여주는 것"에서 "누를 수 있는 것에 쓰인 글자"로 좁아졌고, 5 로 읽으면 적의 남은 체력을
     * 컨트롤 이름으로 읽는다(샘플 게임 22개 중 16개가 그 경우였다). 그래서 아는 번호만 받는다.
     */
    /**
     * 7 이 더해진 이유: `createdBy` 항목이 문자열에서 `{field, prefab, prefabId}` 객체로 바뀌었다.
     * **늘어나기만 한 변경이라** 6 의 뜻이 좁아지지 않았고, 파서가 두 모양을 한 자리에서 읽는다.
     * 6 을 계속 두는 것은 이미 앉은 지도와 저장소의 골든 픽스처가 6 이기 때문이다.
     */
    val SUPPORTED_SCHEMA_VERSIONS = setOf(6, 7)

    /** 헤더를 담기에 넉넉한 양. 실측 헤더는 400 바이트 안쪽이다. */
    const val PREFIX_BYTES = 16 * 1024

    fun read(prefix: ByteArray, mapper: ObjectMapper): EvidenceDocumentHeader {
        var schemaVersion: Int? = null
        var capture: String? = null
        var promises: String? = null
        var build: JsonNode? = null

        try {
            mapper.factory.createParser(prefix).use { parser ->
                if (parser.nextToken() != JsonToken.START_OBJECT) {
                    throw BadRequestException(
                        "근거 문서가 JSON 객체로 시작하지 않습니다.",
                        "invalid_evidence_document",
                    )
                }
                while (parser.nextToken() == JsonToken.FIELD_NAME) {
                    when (parser.currentName()) {
                        "schema" -> { parser.nextToken(); schemaVersion = parser.intValue }
                        "capture" -> { parser.nextToken(); capture = parser.text }
                        "capabilities" -> { parser.nextToken(); promises = parser.readValueAsTree<JsonNode>().toString() }
                        "build" -> { parser.nextToken(); build = parser.readValueAsTree() }
                        // 모르는 필드는 건너뛴다. 여기서 끊으면 SDK 가 헤더에 필드 하나만 더해도
                        // (계약상 더하기는 허용된다) 세대를 올리지 않은 채 모든 문서가 거절된다.
                        // 헤더 뒤의 큰 표에 닿으면 skipChildren 이 잘린 자리에서 예외를 내는데,
                        // 그때는 필요한 필드가 이미 다 모여 있다.
                        else -> { parser.nextToken(); parser.skipChildren() }
                    }
                    if (schemaVersion != null && capture != null && build != null && promises != null) break
                }
            }
        } catch (e: BadRequestException) {
            throw e
        } catch (e: Exception) {
            // 필요한 필드를 다 모으기 전에 잘렸다는 뜻이다.
            if (schemaVersion == null || capture == null || build == null || promises == null) {
                throw BadRequestException(
                    "근거 문서의 머리를 읽지 못했습니다. 헤더가 앞 ${PREFIX_BYTES}바이트 안에 없거나 JSON 이 아닙니다.",
                    "invalid_evidence_document",
                )
            }
        }

        // 컬럼 폭을 넘는 값은 여기서 막는다. 통과시키면 INSERT 가 터져 500 이 나가고,
        // 신뢰할 수 없는 헤더 문자열로 서버 오류를 만들 수 있게 된다.
        fun bounded(value: String?, limit: Int, field: String): String? {
            if (value != null && value.length > limit) {
                throw BadRequestException(
                    "근거 문서의 $field 가 너무 깁니다(최대 $limit 자).",
                    "invalid_evidence_document",
                )
            }
            return value
        }

        val version = schemaVersion
            ?: throw BadRequestException("근거 문서에서 schema 를 읽지 못했습니다.", "invalid_evidence_document")
        if (version !in SUPPORTED_SCHEMA_VERSIONS) {
            throw BadRequestException(
                "지원하지 않는 근거 문서 세대입니다: schema $version " +
                    "(지원: ${SUPPORTED_SCHEMA_VERSIONS.joinToString()})",
                "unsupported_evidence_schema",
            )
        }

        val captureValue = bounded(capture, 16, "capture") ?: capture
            ?: throw BadRequestException("근거 문서에서 capture 를 읽지 못했습니다.", "invalid_evidence_document")
        if (Capture.from(captureValue) == null) {
            throw BadRequestException("알 수 없는 capture 입니다: $captureValue", "invalid_evidence_document")
        }

        val buildNode = build
            ?: throw BadRequestException("근거 문서에서 build 를 읽지 못했습니다.", "invalid_evidence_document")

        // 빈 약속으로 조용히 넘어가지 않는다. capabilities 가 비면 적재기가 control_selector 와
        // control_label 을 채우지 않아야 하는데, "약속이 없다"와 "못 읽었다"가 같은 값이 된다.
        val promisesValue = promises
            ?: throw BadRequestException(
                "근거 문서에서 capabilities 를 읽지 못했습니다.",
                "invalid_evidence_document",
            )
        val digest = buildNode.path("evidence").takeIf { it.isTextual }?.asText()
            ?: throw BadRequestException(
                "근거 문서에서 build.evidence 지문을 읽지 못했습니다. 이 값이 없으면 코드가 바뀌었는지 알 수 없습니다.",
                "invalid_evidence_document",
            )

        return EvidenceDocumentHeader(
            schemaVersion = version,
            capture = captureValue,
            evidenceDigest = bounded(digest, 32, "build.evidence")!!,
            promises = Json.of(promisesValue),
            unity = bounded(buildNode.path("unity").takeIf { it.isTextual }?.asText(), 32, "build.unity"),
            platform = bounded(buildNode.path("platform").takeIf { it.isTextual }?.asText(), 32, "build.platform"),
            backend = bounded(buildNode.path("backend").takeIf { it.isTextual }?.asText(), 16, "build.backend"),
            development = buildNode.path("development").takeIf { it.isBoolean }?.asBoolean(),
            sdkVersion = bounded(buildNode.path("sdk").takeIf { it.isTextual }?.asText(), 32, "build.sdk"),
        )
    }
}
