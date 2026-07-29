package kr.artel.orchestration.game.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.dto.GameBuildListResponse
import kr.artel.orchestration.game.dto.GameBuildResponse
import kr.artel.orchestration.game.dto.UpdateGameBuildRequest
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 게임 빌드는 사람이 만들지 않는다. SDK가 버전을 보고할 때 생기고([SdkRegistrationService]),
 * 여기서는 읽기와 설명 수정만 한다.
 *
 * 그래서 생성도 삭제도 없다. 삭제는 다음 등록에서 같은 행이 다시 생기므로, 지울 수 있다는
 * 인상만 주고 실제로는 지워지지 않는 동작이 된다.
 *
 * 접근할 수 없는(비참여자·삭제된) 프로젝트나 빌드는 null로 응답한다 — 컨트롤러가 404로 옮긴다.
 */
@Service
class GameBuildService(
    private val buildRepository: GameBuildRepository,
    private val projectRepository: ProjectRepository,
    private val clock: Clock
) {
    suspend fun list(userId: Long, projectId: Long): GameBuildListResponse? {
        projectRepository.findAccessibleById(projectId, userId) ?: return null
        val items = buildRepository.findAllForMember(projectId, userId)
            .map { it.toResponse() }
            .toList()
        return GameBuildListResponse(items)
    }

    suspend fun update(
        userId: Long,
        projectId: Long,
        buildId: Long,
        request: UpdateGameBuildRequest
    ): GameBuildResponse? {
        val build = buildRepository.findAccessibleById(buildId, projectId, userId) ?: return null
        return buildRepository.save(build.applying(request)).toResponse()
    }

    private fun GameBuildEntity.applying(request: UpdateGameBuildRequest) = copy(
        // 빈 문자열은 "지우기"다. null은 "이 필드는 건드리지 않음"이라 여기서 구분한다.
        label = request.label.merged(label),
        notes = request.notes.merged(notes),
        updatedAt = Instant.now(clock)
    )

    private fun String?.merged(current: String?): String? = when {
        this == null -> current
        isBlank() -> null
        else -> trim()
    }

    private fun GameBuildEntity.toResponse() = GameBuildResponse(
        id = requireNotNull(id).toString(),
        projectId = projectId.toString(),
        version = version,
        label = label,
        notes = notes,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
