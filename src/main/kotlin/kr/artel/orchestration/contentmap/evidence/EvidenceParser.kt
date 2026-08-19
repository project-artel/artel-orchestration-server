package kr.artel.orchestration.contentmap.evidence

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.common.error.BadRequestException

/**
 * 근거 문서 JSON 을 [EvidenceDocumentModel] 로 바꾸는 **하나뿐인 경계**.
 *
 * 여기서만 [JsonNode] 를 만지고, 아래로는 타입 있는 모델만 내려보낸다(`coding-style.md`). 문서는
 * 파생물이라 언제든 다시 구워지고 키가 늘거나 빈다 — 모르는 키는 버리고, 없어서는 안 될 키가 없으면
 * **그 자리에서** 필드 이름과 함께 실패한다. 세 층 아래에서 null 로 나타나는 것보다 낫다.
 *
 * 데이터 클래스 자동 매핑을 쓰지 않는 이유: 조건 트리가 `kind` 로 갈리는 다형 트리이고, 같은 이름의
 * `calledBy` 가 `types` 와 `unplaced` 에서 서로 다른 형식이며(메서드 안정 키 대 타입 이름),
 * `capabilities` 는 이 도메인에서 뜻이 겹쳐 [EvidenceDocumentModel.promises] 로 이름을 바꿔 담는다.
 */
class EvidenceParser(private val objectMapper: ObjectMapper) {

    fun parse(json: String): EvidenceDocumentModel {
        val root = runCatching { objectMapper.readTree(json) }
            .getOrElse { throw BadRequestException("근거 문서를 JSON 으로 읽지 못했다: ${it.message}") }
        return parse(root)
    }

    fun parse(root: JsonNode): EvidenceDocumentModel {
        val schema = root.path("schema").asIntOrNull()
            ?: throw BadRequestException("근거 문서에 schema 가 없다")
        if (schema < MIN_SUPPORTED_SCHEMA) {
            throw BadRequestException("지원하지 않는 근거 문서 schema: $schema (최소 $MIN_SUPPORTED_SCHEMA)")
        }
        return EvidenceDocumentModel(
            schema = schema,
            capture = root.path("capture").asTextOrNull()
                ?: throw BadRequestException("근거 문서에 capture 가 없다"),
            promises = root.path("capabilities").textList(),
            build = root.path("build").toBuild(),
            scenes = root.path("scenes").textList(),
            types = root.path("types").toRecordsByType(),
            unplaced = root.path("unplaced").toUnplaced(),
            objects = root.path("objects").arrayItems().map { it.toSceneObject() },
            persistentObjects = root.path("persistentObjects").arrayItems().map { it.toSceneObject() },
            gaps = root.path("gaps").textList(),
        )
    }

    private fun JsonNode.toBuild() = EvidenceBuild(
        unity = path("unity").asTextOrNull(),
        platform = path("platform").asTextOrNull(),
        backend = path("backend").asTextOrNull(),
        development = path("development").let { if (it.isBoolean) it.asBoolean() else null },
        sdk = path("sdk").asTextOrNull(),
        digest = path("evidence").asTextOrNull(),
    )

    /** 문서 순서를 보존한다 — 같은 문서를 두 번 읽으면 같은 순서가 나와야 재적재가 결정론적이다. */
    private fun JsonNode.toRecordsByType(): Map<String, List<EvidenceRecord>> =
        fieldNamesInOrder().associateWith { type ->
            path(type).arrayItems().map { it.toRecord(owner = type) }
        }

    private fun JsonNode.toUnplaced(): Map<String, UnplacedType> =
        fieldNamesInOrder().associateWith { type ->
            val blob = path(type)
            UnplacedType(
                evidence = blob.path("evidence").arrayItems().map { it.toRecord(owner = type) },
                createdBy = blob.path("createdBy").textList(),
                calledBy = blob.path("calledBy").textList(),
            )
        }

