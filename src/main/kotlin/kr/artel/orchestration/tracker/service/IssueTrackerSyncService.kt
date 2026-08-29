package kr.artel.orchestration.tracker.service

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kr.artel.orchestration.common.error.ApiException
import kr.artel.orchestration.common.error.BadRequestException
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.issue.entity.IssueEntity
import kr.artel.orchestration.issue.entity.IssueSeverity
import kr.artel.orchestration.issue.repository.IssueRepository
import kr.artel.orchestration.qa.repository.QaTryRepository
import kr.artel.orchestration.tracker.client.IssueTrackerClientRegistry
import kr.artel.orchestration.tracker.client.TrackerTarget
import kr.artel.orchestration.tracker.config.TrackerProperties
import kr.artel.orchestration.tracker.dto.IssueTrackerResponse
import kr.artel.orchestration.tracker.entity.IssueTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.ProjectTrackerLinkEntity
import kr.artel.orchestration.tracker.entity.TrackerProvider
import kr.artel.orchestration.tracker.entity.TrackerSyncState
import kr.artel.orchestration.tracker.repository.IssueTrackerLinkRepository
import kr.artel.orchestration.tracker.repository.ProjectTrackerLinkRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.time.Instant

/** `sync_error` 에 남길 수 있는 길이 상한. 화면 한 줄로 읽히는 것이 목적이라 길게 둘 이유가 없다. */
private const val MAX_SYNC_ERROR_LENGTH = 500

/** 연결이 없을 때의 수동 요청. 조용한 202 대신 무엇이 없는지 말한다. */
class TrackerNotConnectedException(message: String) :
    BadRequestException(message, code = "tracker_not_connected")

/**
 * 결함을 외부 tracker 로 내보낸다. **`provider` 를 모른다.**
 *
 * 아는 것은 셋뿐이다 — severity 기준에 드는가, 이 행을 `claim` 할 수 있는가, 실패를 어떻게 적는가.
 * 어느 tracker 의 말로 옮길지는 [IssueTrackerClientRegistry] 가 고른 구현체가 안다.
 *
 * **이 서비스는 QA 런을 막지도 실패시키지도 않는다.** 자동 경로는 [launchAutoSync] 로 별도 scope 에
 * 던져지고, `IssueService.recordAgentIssue` 는 그 결과를 기다리지 않는다.
 */
