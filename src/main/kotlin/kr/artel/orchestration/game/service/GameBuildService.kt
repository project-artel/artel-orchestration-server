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
import kr.artel.orchestration.testcase.service.ManualCaseVersionService
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 게임 빌드는 SDK가 버전을 보고할 때 생긴다([SdkRegistrationService]). 게임을 실행하기 전에 선언부터 하려는 개발자 에이전트는
 * 버전만으로 빌드를 만들 수 있다([declare], ARTEL-1447). 두 길은 같은 [GameBuildRegistrar] 로 찾으므로, 먼저 선언한 빌드에
 * 나중에 같은 버전의 게임이 붙는다. 여기서는 그 밖에 읽기와 설명 수정을 한다.
 *
 * 삭제는 없다. 다음 등록에서 같은 행이 다시 생기므로, 지울 수 있다는 인상만 주고 실제로는 지워지지 않는 동작이 된다.
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
    private val projectChangeStreamManager: ProjectChangeStreamManager,
    private val buildRegistrar: GameBuildRegistrar,
    private val manualCaseVersions: ManualCaseVersionService,
) {
    private val logger = LoggerFactory.getLogger(GameBuildService::class.java)

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

    /**
     * [version] 의 development build 를 찾고, 없으면 만든다(ARTEL-1447). 같은 버전의 editor 행은 쓰지 않는다 — SDK 의
     * development build 등록과 같은 행을 가리켜야 하기 때문이다.
     *
     * 새로 만들었으면 SDK 등록과 똑같이 앞 버전의 사용자 생성 TC 를 이어 넣는다. 실패해도 빌드는 그대로다.
     * 접근할 수 없는 프로젝트면 null 이다.
     */
    suspend fun declare(userId: Long, projectId: Long, version: String): DeclaredGameBuild? {
        projectRepository.findAccessibleById(projectId, userId) ?: return null
        val (build, created) = buildRegistrar.findOrCreate(projectId, version.trim(), isEditor = false, sceneScan = null, now = Instant.now(clock))
        if (created) {
            val buildId = requireNotNull(build.id)
            runCatching { manualCaseVersions.carryOverOnNewBuild(projectId, buildId) }
                .onFailure { logger.warn("선언한 새 버전에 사용자 TC 이어 넣기 실패 [gameBuildId=$buildId]: ${it.message}") }
            projectChangeStreamManager.notify(projectId, ProjectChangeTopic.GAME_BUILDS)
        }
        return DeclaredGameBuild(build.toResponse(), created)
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

/** [GameBuildService.declare] 의 결과. [created] 면 이번 요청이 만들었다. */
data class DeclaredGameBuild(val build: GameBuildResponse, val created: Boolean)
