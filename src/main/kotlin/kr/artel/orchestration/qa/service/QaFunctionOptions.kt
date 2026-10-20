package kr.artel.orchestration.qa.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ObjectNode
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.game.service.StoredGameFunctionCatalog
import kr.artel.orchestration.qa.dto.CreateQaRunRequest
import kr.artel.orchestration.qa.dto.CreateQaTryRequest

/** `qa_try.run_config` 안에서 cheat 옵션이 사는 키들. 계약 5절 `Run options` 가 이름을 정했다. */
internal const val CHEAT_MODE_FIELD = "cheat_mode"
internal const val BEHAVIOR_MODE_FIELD = "behavior_mode"
internal const val ALLOWED_CHEATS_FIELD = "allowed_cheats"
internal const val ALLOWED_BEHAVIORS_FIELD = "allowed_behaviors"

/**
 * 세션을 열 때 agent 에게 실제로 보낸 이름들. 요청이 아니라 결과다.
 *
 * 요청의 `allowed*` 가 null("카탈로그 전부")인 런은 카탈로그가 바뀌면 같은 요청이 다른 목록이 된다. 그래서 보낸 이름을 이
 * 키에 따로 적고, 허용 검사([QaFunctionAllowList])와 응답의 `effectiveCheats` · `effectiveBehaviors` 가 이것을 읽는다.
 */
internal const val EFFECTIVE_CHEATS_FIELD = "effective_cheats"
internal const val EFFECTIVE_BEHAVIORS_FIELD = "effective_behaviors"

/** [QaTryPersistenceService] 가 갈아치우는 `run_config` 위에 다시 얹는, orchestration 이 쓰는 키 전부. */
internal val CARRIED_FUNCTION_FIELDS = listOf(
    CHEAT_MODE_FIELD,
    BEHAVIOR_MODE_FIELD,
    ALLOWED_CHEATS_FIELD,
    ALLOWED_BEHAVIORS_FIELD,
    EFFECTIVE_CHEATS_FIELD,
    EFFECTIVE_BEHAVIORS_FIELD
)

/**
 * cheat · behavior 를 이 런에 열어 주는지.
 *
 * `off` 면 목록이 비어 agent 가 둘 다 보지 못한다. 잘못된 값은 400 이다 — 조용히 `on` 으로 떨어지면 cheat 없이 돌린다고 믿은
 * arm 이 사실은 cheat 를 쓰고, 그 결과는 그럴듯해서 실험이 끝날 때까지 알아채지 못한다([ContentMapMode] 와 같은 이유).
 */
enum class FunctionMode(val wire: String) {
    ON("on"),
    OFF("off");

    companion object {
        val DEFAULT = ON

        val WIRE_NAMES: Set<String> = entries.mapTo(LinkedHashSet()) { it.wire }

        fun fromWire(value: String?): FunctionMode? =
            value?.trim()?.lowercase()?.let { normalized -> entries.firstOrNull { it.wire == normalized } }
    }
}

/**
 * 요청이 말한 cheat · behavior 옵션. 카탈로그를 보기 전의 값이다.
 *
 * @property allowedCheats null 이면 카탈로그의 cheat 전부다. 빈 목록은 "하나도 허용하지 않는다"이고 `cheatMode = off` 와
 *   같은 결과를 낸다.
 */
data class QaFunctionOptions(
    val cheatMode: FunctionMode = FunctionMode.DEFAULT,
    val behaviorMode: FunctionMode = FunctionMode.DEFAULT,
    val allowedCheats: List<String>? = null,
    val allowedBehaviors: List<String>? = null
) {
    /**
     * 빌드의 카탈로그에서 이 런이 쓸 항목을 고른다.
     *
     * 카탈로그를 받은 적 있는 빌드(`revision > 0`)에서 `allowed*` 가 카탈로그에 없는 이름을 들면 400 이다. 오타 하나로 cheat 가
     * 조용히 빠지면 그 런은 의도한 조건이 아닌데 정상으로 보인다. 받은 적 없는 빌드는 비교할 목록이 없어 이름을 검사하지 않으며,
     * 그 경우 고를 항목도 없어 결과는 빈 목록이다.
     */
    fun resolve(catalog: StoredGameFunctionCatalog): QaResolvedFunctions {
        val known = catalog.revision > 0
        return QaResolvedFunctions(
            options = this,
            cheats = pick("allowedCheats", cheatMode, allowedCheats, catalog.cheats, known),
            behaviors = pick("allowedBehaviors", behaviorMode, allowedBehaviors, catalog.behaviors, known)
        )
    }

    private fun pick(
        field: String,
        mode: FunctionMode,
        allowed: List<String>?,
        entries: List<JsonNode>,
        known: Boolean
    ): List<JsonNode> {
        if (known && allowed != null) {
            val names = entries.mapTo(HashSet()) { it.path("name").asText() }
            val unknown = allowed.filter { it !in names }
            if (unknown.isNotEmpty()) {
                throw BadRequestException("$field has names the build did not register: ${unknown.joinToString(", ")}")
            }
        }
        if (mode == FunctionMode.OFF) return emptyList()
        return if (allowed == null) entries else entries.filter { it.path("name").asText() in allowed }
    }
}

/**
 * 옵션과 카탈로그를 맞춘 결과. [cheats] · [behaviors] 가 세션 개설 본문의 `context.cheats` · `context.behaviors` 로 나가는
 * 항목 그대로다.
 */
