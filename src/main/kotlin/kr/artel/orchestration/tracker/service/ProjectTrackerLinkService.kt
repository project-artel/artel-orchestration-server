package kr.artel.orchestration.tracker.service

import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.project.entity.ProjectRole
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.service.ProjectAccessDeniedException
import kr.artel.orchestration.project.service.ProjectAccessService
import kr.artel.orchestration.tracker.client.IssueTrackerClientRegistry
import kr.artel.orchestration.tracker.client.UnsupportedTrackerException
import kr.artel.orchestration.tracker.client.TrackerNotInstalledException
import kr.artel.orchestration.tracker.client.TrackerTarget
import kr.artel.orchestration.tracker.dto.TrackerLinkResponse
import kr.artel.orchestration.tracker.dto.TrackerLinkUpsertRequest
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/**
 * 프로젝트와 외부 tracker 를 잇는 `link` 의 조회 · 설정 · 해제.
 *
 * 인가 규칙은 프로젝트의 다른 되돌릴 수 없는 동작과 같다(`ProjectService.delete` 참고):
 * - 참여자가 아니면 **404** — 남의 프로젝트가 존재한다는 것조차 알려주지 않는다.
 * - 참여자지만 OWNER 가 아니면 읽기는 되고 쓰기는 **403** — 이미 프로젝트를 볼 수 있는 사람에게
 *   404 를 주는 것은 숨기는 시늉일 뿐이다.
 */
