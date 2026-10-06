package kr.artel.orchestration.contentmap.macro

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.entity.QaTryEntity
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait

/**
 * 등록된 macro 를 지도에 적고 다시 읽는 경로(ARTEL-921).
 *
 * ## 이 서비스가 지는 네 가지
 *
 * 1. **거절은 값으로 돌아온다.** throw 하지 않는다 — 라우터가 그것을 `ERROR` 프레임으로 agent 에게
 *    돌려주고, 예외로 올리면 receive 체인이 끊겨 프레임 하나가 런 전체를 죽인다.
 * 2. **DB 제약으로 떨어질 입력을 먼저 거른다.** 제약 위반 예외는 제약 이름만 실려 agent 가 무엇을
 *    고쳐야 하는지 읽을 수 없다. `ck_macro_require_carries_remedy` 와 `name` 의 `VARCHAR(200)` 이
 *    그 대상이고, DB 제약은 이 서비스가 유일한 writer 가 아닐 때를 위한 backstop 으로 남는다
 *    (`AgentCapabilityWriteService` 가 `ck_capability_press_needs_key` 를 같은 이유로 두 번 본다).
 * 3. **멱등은 DB 가 진다.** `uk_macro_name` 과 `screen_macro` 의 PK 다. 앱의 `if` 로 막으면 같은
 *    이름이 동시에 둘 올 때 조회와 INSERT 사이로 빠져나간다.
 * 4. **등록 갱신은 제자리에서.** `MacroRepository.upsertByName` 이 행의 `id` 를 바꾸지 않으므로
 *    `screen_macro` 행이 살아남는다.
 *
 * 쓰기 하나가 한 트랜잭션이다. 런이 중간에 끊겨도 그때까지 적은 것이 남는다.
 *
 * ## `ContentMapMode.FROZEN` 을 여기서 보지 않는다
 *
 * 게이트는 라우터가 분기 **전에** 한 번만 거는 것이 이 서버의 규율이고(`allowContentMapWrite`),
 * macro 쓰기에 그것을 잇는 것은 ARTEL-922 다. 이 서비스는 그 검사를 하지 않는다.
 */
