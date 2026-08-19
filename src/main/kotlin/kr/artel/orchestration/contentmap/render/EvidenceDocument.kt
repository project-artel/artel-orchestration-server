package kr.artel.orchestration.contentmap.render

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper

/**
 * evidence JSON(스키마 6, `wv2cs.py` 가 소비하던 것과 같은 문서)을 읽는 유일한 경계.
 *
 * evidence는 파생물이라 언제든 재생성되고 필드 유무가 들쭉날쭉할 수 있다(원본 파이썬도
 * dict 기반으로 필요한 키만 읽었다). 그래서 엄격한 POJO 트리 대신 Jackson [JsonNode] 를
 * 그대로 들고 다닌다 — 이 파일이 정해진 키를 읽는 원시 접근 확장 함수([textOrNull],
 * [arrayOrEmpty] 등)를 갖고 있고, `render/` 아래 다른 파일들은 대부분 여기 함수로만 원본을
 * 읽는다. 임의 키를 통째로 순회해야 하는 드문 예외(gesture 조건 렌더링)만 [evidenceObjectMapper]
 * 를 직접 재사용한다 — 새 `ObjectMapper` 인스턴스를 만들지 않는다는 경계는 지킨다.
 */
class EvidenceDocument private constructor(private val root: JsonNode) {

    val schema: Int = root.path("schema").let { if (it.isMissingNode || it.isNull) 0 else it.asInt(0) }
    val capture: String? = root.path("capture").textOrNull()

    /** `wv2cs.py` 의 헤더 한 줄(선행 `// ` 없이) — 파일 맨 위 주석에 그대로 쓰인다. */
    val captureHeaderLine: String = run {
        val b = root.path("build")
        "capture=$capture schema=$schema unity=${b.path("unity").textOrNull()} " +
            "platform=${b.path("platform").textOrNull()} backend=${b.path("backend").textOrNull()} " +
            "sdk=${b.path("sdk").textOrNull()}"
    }

    val scenes: List<String> = root.path("scenes").arrayOrEmpty().map { it.asText() }
    val capabilities: List<String> = root.path("capabilities").arrayOrEmpty().map { it.asText() }
    val gaps: List<String> = root.path("gaps").arrayOrEmpty().map { it.asText() }

    /** 타입 풀네임 -> evidence record 목록. JSON 문서 순서를 그대로 보존한다(결정론). */
    val types: Map<String, List<JsonNode>> = run {
        val node = root.path("types")
        node.fieldNamesOrEmpty().associateWith { name -> node.path(name).arrayOrEmpty() }
    }

    /** 타입 풀네임 -> 아직 씬 오브젝트에 놓이지 못한 evidence 묶음. */
    val unplaced: Map<String, UnplacedBlob> = run {
        val node = root.path("unplaced")
        node.fieldNamesOrEmpty().associateWith { name ->
            val blob = node.path(name)
            UnplacedBlob(
                evidence = blob.path("evidence").arrayOrEmpty(),
                createdBy = blob.path("createdBy").arrayOrEmpty().map { it.asText() },
            )
        }
    }

    val objects: List<JsonNode> = root.path("objects").arrayOrEmpty()
    val persistentObjects: List<JsonNode> = root.path("persistentObjects").arrayOrEmpty()

    companion object {
        fun parse(json: String): EvidenceDocument = EvidenceDocument(evidenceObjectMapper.readTree(json))
    }
}

/**
 * evidence 문서를 읽는 하나뿐인 [ObjectMapper]. [EvidenceDocument] 는 이걸로 트리를 파싱하고,
 * [ExpressionWriter]의 gesture 조건 렌더링처럼 임의 키를 그대로 훑어야 하는 드문 예외적
 * raw 순회도 새 인스턴스를 만들지 않고 이걸 재사용한다.
 */
internal val evidenceObjectMapper = ObjectMapper()

/** evidence는 아직 씬 오브젝트에 붙이지 못한 타입 하나의 근거 뭉치([wv2cs.py]의 `unplaced[type]`). */
data class UnplacedBlob(val evidence: List<JsonNode>, val createdBy: List<String>)

// ---------- JsonNode 편의 확장 ----------
// 다른 render/* 파일은 원시 `.get("x")`/`.asText()` 를 직접 흩뿌리지 않고 이 함수들로만 읽는다.

/** 필드가 없거나 JSON null 이면 null, 있으면 텍스트. */
fun JsonNode?.textOrNull(): String? {
    if (this == null || this.isMissingNode || this.isNull) return null
    return this.asText()
}

/** 배열이면 요소 목록, 아니면(없음 포함) 빈 목록. */
fun JsonNode.arrayOrEmpty(): List<JsonNode> = if (this.isArray) this.toList() else emptyList()

/** 객체면 필드 이름 목록(문서 순서 보존), 아니면 빈 목록. */
fun JsonNode.fieldNamesOrEmpty(): List<String> = if (this.isObject) this.fieldNames().asSequence().toList() else emptyList()

/** record 항목들의 `offset` — 없으면 0(파이썬 `.get("offset", 0)` 과 동일). */
fun JsonNode.offsetOrZero(): Int {
    val offset = this.path("offset")
    return if (offset.isMissingNode || offset.isNull) 0 else offset.asInt(0)
}