@Service
class ProjectTrackerLinkService(
    private val linkRepository: ProjectTrackerLinkRepository,
    private val projectRepository: ProjectRepository,
    private val projectAccess: ProjectAccessService,
    private val clients: IssueTrackerClientRegistry,
    private val clock: Clock
) {
    suspend fun read(projectId: Long, userId: Long, provider: TrackerProvider): TrackerLinkResponse? {
        requireMember(projectId, userId)
        return linkRepository.findByProjectIdAndProvider(projectId, provider.name)?.toResponse()
    }

    /**
     * 저장소와 자동 `sync` 기준을 정한다.
     *
     * 저장하기 전에 [IssueTrackerClient.verifyRepositoryAccess] 로 실제 접근을 확인한다. 확인하지
     * 않으면 첫 결함이 보고되는 순간에야 저장소 오타를 알게 되고, 그 실패는 QA 런 한복판에서 난다.
     */
    suspend fun upsert(
        projectId: Long,
        userId: Long,
        request: TrackerLinkUpsertRequest
    ): TrackerLinkResponse {
        requireOwner(projectId, userId)
        val provider = TrackerProvider.parse(request.provider)
            ?: throw UnsupportedTrackerException(request.provider)
        val workspace = request.workspace.trim()
        val repository = request.repository.trim()
        if (workspace.isEmpty() || repository.isEmpty()) {
            throw BadRequestException("workspace와 repository는 비어 있을 수 없습니다.")
        }
        val severities = parseSeverities(request.autoSyncSeverities)

        val existing = linkRepository.findByProjectIdAndProvider(projectId, provider.name)
        // `installation` 이 필요한지는 provider 가 판정한다. 여기서 null 을 막으면 설치라는 개념이
        // 없는 다음 provider 를 붙일 때 이 메서드를 고쳐야 한다.
        clients.of(provider).verifyRepositoryAccess(
            TrackerTarget(workspace, repository, existing?.installationRef)
        )

        linkRepository.upsertTarget(
            projectId = projectId,
            provider = provider.name,
            workspace = workspace,
            repository = repository,
            autoSyncSeverities = severities,
            connectedBy = userId,
            now = Instant.now(clock)
        )
        return requireNotNull(linkRepository.findByProjectIdAndProvider(projectId, provider.name))
            .toResponse()
    }

    /**
     * `link` 를 지운다. **이미 나간 외부 이슈와 `issue_tracker_link` 는 건드리지 않는다** — 그것은
     * 이 프로젝트의 설정이 아니라 저쪽에서 사람이 처리 중인 일이다.
     */
    suspend fun delete(projectId: Long, userId: Long, provider: TrackerProvider) {
        requireOwner(projectId, userId)
        linkRepository.deleteByProjectIdAndProvider(projectId, provider.name)
    }

    /**
     * 설치 callback 이 부르는 자리. state 검증은 호출부가 이미 했고, 여기서는 그 state 가 가리키는
     * 사람이 지금도 그 프로젝트의 OWNER 인지 한 번 더 본다 — state 를 발급받은 뒤 권한이 빠졌을 수 있다.
     */
    suspend fun attachInstallation(
        projectId: Long,
        userId: Long,
        provider: TrackerProvider,
        installationRef: String
    ) {
        requireOwner(projectId, userId)
        linkRepository.attachInstallation(
            projectId = projectId,
            provider = provider.name,
            installationRef = installationRef,
            connectedBy = userId,
            now = Instant.now(clock)
        )
    }

    /**
     * 설치 주소를 내주기 전의 인가 판정. 설치는 `link` 를 만드는 동작이므로 조회 endpoint 라도
     * 쓰기와 같은 규칙(OWNER)을 쓴다.
     */
    suspend fun requireOwnerForSetup(projectId: Long, userId: Long) = requireOwner(projectId, userId)

    /** `installation` 이 붙어 있어야 부를 수 있는 GitHub 고유 경로가 쓰는 조회. */
    suspend fun requireInstallation(
        projectId: Long,
        userId: Long,
        provider: TrackerProvider
    ): String {
        requireOwner(projectId, userId)
        return linkRepository.findByProjectIdAndProvider(projectId, provider.name)?.installationRef
            ?: throw TrackerNotInstalledException(
                "이 프로젝트에는 GitHub App이 설치되어 있지 않습니다."
            )
    }

    private fun parseSeverities(requested: List<String>?): String {
        if (requested == null) return ProjectTrackerLinkEntity.DEFAULT_AUTO_SYNC_SEVERITIES
        val parsed = requested.map { name ->
            IssueSeverity.entries.firstOrNull { it.name == name.trim().uppercase() }
                ?: throw BadRequestException(
                    "autoSyncSeverities는 ${IssueSeverity.NAMES} 중에서 골라야 합니다."
                )
        }
        // 선언 순서(심각한 것부터)로 정규화해 저장한다. 순서가 요청마다 달라지면 같은 설정이 다른
        // 문자열로 저장되어, 값을 눈으로 비교할 수 없게 된다.
        return IssueSeverity.entries.filter { it in parsed }.joinToString(",") { it.name }
    }

    /** 참여자인지. 아니면 404 — 삭제된 프로젝트도 없는 것으로 본다. */
    private suspend fun requireMember(projectId: Long, userId: Long) {
        projectRepository.findAccessibleById(projectId, userId) ?: throw NotFoundException()
    }

    private suspend fun requireOwner(projectId: Long, userId: Long) {
        requireMember(projectId, userId)
        val member = projectAccess.member(projectId, userId)
        if (member?.role != ProjectRole.OWNER.name) {
            throw ProjectAccessDeniedException("이슈 트래커 연결은 프로젝트 소유자만 바꿀 수 있습니다.")
        }
    }

    private fun ProjectTrackerLinkEntity.toResponse(): TrackerLinkResponse {
        val parsed = TrackerProvider.parse(provider)
        return TrackerLinkResponse(
            provider = provider,
            installed = !installationRef.isNullOrBlank(),
            workspace = externalWorkspace,
            repository = externalRepository,
            // 주소를 여기서 조립하지 않는다 — host 는 provider 의 것이고, GitHub Enterprise 처럼
            // 그것이 github.com 이 아닌 설치가 있다.
            htmlUrl = if (hasTarget && parsed != null) {
                clients.of(parsed).webUrlOf(
                    TrackerTarget(
                        workspace = requireNotNull(externalWorkspace),
                        repository = requireNotNull(externalRepository),
                        installationRef = installationRef
                    )
                )
            } else {
                null
            },
            autoSyncSeverities = autoSyncSeverityLadder.map { it.name },
            updatedAt = updatedAt
        )
    }
}
