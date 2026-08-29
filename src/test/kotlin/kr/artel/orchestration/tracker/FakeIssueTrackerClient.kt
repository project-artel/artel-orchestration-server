package kr.artel.orchestration.tracker

import kotlinx.coroutines.CompletableDeferred
import kr.artel.orchestration.tracker.client.IssueTrackerClient
import kr.artel.orchestration.tracker.client.IssueTrackerClientRegistry
import kr.artel.orchestration.tracker.client.TrackerIssueDraft
import kr.artel.orchestration.tracker.client.TrackerIssueRef
import kr.artel.orchestration.tracker.client.TrackerNotInstalledException
import kr.artel.orchestration.tracker.client.TrackerRepositoryUnavailableException
import kr.artel.orchestration.tracker.client.TrackerTarget
import kr.artel.orchestration.tracker.entity.TrackerProvider
import java.util.concurrent.atomic.AtomicInteger

/** 대역이 만든 이슈 한 건. 무엇이 어느 저장소로 나갔는지 확인할 때 읽는다. */
data class CreatedIssue(val target: TrackerTarget, val draft: TrackerIssueDraft, val externalKey: String)

/**
 * 실제 GitHub 대신 쓰는 [IssueTrackerClient].
 *
 * [enterGate] 가 이 대역의 요점이다. `createIssue` 는 `claim` **뒤에** 불리므로 게이트 안으로 들어오는
 * 코루틴은 언제나 하나뿐이다 — 승자다. 승자를 그 안에 붙잡아 두면 행이 방금 만들어진 `PENDING` 인
 * 상태가 유지되고, 그때 두 번째 요청이 `sync_state='PENDING' AND updated_at >= staleBefore` 분기를
 * **실제로** 지난다. 게이트가 없으면 승자가 `markSynced` 까지 끝낸 뒤 패배자가 돌아 `SYNCED` 를
 * 만나므로, 검증하려던 동시성 분기를 한 번도 밟지 않는다.
 */
class FakeIssueTrackerClient : IssueTrackerClient {

    override val provider = TrackerProvider.GITHUB

    val created = mutableListOf<CreatedIssue>()
    val closed = mutableListOf<String>()
    val reopened = mutableListOf<String>()

    /** 다음 `createIssue` 를 실패시킨다. 실패 기록 경로를 확인할 때 켠다. */
    @Volatile var failNextCreate: Boolean = false

    /** `verifyRepositoryAccess` 를 실패시킨다. 저장 전 확인이 사는지 볼 때 켠다. */
    @Volatile var failVerify: Boolean = false

    /** 켜면 `createIssue` 가 [entered] 를 완료하고 [release] 를 기다린다. */
    @Volatile var gated: Boolean = false
    val entered = CompletableDeferred<Unit>()
    val release = CompletableDeferred<Unit>()

    private val nextNumber = AtomicInteger(100)

    /** 진짜 구현체가 `webBaseUrl` 로 만드는 자리. 대역은 자기 host 를 써서 둘을 구분할 수 있게 한다. */
    override fun webUrlOf(target: TrackerTarget) =
        "https://github.test/${target.workspace}/${target.repository}"

    override suspend fun verifyRepositoryAccess(target: TrackerTarget) {
        // 진짜 GitHub 구현체와 같은 전제를 건다. `installation` 이 필요한지는 provider 가 아는 것이라
        // 호출부가 아니라 여기서 판정하며, 대역이 그것을 흉내내지 않으면 그 규칙이 테스트에서 사라진다.
        requireInstallation(target)
        if (failVerify) throw TrackerRepositoryUnavailableException("저장소에 접근할 수 없습니다(대역).")
    }

    private fun requireInstallation(target: TrackerTarget) {
        if (target.installationRef.isNullOrBlank()) {
            throw TrackerNotInstalledException("설치가 없습니다(대역).")
        }
    }

    override suspend fun createIssue(target: TrackerTarget, draft: TrackerIssueDraft): TrackerIssueRef {
        requireInstallation(target)
        if (gated) {
            entered.complete(Unit)
            release.await()
        }
        if (failNextCreate) {
            failNextCreate = false
            throw IllegalStateException("GitHub이 응답하지 않습니다(대역).")
        }
        val number = nextNumber.getAndIncrement().toString()
        synchronized(created) { created += CreatedIssue(target, draft, number) }
        return TrackerIssueRef(externalKey = number, url = "https://github.test/issues/$number")
    }

    override suspend fun closeIssue(target: TrackerTarget, externalKey: String) {
        synchronized(closed) { closed += externalKey }
    }

    override suspend fun reopenIssue(target: TrackerTarget, externalKey: String) {
        synchronized(reopened) { reopened += externalKey }
    }

    fun reset() {
        synchronized(created) { created.clear() }
        synchronized(closed) { closed.clear() }
        synchronized(reopened) { reopened.clear() }
        failNextCreate = false
        failVerify = false
        gated = false
    }
}

/**
 * 대역을 물리는 방법.
 *
 * `@Primary` 를 [IssueTrackerClient] 에 붙이는 것으로는 부족하다 — registry 는 `List<IssueTrackerClient>`
 * 를 주입받으므로 진짜 GitHub 구현체가 목록에 그대로 남는다. 그래서 **registry 자체**를 갈아 끼운다.
 */
fun registryOf(fake: FakeIssueTrackerClient) = IssueTrackerClientRegistry(listOf(fake))