    /**
     * [owner] 를 따로 받는 이유: 레코드 안의 `owner` 는 실측 318건 중 71건이 `entryId` 의 타입과
     * 다르고, 배치와 그룹핑이 쓰는 것은 **레코드가 매달린 키** 쪽이다. 문서가 둘을 모두 주면 같은
     * 값이지만, 없을 때 키를 쓰는 것이 맞다.
     */
    private fun JsonNode.toRecord(owner: String): EvidenceRecord {
        val condition = path("condition")
        return EvidenceRecord(
            owner = path("owner").asTextOrNull() ?: owner,
            entry = path("entry").asTextOrNull().orEmpty(),
            entryId = path("entryId").asTextOrNull()
                ?: throw BadRequestException("근거 레코드에 entryId 가 없다 (owner=$owner)"),
            source = path("source").asTextOrNull().orEmpty(),
            methodId = path("methodId").asTextOrNull()
                ?: throw BadRequestException("근거 레코드에 methodId 가 없다 (owner=$owner)"),
            recordKind = path("recordKind").asTextOrNull().orEmpty(),
            triggerKind = path("triggerKind").asTextOrNull().orEmpty(),
            confidence = path("confidence").asTextOrNull().orEmpty(),
            callPath = path("callPath").textList(),
            condition = condition.toCondition(),
            // 타입 트리에서 되쓰지 않는다. 우리 모델이 못 담은 키가 조용히 사라진다.
            conditionJson = if (condition.isMissingNode || condition.isNull) "{}" else condition.toString(),
            inputs = path("inputs").arrayItems().map {
                EvidenceInput(
                    kind = it.path("kind").asTextOrNull().orEmpty(),
                    control = it.path("control").asTextOrNull().orEmpty(),
                    phase = it.path("phase").asTextOrNull().orEmpty(),
                    absent = it.path("absent").asBoolean(false),
                    offset = it.path("offset").asInt(0),
                )
            },
            effects = path("effects").arrayItems().map {
                EvidenceEffect(
                    kind = it.path("kind").asTextOrNull().orEmpty(),
                    category = it.path("category").asTextOrNull().orEmpty(),
                    target = it.path("target").asTextOrNull(),
                    detail = it.path("detail").asTextOrNull(),
                    source = it.path("source").asTextOrNull(),
                    offset = it.path("offset").asInt(0),
                )
            },
            calls = path("calls").arrayItems().map {
                EvidenceCall(
                    targetId = it.path("targetId").asTextOrNull().orEmpty(),
                    target = it.path("target").asTextOrNull().orEmpty(),
                    receiver = it.path("receiver").asTextOrNull(),
                    receiverWhere = it.path("receiverWhere").asTextOrNull(),
                    args = it.path("args").asTextOrNull(),
                    offset = it.path("offset").asInt(0),
                )
            },
            handles = path("handles").arrayItems().map {
                EvidenceHandle(
                    channel = it.path("channel").asTextOrNull(),
                    channelType = it.path("channelType").asTextOrNull(),
                    member = it.path("member").asTextOrNull(),
                    handler = it.path("handler").asTextOrNull(),
                    handlerId = it.path("handlerId").asTextOrNull(),
                    offset = it.path("offset").asInt(0),
                )
            },
            alsoReachedBy = path("alsoReachedBy").arrayItems().mapNotNull {
                val entryId = it.path("entryId").asTextOrNull() ?: return@mapNotNull null
                EvidenceArrival(
                    entry = it.path("entry").asTextOrNull().orEmpty(),
                    entryId = entryId,
                    triggerKind = it.path("triggerKind").asTextOrNull().orEmpty(),
                    callPath = it.path("callPath").textList(),
                )
            },
            gaps = path("gaps").textList(),
            calledBy = path("calledBy").textList(),
            loopsBackTo = path("loopsBackTo").asIntOrNull(),
            handedOverTo = path("handedOverTo").asTextOrNull(),
        )
    }

