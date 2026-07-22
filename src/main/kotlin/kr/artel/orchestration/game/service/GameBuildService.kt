package kr.artel.orchestration.game.service

import kr.artel.orchestration.game.dto.GameBuildListResponse
import kr.artel.orchestration.game.dto.GameBuildResponse
import kr.artel.orchestration.game.dto.UpdateGameBuildRequest
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.springframework.stereotype.Service
import org.springframework.transaction.annotation.Transactional
import reactor.core.publisher.Mono
import java.time.Clock
import java.time.Instant

/**
 * 게임 빌드는 사람이 만들지 않는다. SDK가 버전을 보고할 때 생기고([SdkRegistrationService]),
 * 여기서는 읽기와 설명 수정만 한다.
 *
 * 그래서 생성도 삭제도 없다. 삭제는 다음 등록에서 같은 행이 다시 생기므로, 지울 수 있다는
 * 인상만 주고 실제로는 지워지지 않는 동작이 된다.
 */
@Service
class GameBuildService(
    private val buildRepository: GameBuildRepository,
    private val projectRepository: ProjectRepository,
    private val clock: Clock
) {
    @Transactional(readOnly = true)
    fun list(userId: Long, projectId: Long): Mono<GameBuildListResponse> =
        projectRepository.findAccessibleById(projectId, userId)
            .flatMap {
                buildRepository.findAllForMember(projectId, userId)
                    .map { build -> build.toResponse() }
                    .collectList()
            }
            .map { GameBuildListResponse(it) }

    @Transactional
    fun update(
        userId: Long,
        projectId: Long,
        buildId: Long,
        request: UpdateGameBuildRequest
    ): Mono<GameBuildResponse> =
        buildRepository.findAccessibleById(buildId, projectId, userId)
            .flatMap { build -> buildRepository.save(build.applying(request)) }
            .map { it.toResponse() }

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
