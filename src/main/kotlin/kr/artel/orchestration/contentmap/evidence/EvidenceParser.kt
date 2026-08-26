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
                createdBy = blob.path("createdBy").toCreatedBy(),
                calledBy = blob.path("calledBy").textList(),
            )
        }

    /**
     * 두 세대를 한 자리에서 읽는다.
     *
     * schema 6 은 문자열 `"<OwnerType>.<field>"`, schema 7 은 `{field, prefab, prefabId}` 객체다.
     * **분기를 여기 한 곳에 두는 이유:** 아래 소비자(`SpawnAttribution`, 렌더)는 `field` 만 쓰므로
     * 세대를 알 필요가 없다. 저쪽에서 갈라 놓으면 세대가 하나 더 늘 때 고칠 곳이 흩어진다.
     *
     * 객체인데 `field` 가 없으면 **버린다.** 그것 없이는 어느 필드가 만들었는지 모르고, 프리팹 이름만
     * 든 항목은 씬 위의 자리를 못 찾아 스폰 귀속에 쓸 수 없다.
     */
    private fun JsonNode.toCreatedBy(): List<CreatedBy> =
        arrayItems().mapNotNull { item ->
            if (item.isTextual) {
                item.asText().takeIf { it.isNotBlank() }?.let { CreatedBy(field = it) }
            } else {
                item.path("field").asTextOrNull()?.let { field ->
                    CreatedBy(
                        field = field,
                        prefab = item.path("prefab").asTextOrNull(),
                        prefabId = item.path("prefabId").asLongOrNull(),
                    )
                }
            }
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
     *
     * **[ConditionNode.Always] 는 문서가 "조건 없음"이라고 말했을 때만 나온다.** 못 읽은 것을 그리로
     * 보내면 "아무 때나 할 수 있는 행동"과 "전제가 있는 행동"이 같은 모양이 되고, 진짜 전제가 예외도
     * 로그도 없이 사라진다. 판단이 안 서는 자리의 정답은 전부 [ConditionNode.Unknown] 이다.
     *
     * 판정 순서가 곧 규칙이다. 아래 셋은 자리를 바꾸면 뜻이 달라진다:
     *
     * 1. 객체가 아닌 조건(문자열·배열)을 **모양 추론보다 먼저** 걸러 낸다. 객체가 아니면 아래의 필드
     *    검사가 전부 조용히 false 라, 뒤에 두면 "필드가 하나도 없음"에 걸려 [ConditionNode.Always] 가 된다.
     * 2. `kind` 정규화는 [conditionKind] 한 곳에서만 한다. 그래서 [GroupKind.from] 은 소문자만 받으면
     *    되고, 대소문자 정책이 두 곳으로 갈라져 한쪽만 고쳐지는 일이 없다.
     * 3. 이름표가 있으면 모양으로 다시 추측하지 않는다. 문서가 자기가 무엇인지 말했는데 우리가 그 말을
     *    모르는 것이므로, 모르는 채로 남기는 것이 맞다.
     */
    private fun JsonNode.toCondition(): ConditionNode {
        if (isMissingNode || isNull) return ConditionNode.Always
        if (!isObject) return ConditionNode.Unknown(reason = CONDITION_NOT_AN_OBJECT, unread = null)

        val kind = conditionKind() ?: return inferByShape()
        return when (kind) {
            "always" -> ConditionNode.Always
            "test" -> toTest()
            "gesture" -> toGesture()
            // 원문 `kind` 를 사유로 남긴다. 정규화한 소문자가 아니라 문서가 쓴 글자여야, 그 값이
            // `CapabilityKey.canonical` 을 타고 키에 실렸을 때 되짚어 찾을 수 있다.
            else -> GroupKind.from(kind)?.let { ConditionNode.Group(it, toParts()) }
                ?: toUnknown(fallbackReason = path("kind").asTextOrNull())
        }
    }

    /**
     * `kind` 를 어휘 한 벌로 정규화한다 — 앞뒤 공백을 떼고 소문자로, 남는 것이 없으면 null.
     *
     * 대문자 `"EVERY"` 가 [GroupKind.from] 의 정확한 문자열 비교에서 떨어져 그룹 전체가
     * [ConditionNode.Unknown] 이 되는 일이 실제로 있었다. `Unknown` 은 자식을 담지 않으므로 그 아래
     * 트리가 통째로 파싱되지도 않는다. `test`·`gesture`·`always` 도 같은 사고를 겪을 수 있어 어휘 전체를
     * 여기서 한 번에 접는다.
     */
    private fun JsonNode.conditionKind(): String? =
        path("kind").asTextOrNull()?.trim()?.lowercase()?.takeIf { it.isNotEmpty() }

    /**
     * 이름표 없는 노드를 모양으로 읽는다.
     *
     * 이 자리에 오는 것은 실측에서 두 모양뿐이다 — `left`/`operator`/`right` 를 든 test 20개와 `input` 을
     * 든 gesture 8개(2026-08-26 로컬 스택 `capability_evidence` 465행 기준). 고치기 전에는 그 28개가
     * 전부 [ConditionNode.Always] 가 됐다.
     *
     * **`parts` 를 든 노드는 그룹인 줄 알면서도 [ConditionNode.Unknown] 으로 남긴다.** 모양은 그것이
     * 그룹이라는 데까지만 말하고 `every` 인지 `either` 인지는 말하지 않는데, 거기서 찍으면 "둘 중 하나"가
     * "둘 다"로 뒤집힌다 — 조건을 삼키는 것과 방향만 반대인 같은 사고이고, [ConditionNode] 가 "평탄화
     * 금지"로 막아 둔 것과 같은 사고다. 이 모양을 만드는 생산자도 없다: 근거 문서는 그룹에 소문자 `kind`
     * 를 쓰고, 우리 쪽 직렬화는 대문자 `kind` 를 쓴다.
     */
    private fun JsonNode.inferByShape(): ConditionNode = when {
        // 필드가 하나도 없는 노드. `ConditionNode.Always` 를 직렬화하면 정확히 이 모양(`{}`)이 된다.
        size() == 0 -> ConditionNode.Always
        path("parts").isArray -> toUnknown(fallbackReason = GROUP_KIND_MISSING)
        hasNonNull("left") && hasNonNull("operator") && hasNonNull("right") -> toTest()
        hasNonNull("input") -> toGesture()
        else -> toUnknown(fallbackReason = CONDITION_KIND_MISSING)
    }

    /**
     * 비교 하나.
     *
     * 이름표로 온 길과 모양으로 읽은 길이 **같은 이 함수**를 부른다. 두 길이 필드를 따로 읽으면 같은
     * 노드가 길에 따라 다른 값이 되고, 그것이 이 이슈를 다시 여는 방법이다.
     */
    private fun JsonNode.toTest() = ConditionNode.Test(
        left = path("left").asTextOrNull().orEmpty(),
        operator = path("operator").asTextOrNull().orEmpty(),
        right = path("right").asTextOrNull().orEmpty(),
        context = path("context").asTextOrNull(),
        offset = path("offset").asInt(0),
        subjectLost = path("subjectLost").asTextOrNull(),
    )

    /** 입력 조건 하나. [toTest] 와 같은 이유로 두 길이 이 함수를 함께 쓴다. */
    private fun JsonNode.toGesture() = ConditionNode.Gesture(
        input = path("input").asTextOrNull().orEmpty(),
        offset = path("offset").asInt(0),
    )

    private fun JsonNode.toParts(): List<ConditionNode> = path("parts").arrayItems().map { it.toCondition() }

    /**
     * 못 읽은 노드.
     *
     * 노드가 **자기 사유를 들고 있으면 그것을 그대로 쓴다.** [fallbackReason] 은 문서가 사유를 말하지
     * 않았을 때만 들어간다 — 우리가 왜 못 읽었는지가 그 자리의 유일한 단서이기 때문이다.
     */
    private fun JsonNode.toUnknown(fallbackReason: String?) = ConditionNode.Unknown(
        reason = path("reason").asTextOrNull() ?: fallbackReason,
        unread = path("unread").asTextOrNull(),
    )

    companion object {
        /** 이 파서가 아는 가장 낮은 세대. schema 5 이하는 `alsoReachedBy` 가 없어 `wiring`이 통째로 샌다. */
        const val MIN_SUPPORTED_SCHEMA = 6

        /** `parts` 를 들었지만 `every` 인지 `either` 인지 말하지 않은 노드의 사유. */
        const val GROUP_KIND_MISSING = "group-kind-missing"

        /** 이름표도 없고 아는 모양도 아닌 노드의 사유. */
        const val CONDITION_KIND_MISSING = "condition-kind-missing"

        /** 조건 자리에 객체가 아닌 것(문자열·숫자·배열)이 온 사유. */
        const val CONDITION_NOT_AN_OBJECT = "condition-not-an-object"
    }
}

// ---------- JsonNode 읽기 도우미. 이 파일 밖으로 나가지 않는다 ----------

/** 필드가 없거나 JSON null 이면 null. 빈 문자열은 값으로 친다. */
private fun JsonNode?.asTextOrNull(): String? =
    if (this == null || isMissingNode || isNull) null else asText()

private fun JsonNode?.asIntOrNull(): Int? =
    if (this == null || isMissingNode || isNull || !isNumber) null else asInt()

private fun JsonNode?.asLongOrNull(): Long? =
    if (this == null || isMissingNode || isNull || !isNumber) null else asLong()

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