    private fun JsonNode.toSceneObject() = SceneObject(
        path = path("path").asTextOrNull().orEmpty(),
        selector = path("selector").asTextOrNull(),
        scene = path("scene").asTextOrNull().orEmpty(),
        active = path("active").asBoolean(true),
        components = path("components").arrayItems().map { component ->
            SceneComponent(
                type = component.path("type").asTextOrNull().orEmpty(),
                calls = component.path("calls").arrayItems().map {
                    ComponentCall(
                        event = it.path("event").asTextOrNull().orEmpty(),
                        targetType = it.path("targetType").asTextOrNull().orEmpty(),
                        targetPath = it.path("targetPath").asTextOrNull(),
                        method = it.path("method").asTextOrNull().orEmpty(),
                    )
                },
                refs = component.path("refs").arrayItems().map {
                    ComponentRef(
                        field = it.path("field").asTextOrNull().orEmpty(),
                        type = it.path("type").asTextOrNull().orEmpty(),
                        name = it.path("name").asTextOrNull(),
                        id = it.path("id").asIntOrNull(),
                        path = it.path("path").asTextOrNull(),
                        asset = it.path("asset").asBoolean(false),
                        carries = it.path("carries").textList(),
                    )
                },
            )
        },
        visuals = path("visuals").arrayItems().map {
            SceneVisual(
                role = it.path("role").asTextOrNull().orEmpty(),
                value = it.path("value").asTextOrNull(),
                from = it.path("from").asTextOrNull(),
                type = it.path("type").asTextOrNull(),
            )
        },
    )

    /**
     * 조건 트리는 `kind` 로 갈리는 다형 트리다. 모르는 `kind` 는 [ConditionNode.Unknown] 으로 남긴다 —
     * 버리면 "조건이 없다"가 되고, 그것은 문서가 하지 않은 말이다.
     */
    private fun JsonNode.toCondition(): ConditionNode {
        if (isMissingNode || isNull) return ConditionNode.Always
        return when (val kind = path("kind").asTextOrNull()) {
            "always", null -> ConditionNode.Always
            "test" -> ConditionNode.Test(
                left = path("left").asTextOrNull().orEmpty(),
                operator = path("operator").asTextOrNull().orEmpty(),
                right = path("right").asTextOrNull().orEmpty(),
                context = path("context").asTextOrNull(),
                offset = path("offset").asInt(0),
                subjectLost = path("subjectLost").asTextOrNull(),
            )
            "gesture" -> ConditionNode.Gesture(
                input = path("input").asTextOrNull().orEmpty(),
                offset = path("offset").asInt(0),
            )
            else -> {
                val group = GroupKind.from(kind)
                if (group != null) {
                    ConditionNode.Group(group, path("parts").arrayItems().map { it.toCondition() })
                } else {
                    ConditionNode.Unknown(
                        reason = path("reason").asTextOrNull() ?: kind,
                        unread = path("unread").asTextOrNull(),
                    )
                }
            }
        }
    }

    companion object {
        /** 이 파서가 아는 가장 낮은 세대. schema 5 이하는 `alsoReachedBy` 가 없어 배선이 통째로 샌다. */
        const val MIN_SUPPORTED_SCHEMA = 6
    }
}

// ---------- JsonNode 읽기 도우미. 이 파일 밖으로 나가지 않는다 ----------

/** 필드가 없거나 JSON null 이면 null. 빈 문자열은 값으로 친다. */
private fun JsonNode?.asTextOrNull(): String? =
    if (this == null || isMissingNode || isNull) null else asText()

private fun JsonNode?.asIntOrNull(): Int? =
    if (this == null || isMissingNode || isNull || !isNumber) null else asInt()

/**
 * 배열이면 요소들, 아니면 빈 목록.
 *
 * `elements()` 라는 이름을 쓰지 않는 것은 [JsonNode] 에 같은 이름의 멤버가 있어 확장이 가려지고,
 * 그쪽은 `Iterator` 라 조용히 다른 것이 되기 때문이다.
 */
private fun JsonNode.arrayItems(): List<JsonNode> = if (isArray) toList() else emptyList()

private fun JsonNode.textList(): List<String> = arrayItems().mapNotNull { it.asTextOrNull() }

/** 객체면 필드 이름들(문서 순서), 아니면 빈 목록. */
private fun JsonNode.fieldNamesInOrder(): List<String> =
    if (isObject) fieldNames().asSequence().toList() else emptyList()
