package kr.artel.orchestration.game.service

import io.r2dbc.postgresql.codec.Json
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import org.springframework.dao.DataIntegrityViolationException
import org.springframework.stereotype.Component
import java.time.Instant

private const val BUILD_RETRY_ATTEMPTS = 3

/**
 * (프로젝트, 버전)으로 게임 빌드를 찾고 없으면 만드는 한 곳.
 *
 * SDK 등록([SdkRegistrationService])과 MCP 의 버전 선언([GameBuildService.declare])이 함께 쓴다. 두 길이 같은 규칙으로 찾아야
 * MCP 가 먼저 만든 빌드에 나중에 같은 버전의 게임이 붙는다(ARTEL-1447).
 */
@Component
class GameBuildRegistrar(
    private val buildRepository: GameBuildRepository,
) {
    /**
     * development build 는 같은 (프로젝트, 버전)의 non-editor 행을, editor 는 같은 (프로젝트, 버전)의 editor 행을
     * 쓴다(ARTEL-1180). 없으면 만든다. 같은 버전의 두 행은 서로 덮지 않는다(ARTEL-1014) — editor 는 버전을 올리지
     * 않고도 계속 바뀌는 작업 상태라서, 같은 버전 재등록은 그 editor 행의 스캔을 덮는다.
     *
     * 있는 행은 스캔이 실려 있을 때만 저장한다. 같은 version 으로 찾았으므로 version 은 바뀌지 않는다. 스캔이 없는
     * 등록은 앞 스캔을 지우지 않는다.
     *
     * 조회 후 저장 사이에 경합이 난다. 유니크 인덱스(`uk_game_build_project_version`,
     * `uk_game_build_project_editor_version`)가 그 충돌을 예외로 만들고, 여기서 다시 읽어 재시도한다. 재시도마다 조회를
     * 새로 해야 이미 만들어진 행을 찾아 끝날 수 있다.
     */
    suspend fun findOrCreate(
        projectId: Long,
        version: String,
        isEditor: Boolean,
        sceneScan: Json?,
        now: Instant
    ): Pair<GameBuildEntity, Boolean> {
        var remaining = BUILD_RETRY_ATTEMPTS
        while (true) {
            try {
                val existing = if (isEditor) {
                    buildRepository.findEditorByVersion(projectId, version)
                } else {
                    buildRepository.findNonEditorByVersion(projectId, version)
                }
                return when {
                    existing == null -> buildRepository.save(
                        GameBuildEntity(
                            projectId = projectId,
                            version = version,
                            sceneScan = sceneScan,
                            isEditor = isEditor,
                            createdAt = now,
                            updatedAt = now
                        )
                    ) to true
                    existing.version == version && sceneScan == null -> existing to false
                    else -> buildRepository.save(
                        existing.copy(version = version, sceneScan = sceneScan ?: existing.sceneScan, updatedAt = now)
                    ) to false
                }
            } catch (e: DataIntegrityViolationException) {
                if (remaining-- <= 0) throw e
            }
        }
    }
}
