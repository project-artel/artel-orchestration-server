package kr.artel.orchestration.game.service

import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.game.dto.GameBuildListResponse
import kr.artel.orchestration.game.dto.GameBuildResponse
import kr.artel.orchestration.game.dto.UpdateGameBuildRequest
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.dto.GameBuildSummaryListResponse
import kr.artel.orchestration.game.dto.GameBuildSummaryResponse
import kr.artel.orchestration.game.dto.LatestQaRunSummary
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameBuildSummaryRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
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
    private val summaryRepository: GameBuildSummaryRepository,
    private val sessionManager: SessionManager,
    private val projectRepository: ProjectRepository,
    private val clock: Clock,
    private val projectChangeStreamManager: ProjectChangeStreamManager
) {
    suspend fun list(userId: Long, projectId: Long): GameBuildListResponse? {
        projectRepository.findAccessibleById(projectId, userId) ?: return null
        val items = buildRepository.findAllForMember(projectId, userId)
            .map { it.toResponse() }
            .toList()
        return GameBuildListResponse(items)
    }

    /** 빌드마다 인스턴스 · function 목록 · QA run 요약을 붙인 목록. 순서는 [list] 와 같다. */
    suspend fun summaries(userId: Long, projectId: Long): GameBuildSummaryListResponse? {
        projectRepository.findAccessibleById(projectId, userId) ?: return null
        val items = summaryRepository.findByProject(projectId).map { row ->
            GameBuildSummaryResponse(
                gameBuildId = row.id.toString(),
                version = row.version,
                label = row.label,
                isEditor = row.isEditor,
                createdAt = row.createdAt,
                lastReportedAt = row.lastReportedAt,
                instanceCount = row.instanceIds.size,
                connectedCount = row.instanceIds.count { sessionManager.hasSession(it.toString()) },
                cheatCount = row.cheatCount,
                behaviorCount = row.behaviorCount,
                catalogReceivedAt = row.catalogReceivedAt,
                qaRunCount = row.qaRunCount,
                latestQaRun = row.latestRunId?.let {
                    LatestQaRunSummary(
                        qaRunId = it.toString(),
                        status = requireNotNull(row.latestRunStatus),
                        startedAt = requireNotNull(row.latestRunStartedAt),
                        completedAt = row.latestRunCompletedAt,
                        stepsPassed = row.stepsPassed,
                        stepsTotal = row.stepsTotal
                    )
                }
            )
        }
        return GameBuildSummaryListResponse(items)
    }

    suspend fun update(
        userId: Long,
        projectId: Long,
        buildId: Long,
        request: UpdateGameBuildRequest
    ): GameBuildResponse? {
        val build = buildRepository.findAccessibleById(buildId, projectId, userId) ?: return null
        val saved = buildRepository.save(build.applying(request))
        // save 가 곧 commit 이다. 인스턴스 응답에도 빌드 label 이 실려 있어 둘 다 알린다.
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.GAME_BUILDS)
        projectChangeStreamManager.notify(projectId, ProjectChangeTopic.GAME_INSTANCES)
        return saved.toResponse()
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
        isEditor = isEditor,
        createdAt = createdAt,
        updatedAt = updatedAt
    )
}