data class QaResolvedFunctions(
    val options: QaFunctionOptions,
    val cheats: List<JsonNode>,
    val behaviors: List<JsonNode>
) {
    val effectiveCheats: List<String> get() = cheats.map { it.path("name").asText() }
    val effectiveBehaviors: List<String> get() = behaviors.map { it.path("name").asText() }

    /** `run_config` 에 옵션과 보낸 이름들을 쓴다. `allowed*` 가 null 이면 키를 쓰지 않는다. */
    fun writeTo(config: ObjectNode) {
        config.put(CHEAT_MODE_FIELD, options.cheatMode.wire)
        config.put(BEHAVIOR_MODE_FIELD, options.behaviorMode.wire)
        options.allowedCheats?.let { names -> config.putArray(ALLOWED_CHEATS_FIELD).also { a -> names.forEach(a::add) } }
        options.allowedBehaviors?.let { names -> config.putArray(ALLOWED_BEHAVIORS_FIELD).also { a -> names.forEach(a::add) } }
        config.putArray(EFFECTIVE_CHEATS_FIELD).also { a -> effectiveCheats.forEach(a::add) }
        config.putArray(EFFECTIVE_BEHAVIORS_FIELD).also { a -> effectiveBehaviors.forEach(a::add) }
    }
}

/**
 * `run_config` 에서 읽은 cheat · behavior 옵션과 보낸 이름들.
 *
 * 키가 없는 런(이 기능 이전의 런)은 모드 `on`, `allowed*` null, 보낸 이름 없음이다. 그런 런의 agent 는 cheat 를 받은 적 없으므로
 * 허용 검사는 모든 호출을 거절한다.
 */
data class QaFunctionAllowList(
    val cheatMode: FunctionMode = FunctionMode.DEFAULT,
    val behaviorMode: FunctionMode = FunctionMode.DEFAULT,
    val allowedCheats: List<String>? = null,
    val allowedBehaviors: List<String>? = null,
    val effectiveCheats: List<String> = emptyList(),
    val effectiveBehaviors: List<String> = emptyList()
) {
    companion object {
        fun of(runConfigJson: String?, objectMapper: ObjectMapper): QaFunctionAllowList {
            val config = runCatching { objectMapper.readTree(runConfigJson ?: "{}") }.getOrNull()
                ?: return QaFunctionAllowList()
            return QaFunctionAllowList(
                cheatMode = FunctionMode.fromWire(config.path(CHEAT_MODE_FIELD).asText(null)) ?: FunctionMode.DEFAULT,
                behaviorMode = FunctionMode.fromWire(config.path(BEHAVIOR_MODE_FIELD).asText(null)) ?: FunctionMode.DEFAULT,
                allowedCheats = config.namesAt(ALLOWED_CHEATS_FIELD),
                allowedBehaviors = config.namesAt(ALLOWED_BEHAVIORS_FIELD),
                effectiveCheats = config.namesAt(EFFECTIVE_CHEATS_FIELD).orEmpty(),
                effectiveBehaviors = config.namesAt(EFFECTIVE_BEHAVIORS_FIELD).orEmpty()
            )
        }

        private fun JsonNode.namesAt(field: String): List<String>? =
            path(field).takeIf { it.isArray }?.mapNotNull { n -> n.takeIf { it.isTextual }?.asText() }
    }
}

/** 모드 토큰 하나를 읽는다. 단일 시나리오 요청과 run 요청이 같은 함수를 쓴다. 없으면 `on` 이다. */
private fun parseFunctionMode(field: String, raw: String?): FunctionMode =
    raw?.let {
        FunctionMode.fromWire(it)
            ?: throw BadRequestException("$field must be one of ${FunctionMode.WIRE_NAMES}")
    } ?: FunctionMode.DEFAULT

/** 이름 목록 검사. null 은 "전부"이고 빈 이름과 null 원소는 400 이다. 중복은 하나로 접는다. */
private fun parseFunctionNames(field: String, raw: List<String?>?): List<String>? {
    raw ?: return null
    val names = LinkedHashSet<String>()
    for (item in raw) {
        val name = item?.trim()?.takeIf { it.isNotEmpty() }
            ?: throw BadRequestException("$field must not contain a blank name")
        if (name.length > MAX_FUNCTION_NAME_LENGTH) {
            throw BadRequestException("$field has a name longer than $MAX_FUNCTION_NAME_LENGTH characters")
        }
        names.add(name)
    }
    return names.toList()
}

private const val MAX_FUNCTION_NAME_LENGTH = 200

fun CreateQaTryRequest.toFunctionOptions() = QaFunctionOptions(
    cheatMode = parseFunctionMode("cheatMode", cheatMode),
    behaviorMode = parseFunctionMode("behaviorMode", behaviorMode),
    allowedCheats = parseFunctionNames("allowedCheats", allowedCheats),
    allowedBehaviors = parseFunctionNames("allowedBehaviors", allowedBehaviors)
)

fun CreateQaRunRequest.toFunctionOptions() = QaFunctionOptions(
    cheatMode = parseFunctionMode("cheatMode", cheatMode),
    behaviorMode = parseFunctionMode("behaviorMode", behaviorMode),
    allowedCheats = parseFunctionNames("allowedCheats", allowedCheats),
    allowedBehaviors = parseFunctionNames("allowedBehaviors", allowedBehaviors)
)

/** 허용 검사가 보는 두 method. 계약 3절의 `invoke_cheat` 와 `start_behavior` 다. */
internal const val INVOKE_CHEAT_METHOD = "invoke_cheat"
internal const val START_BEHAVIOR_METHOD = "start_behavior"