@Service
class MacroDefinitionService(
    private val gameInstances: GameInstanceRepository,
    private val contentMaps: ContentMapRepository,
    private val screens: ScreenRepository,
    private val macros: MacroRepository,
    private val screenMacros: ScreenMacroRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
) {

    /**
     * 등록된 macro 정의를 적는다. 같은 이름이 이미 있으면 **제자리에서 갱신하고** 관계 행을 남긴다.
     *
     * [MacroRegisterRequest.screens] 는 기존 관계에 더한다. 지우지 않는 이유는 관계를 빼는 길이
     * v1 에 없고(ARTEL-925), 등록할 때마다 지우면 agent 가 앞서 단 `screen` 을 잃기 때문이다.
     */
    suspend fun register(qaTry: QaTryEntity, request: MacroRegisterRequest): MacroWrite {
        val type = MacroWriteFrames.REGISTER
        val name = request.name?.trim()
        if (name.isNullOrEmpty()) return refuse(type, "payload.name is required")
        if (name.length > MacroWriteFrames.MAX_NAME_LENGTH) {
            return refuse(type, "payload.name is longer than ${MacroWriteFrames.MAX_NAME_LENGTH} characters")
        }
        // `source` 는 원본이라 공백을 깎지 않는다. 들여쓰기가 Python 문법의 일부고, 사람이 보는
        // diff 가 agent 가 적은 그대로여야 한다. 비었는지만 본다.
        val source = request.source
        if (source.isNullOrBlank()) return refuse(type, "payload.source is required")
        if (source.length > MacroWriteFrames.MAX_SOURCE_LENGTH) {
            return refuse(type, "payload.source is longer than ${MacroWriteFrames.MAX_SOURCE_LENGTH} characters")
        }

        val definition = request.definition
        if (definition == null || definition.isNull) return refuse(type, "payload.definition is required")
        if (!definition.isObject) return refuse(type, "payload.definition must be a JSON object")
        val definitionText = objectMapper.writeValueAsString(definition)
        if (definitionText.length > MacroWriteFrames.MAX_DEFINITION_LENGTH) {
            return refuse(
                type,
                "payload.definition is longer than ${MacroWriteFrames.MAX_DEFINITION_LENGTH} characters"
            )
        }
        // `ck_macro_require_carries_remedy` 를 여기서 한 번 더 본다. DB 가 막아 주기는 하지만 그
        // 실패는 제약 이름이 실린 예외라 agent 가 무엇을 고쳐야 하는지 읽을 수 없다.
        if (hasRequireWithoutRemedy(definition)) {
            return refuse(
                type,
                "every require statement must carry a remedy — a failed require that names no " +
                    "remedy leaves the agent nothing to do next"
            )
        }

        val parameters = request.parameters.map { it.trim() }
        if (parameters.any { it.isEmpty() }) {
            return refuse(type, "payload.parameters must be a list of names: ${request.parameters}")
        }

        val contentMapId = when (val step = resolveContentMap(qaTry, type)) {
            is Step.No -> return MacroWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        val screenIds = when (val step = resolveScreens(type, contentMapId, request.screens)) {
            is Step.No -> return MacroWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }

        val existing = macros.findByContentMapIdAndName(contentMapId, name)
        return transactionalOperator.executeAndAwait {
            // `ON CONFLICT DO UPDATE ... RETURNING id` 라 충돌해도 유니크 위반이 아니라 UPDATE 로
            // 가고 늘 한 행이 돌아온다. 그래서 `AgentCapabilityWriteService` 가 쓰는 "트랜잭션 밖에서
            // 다시 조회해 복구" 가 여기에는 필요 없다.
            val macroId = macros.upsertByName(
                contentMapId = contentMapId,
                name = name,
                source = source,
                definitionJson = Json.of(definitionText),
                parameterNames = Json.of(objectMapper.writeValueAsString(parameters)),
            )
            screenIds.forEach { screenMacros.link(it, macroId) }
            MacroWrite.Registered(
                macroId = macroId,
                name = name,
                created = existing == null,
                screenIds = screenMacros.findScreenIdsByMacroId(macroId).toList(),
            )
        }
    }

    /**
     * 저장된 정의를 이름으로 읽는다.
     *
     * 이 build 의 `content_map` 에서만 찾는다. 이름의 유일 범위가 그 안이므로, 좁히지 않으면 다른
     * build 에 등록된 같은 이름의 정의를 돌려줄 수 있고 그것은 이 게임에서 돌지 않는다.
     */
    suspend fun read(qaTry: QaTryEntity, request: MacroReadRequest): MacroWrite {
        val type = MacroWriteFrames.READ
        val name = request.name?.trim()
        if (name.isNullOrEmpty()) return refuse(type, "payload.name is required")

        val contentMapId = when (val step = resolveContentMap(qaTry, type)) {
            is Step.No -> return MacroWrite.Rejected(step.reason)
            is Step.Ok -> step.value
        }
        // 존재하지 않는 `scene` 을 묻는 거부 문장과 같은 모양이다.
        val macro = macros.findByContentMapIdAndName(contentMapId, name)
            ?: return refuse(type, "references an unknown macro: $name")
        val macroId = requireNotNull(macro.id) { "macro 가 저장되지 않았다" }

        return MacroWrite.Loaded(
            macroId = macroId,
            name = macro.name,
            source = macro.source,
            definition = objectMapper.readTree(macro.definitionJson.asString()),
            parameters = readNames(macro.parameterNames),
            screenIds = screenMacros.findScreenIdsByMacroId(macroId).toList(),
        )
    }

    /**
     * `remedy` 가 없거나 비거나 문자열이 아닌 `require` 가 tree 안에 있나.
     *
     * tree 전체를 훑는다. `require` 가 `if` 몸통 안에 중첩될 수 있어 고정 경로로는 닿지 않는다.
     * 기대는 것은 "`require` node 는 `kind` 가 `require` 이고 `remedy` 를 든다" 하나뿐이고,
     * statement 목록을 담는 key 이름에는 기대지 않는다 — tree 의 모양은 ARTEL-918 이 확정한다.
     *
     * `ck_macro_require_carries_remedy` 가 같은 판정을 SQL 로 한 번 더 한다. 두 벌인 것은
     * 의도다: 이쪽은 agent 가 읽을 문장을 만들고, 저쪽은 이 서비스를 지나지 않는 쓰기를 막는다.
     */
    private fun hasRequireWithoutRemedy(node: JsonNode): Boolean {
        if (node.isObject) {
            val kind = node.path(MacroWriteFrames.KIND_FIELD)
            if (kind.isTextual && kind.asText() == MacroWriteFrames.REQUIRE_KIND) {
                val remedy = node.path(MacroWriteFrames.REMEDY_FIELD)
                if (!remedy.isTextual || remedy.asText().isBlank()) return true
            }
        }
        return node.any { hasRequireWithoutRemedy(it) }
    }

    /**
     * 이 런의 지도를 찾는다.
     *
     * `scene` 을 해석하지 않는 것이 `AgentCapabilityWriteService.resolve` 와 다른 점이다. macro 는
     * `content_map` 에 매달리고 `scene` 경계를 넘을 수 있어, agent 가 어느 `scene` 에 서 있는지가
     * 이 쓰기의 조건이 아니다. `qa_run_id` 도 요구하지 않는다 — macro 행에 런을 걸 칸이 없다.
     */
    private suspend fun resolveContentMap(qaTry: QaTryEntity, type: String): Step<Long> {
        val instance = gameInstances.findById(qaTry.gameInstanceId)
            ?: return reject(type, "cannot resolve the game instance of this run")
        val buildId = instance.lastGameBuildId
            ?: return reject(type, "this game instance has no build to attach a macro to")
        // 빌드마다 지도가 하나다(ARTEL-642). 고를 것이 없다.
        val contentMapId = contentMaps.findByGameBuildId(buildId)?.id
            ?: return reject(type, "this game build has no content map yet")
        return Step.Ok(contentMapId)
    }

    /**
     * agent 가 지목한 `screen` 이 이 build 의 것인지 본다.
     *
     * 지목하지 않는 것도 정상이다. 서 있는 `screen` 을 모르면(아직 안 굳은 `scene`) 관계를 비운 채
     * 등록하고, 빈 관계의 뜻은 **아직 어디서 쓸지 모른다**는 것이다(ARTEL-925).
     *
     * 남의 build 의 `screen` 을 받으면 그 macro 는 자기 게임에 없는 화면에서 쓸 수 있다고 적힌다.
     * 못 찾은 id 를 전부 모아 한 번에 돌려준다 — 하나씩 거절하면 agent 가 왕복을 반복한다.
     */
    private suspend fun resolveScreens(
        type: String,
        contentMapId: Long,
        requestedScreens: List<String>,
    ): Step<List<Long>> {
        if (requestedScreens.isEmpty()) return Step.Ok(emptyList())
        val parsed = requestedScreens.map { it.trim().toLongOrNull() }
        if (parsed.any { it == null }) {
            return reject(type, "payload.screens must be numeric screen ids: $requestedScreens")
        }
        // 같은 id 를 두 번 적은 것은 오류가 아니라 중복이다. 합치고 넘어간다.
        val ids = parsed.filterNotNull().distinct()
        val found = screens.findIdsInContentMap(contentMapId, ids).toList().toSet()
        val missing = ids.filterNot { it in found }
        if (missing.isNotEmpty()) {
            return reject(type, "references screens outside this build's content map: $missing")
        }
        return Step.Ok(ids)
    }

    /** `parameter_names` 는 문자열 배열이다(`ck_macro_parameter_names_array`). */
    private fun readNames(stored: Json): List<String> =
        objectMapper.readTree(stored.asString()).mapNotNull { it.takeIf(JsonNode::isTextual)?.asText() }

    /** 사유 문장의 앞머리를 요청 타입으로 통일한다. agent 로그에서 어느 프레임이 막혔는지 바로 읽힌다. */
    private fun message(type: String, reason: String) = "$type $reason"

    private fun reject(type: String, reason: String) = Step.No(message(type, reason))

    private fun refuse(type: String, reason: String) = MacroWrite.Rejected(message(type, reason))

    /**
     * 검증 한 칸의 결과. 거절 사유를 값으로 들고 다니게 하려고 둔 것이고, 바깥으로 나가는 결과는
     * [MacroWrite] 다.
     */
    private sealed interface Step<out T> {
        data class Ok<T>(val value: T) : Step<T>
        data class No(val reason: String) : Step<Nothing>
    }
}
