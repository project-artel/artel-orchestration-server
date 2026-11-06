package kr.artel.orchestration.game.dto

import com.fasterxml.jackson.databind.JsonNode
import java.time.Instant

/**
 * 빌드가 등록한 cheat · behavior 목록 (`GET /api/projects/{projectId}/game-builds/{buildId}/game-functions`).
 *
 * [cheats] 와 [behaviors] 의 항목은 SDK 가 보낸 객체 그대로다. 필드는 계약 1절의 `name`, `description`, `params`,
 * `target`, `group` 이고, orchestration 은 `name` 외에는 읽지 않는다.
 *
 * 목록을 보낸 적 없는 빌드는 404 가 아니라 `revision` 0, `receivedAt` null, 빈 목록 둘로 답한다.
 *
 * @property revision SDK 가 프로세스마다 1 부터 올리는 번호. 프로세스가 바뀌면 다시 1 이다.
 * @property receivedAt 서버가 마지막 프레임을 받은 시각.
 */
data class GameFunctionCatalogResponse(
    val revision: Int,
    val receivedAt: Instant?,
    val cheats: List<JsonNode>,
    val behaviors: List<JsonNode>
)
