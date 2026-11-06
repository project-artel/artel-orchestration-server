package kr.artel.orchestration.game.service

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kr.artel.orchestration.game.dto.GameFunctionCatalogResponse
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 한 빌드가 등록한 cheat · behavior 목록 (`GAME_FUNCTION_CATALOG`).
 *
 * 항목은 읽어서 판단하는 값이 아니라 agent 에게 그대로 건네는 값이다. 그래서 DTO 로 접지 않고 `JsonNode` 로 둔다.
 * 읽는 것은 `name` 하나뿐이다 — run 옵션이 이름으로 목록을 고르고, 허용 검사가 이름으로 맞춘다.
 */
@Service
class GameFunctionCatalogService(
    private val buildRepository: GameBuildRepository,
    private val instanceRepository: GameInstanceRepository,
    private val projectRepository: ProjectRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(GameFunctionCatalogService::class.java)

    /**
     * `GAME_FUNCTION_CATALOG` 프레임을 그 인스턴스가 마지막으로 등록한 빌드에 덮어쓴다.
     *
     * 프레임이 깨졌거나 인스턴스에 빌드가 없으면 저장하지 않고 로그만 남긴다. 소켓을 닫을 이유가 아니다 — 목록은 보조
     * 신호이고, 다음 프레임이 같은 자리를 다시 채운다.
     *
     * @return 저장했는가.
     */
    suspend fun record(gameInstanceId: Long, payload: JsonNode): Boolean {
        val revision = payload.path("revision").takeIf { it.isIntegralNumber && it.canConvertToInt() }?.intValue()
        if (revision == null || revision < 0) {
            logger.warn("GAME_FUNCTION_CATALOG 의 revision 이 정수가 아니어서 버린다 [instanceId: {}]", gameInstanceId)
            return false
        }
        val buildId = instanceRepository.findById(gameInstanceId)?.lastGameBuildId
        if (buildId == null) {
            logger.warn("GAME_FUNCTION_CATALOG 를 받았지만 인스턴스에 빌드가 없다 [instanceId: {}]", gameInstanceId)
            return false
        }
        val stored = objectMapper.createObjectNode().apply {
            set<JsonNode>("cheats", entriesOf(payload.path("cheats")))
            set<JsonNode>("behaviors", entriesOf(payload.path("behaviors")))
        }
        return buildRepository.storeGameFunctionCatalog(
            id = buildId,
            catalog = objectMapper.writeValueAsString(stored),
            revision = revision,
            receivedAt = Instant.now(clock)
        ) == 1
    }

    /** 사용자가 볼 수 있는 빌드의 목록. 프로젝트나 빌드에 접근할 수 없으면 null 이고 컨트롤러가 404 로 옮긴다. */
    suspend fun catalogOf(userId: Long, projectId: Long, buildId: Long): GameFunctionCatalogResponse? {
        projectRepository.findAccessibleById(projectId, userId) ?: return null
        val build = buildRepository.findAccessibleById(buildId, projectId, userId) ?: return null
        return build.toCatalog().toResponse()
    }

    /** 접근 확인을 이미 마친 호출부가 빌드 id 로 목록을 읽는다. 빌드가 없거나 id 가 null 이면 빈 목록이다. */
    suspend fun catalogOfBuild(buildId: Long?): StoredGameFunctionCatalog {
        val build = buildId?.let { buildRepository.findById(it) } ?: return StoredGameFunctionCatalog.EMPTY
        return build.toCatalog()
    }

    private fun GameBuildEntity.toCatalog(): StoredGameFunctionCatalog {
        val json = gameFunctionCatalog ?: return StoredGameFunctionCatalog.EMPTY
        val tree = objectMapper.readTree(json.asString())
        return StoredGameFunctionCatalog(
            revision = gameFunctionCatalogRevision ?: 0,
            receivedAt = gameFunctionCatalogReceivedAt,
            cheats = tree.path("cheats").toList(),
            behaviors = tree.path("behaviors").toList()
        )
    }

    private fun StoredGameFunctionCatalog.toResponse() =
        GameFunctionCatalogResponse(revision, receivedAt, cheats, behaviors)

    /** 배열이 아니면 빈 배열이다. `name` 이 문자열이 아닌 항목은 이름으로 고를 수 없어 버린다. */
    private fun entriesOf(node: JsonNode) = objectMapper.createArrayNode().also { out ->
        if (node.isArray) node.filter { it.path("name").isTextual && it.path("name").asText().isNotBlank() }.forEach(out::add)
    }
}

/**
 * 저장된 목록. [revision] 이 0 이면 이 빌드는 목록을 보낸 적이 없다.
 */
data class StoredGameFunctionCatalog(
    val revision: Int,
    val receivedAt: Instant?,
    val cheats: List<JsonNode>,
    val behaviors: List<JsonNode>
) {
    companion object {
        val EMPTY = StoredGameFunctionCatalog(0, null, emptyList(), emptyList())
    }
}