@Service
class IssueTrackerSyncService(
    private val issueRepository: IssueRepository,
    private val qaTryRepository: QaTryRepository,
    private val projectLinkRepository: ProjectTrackerLinkRepository,
    private val issueLinkRepository: IssueTrackerLinkRepository,
    private val clients: IssueTrackerClientRegistry,
    private val bodyWriter: TrackerIssueBodyWriter,
    private val properties: TrackerProperties,
    private val scope: CoroutineScope,
    private val clock: Clock
) {
    private val logger = LoggerFactory.getLogger(IssueTrackerSyncService::class.java)

    /**
     * 자동 `sync` 를 별도 scope 에 던진다. 실패는 전부 로그로만 남는다.
     *
     * `Job` 을 돌려주는 이유는 호출부가 기다리기 위해서가 아니라 **테스트가 기다릴 수 있어야 하기
     * 때문**이다. `IssueService` 는 이 값을 버린다.
     */
    fun launchAutoSync(issueId: Long): Job = launch("auto-sync", issueId) { syncAutomatically(issueId) }

    private fun launch(what: String, issueId: Long, block: suspend () -> Unit): Job = scope.launch {
        try {
            block()
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            // 여기서 던지면 scope 의 SupervisorJob 이 삼키고 흔적이 남지 않는다.
            logger.warn("이슈 {}의 tracker {} 실패", issueId, what, error)
        }
    }

    /** severity 가 프로젝트의 자동 기준에 들 때만 내보낸다. */
    suspend fun syncAutomatically(issueId: Long) {
        val context = context(issueId) ?: return
        val ladder = context.projectLink.autoSyncSeverityLadder
        val severity = IssueSeverity.entries.firstOrNull { it.name == context.issue.severity }
        if (severity == null || severity !in ladder) return
        export(context)
    }

    /**
     * 사람이 누르는 내보내기와 재시도.
     *
     * 계약이 `202 {"tracker": IssueTracker}` 이고 `tracker` 는 nullable 이 아니라, 어떤 갈래로 끝나든
     * 상태를 실어 보낸다 — 외부 호출 실패까지 포함해서다. `FAILED` + `syncError` 를 그 자리에 보여
     * 주는 것이 이 모양이 존재하는 목적이다.
     */
    suspend fun syncManually(issueId: Long, userId: Long): IssueTrackerResponse {
        val issue = issueRepository.findById(issueId) ?: throw NotFoundException()
        // 이슈에는 프로젝트가 없다. 실행 접근이 곧 프로젝트 참여이므로 판정은 이 한 줄로 끝난다.
        qaTryRepository.findAccessibleById(issue.qaTryId, userId) ?: throw NotFoundException()

        val context = context(issue)
            ?: throw TrackerNotConnectedException(
                "이 프로젝트에는 내보낼 이슈 트래커가 연결되어 있지 않습니다."
            )
        export(context)
        // `claim` 에 실패했든 성공했든, 지금 저장된 상태가 곧 답이다. 실패했다면 다른 요청이 만든
        // 상태이고, 그것이 사람이 알아야 할 현재 상태다 — 여기서 멱등이 성립한다.
        // export 가 claim 했든 못 했든 이 시점에는 행이 반드시 있다. 없다면 우리 쪽 결함이므로
        // 4xx 로 위장하지 않는다 — 사용자가 고칠 수 있는 것이 아니다.
        return checkNotNull(
            issueLinkRepository.findByIssueIdAndProvider(issueId, context.provider.name)
        ) { "issue_tracker_link이 claim 이후에도 없습니다: issue=$issueId" }.toResponse()
    }

    /**
     * 사람이 결함을 해결로 표시했을 때. 연결된 외부 이슈가 없으면 아무 일도 하지 않는다.
     *
     * 자동 경로와 **같은 이유로** 별도 scope 에 던진다. 실패가 응답을 바꾸지 않는 것만으로는 부족하다 —
     * GitHub 이 느리면 `POST /resolve` 가 요청 timeout(15초)만큼 매달려, 사람은 버튼이 먹통이라고 본다.
     */
    fun launchResolved(issueId: Long): Job = launch("resolve", issueId) { reflectState(issueId, closed = true) }

    /** 해결 표시를 되돌렸을 때. */
    fun launchReopened(issueId: Long): Job = launch("reopen", issueId) { reflectState(issueId, closed = false) }

    /** 목록 한 페이지의 `tracker` 필드. 줄마다 조회하지 않도록 배치로 읽는다. */
    suspend fun trackersOf(issueIds: Collection<Long>): Map<Long, IssueTrackerResponse> {
        if (issueIds.isEmpty()) return emptyMap()
        val rows = mutableMapOf<Long, IssueTrackerResponse>()
        issueLinkRepository.findByIssueIdIn(issueIds).collect { rows[it.issueId] = it.toResponse() }
        return rows
    }

    // --- 내부 ---

    /** 내보내기에 필요한 것을 한 번에 모은다. 하나라도 없으면 내보낼 곳이 없는 것이다. */
    private data class SyncContext(
        val issue: IssueEntity,
        val projectId: Long,
        val projectLink: ProjectTrackerLinkEntity,
        val provider: TrackerProvider
    ) {
        val target: TrackerTarget
            get() = TrackerTarget(
                workspace = requireNotNull(projectLink.externalWorkspace),
                repository = requireNotNull(projectLink.externalRepository),
                installationRef = projectLink.installationRef
            )
    }

    private suspend fun context(issueId: Long): SyncContext? =
        context(issueRepository.findById(issueId) ?: return null)

    private suspend fun context(issue: IssueEntity): SyncContext? {
        val issueId = requireNotNull(issue.id)
        val projectId = issueRepository.findProjectIdByIssueId(issueId) ?: return null
        // provider 가 값이므로 프로젝트에 붙은 `link` 를 찾아 그것이 말하는 provider 를 쓴다.
        // 지금은 프로젝트당 하나지만, 늘어나면 이 조회가 목록이 된다.
        val link = TrackerProvider.entries
            .firstNotNullOfOrNull { projectLinkRepository.findByProjectIdAndProvider(projectId, it.name) }
            ?: return null
        if (!link.hasTarget) return null
        val provider = TrackerProvider.parse(link.provider) ?: return null
        return SyncContext(issue, projectId, link, provider)
    }

    /**
     * `claim` 하고, 외부에 만들고, 결과를 적는다.
     *
     * `claim` 이 실패하면 **조용히 끝낸다** — 다른 요청이 이미 가져갔거나 이미 `SYNCED` 다.
     */
    private suspend fun export(context: SyncContext) {
        val issueId = requireNotNull(context.issue.id)
        val now = Instant.now(clock)
        val existing = issueLinkRepository.findByIssueIdAndProvider(issueId, context.provider.name)
        val linkId = issueLinkRepository.claim(
            issueId = issueId,
            provider = context.provider.name,
            now = now,
            staleBefore = now.minus(properties.claimStaleAfter)
        ) ?: return

        if (existing?.syncState == TrackerSyncState.PENDING.name) {
            // 굳은 `PENDING` 을 되살린 자리. 외부 호출은 성공했는데 markSynced 전에 프로세스가 죽은
            // 경우라면 여기서 두 번째 이슈가 만들어진다 — 드물지만 가능하므로 흔적을 남긴다.
            logger.warn(
                "굳은 PENDING을 다시 claim했습니다: issue={}, provider={}. 외부 이슈가 중복될 수 있습니다.",
                issueId,
                context.provider
            )
        }

        try {
            val created = clients.of(context.provider).createIssue(
                context.target,
                bodyWriter.write(context.issue, context.projectId)
            )
            issueLinkRepository.markSynced(
                id = linkId,
                externalKey = created.externalKey,
                externalUrl = created.url,
                now = Instant.now(clock)
            )
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            issueLinkRepository.markFailed(linkId, summarize(error), Instant.now(clock))
            logger.warn("이슈 {}를 {}로 내보내지 못했습니다", issueId, context.provider, error)
        }
    }

    /**
     * 외부 이슈의 열림/닫힘을 맞춘다.
     *
     * 실패해도 던지지 않는다 — 사람이 누른 상태 전이가 GitHub 때문에 실패하면 안 된다. `sync_state`
     * 도 건드리지 않는다: 이슈는 이미 저쪽에 존재하므로 `FAILED` 로 되돌리면 재시도가 이슈를 하나 더
     * 만든다.
     */
    private suspend fun reflectState(issueId: Long, closed: Boolean) {
        val context = context(issueId) ?: return
        val link = issueLinkRepository.findByIssueIdAndProvider(issueId, context.provider.name) ?: return
        if (link.syncState != TrackerSyncState.SYNCED.name) return
        val externalKey = link.externalKey ?: return
        try {
            val client = clients.of(context.provider)
            if (closed) client.closeIssue(context.target, externalKey)
            else client.reopenIssue(context.target, externalKey)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Exception) {
            logger.warn(
                "이슈 {}의 외부 상태를 {}로 맞추지 못했습니다",
                issueId,
                if (closed) "closed" else "open",
                error
            )
        }
    }

    /**
     * 화면으로 나갈 실패 요약.
     *
     * **5xx 의 message 는 절대 싣지 않는다.** 이 값은 `IssueResponse.tracker.syncError` 로 프로젝트
     * 참여자 전원에게 그대로 보이므로, 여기에 5xx 를 실으면 `error-handling.md` 가 막아 둔 경로가
     * 되살아난다 — 실제로 새어 나갈 뻔한 것이 installation id 와 upstream status 다.
     *
     * 4xx 는 우리가 쓴 도메인 안내라 그대로 내보낸다("App이 그 저장소에 설치되어 있는지 확인해
     * 주세요"). 그것이 사람이 고칠 수 있는 유일한 단서다. 5xx 의 원인 전체는 로그로만 간다.
     */
    private fun summarize(error: Throwable): String {
        val text = when {
            error is ApiException && error.status.is4xxClientError -> error.message ?: error.code
            else -> "이슈 트래커로 내보내지 못했습니다. 잠시 후 다시 시도해 주세요."
        }
        return text.take(MAX_SYNC_ERROR_LENGTH)
    }

    private fun IssueTrackerLinkEntity.toResponse() = IssueTrackerResponse(
        provider = provider,
        externalKey = externalKey,
        url = externalUrl,
        syncState = syncState,
        syncError = syncError,
        syncedAt = syncedAt
    )
}
