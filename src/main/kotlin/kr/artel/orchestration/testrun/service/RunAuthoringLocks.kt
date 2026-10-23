package kr.artel.orchestration.testrun.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.LockedException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.testrun.dto.RunAuthoringLockResponse
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.AuthoringStage
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Artel Bot 이 런의 시나리오를 고치는 동안의 잠금(ARTEL-1264).
 *
 * TS 본문을 쓰는 길(편집 화면 자동저장, 맵, AI 반영, 승인)은 모두 본문을 통째로 덮고 버전을 보지 않는다. 그래서 AI 가
 * 생각하는 동안 누가 같은 TS 를 고치면, AI 결과가 그것을 말없이 덮는다. 사람끼리는 막지 않고, **AI 턴이 도는 동안만**
 * 그 런을 잠근다. 요청한 사람 본인의 손 편집도 막는다 — 결과가 끝에 통째로 덮어 버리기 때문이다.
 *
 * 턴은 저작 세션(`userId:runId`)마다 하나이고, 세션은 이 서버의 메모리에 산다([TestScenarioAgentService]). 잠금도
 * 같은 자리에 둔다. 서버가 다시 뜨면 세션과 함께 사라진다.
 */
@Component
class RunAuthoringLocks(
    private val runScenarioRepository: TestRunScenarioRepository,
    private val appUserRepository: AppUserRepository,
    private val projectChanges: ProjectChangeStreamManager,
) {
    data class Lock(
        val runId: Long,
        val projectId: Long,
        val appUserId: Long,
        val sessionKey: String,
        val startedAt: Instant,
        val stage: AuthoringStage?,
    )

    private val locks = ConcurrentHashMap<Long, Lock>()

    /** 턴이 시작했다. 같은 세션의 재작성 턴이면 시작 시각을 그대로 둔다. */
    fun hold(runId: Long, projectId: Long, appUserId: Long, sessionKey: String) {
        val previous = locks[runId]
        val startedAt = if (previous?.sessionKey == sessionKey) previous.startedAt else Instant.now()
        locks[runId] = Lock(runId, projectId, appUserId, sessionKey, startedAt, previous?.stage.takeIf { previous?.sessionKey == sessionKey })
        if (previous?.sessionKey != sessionKey) projectChanges.notify(projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 진행 단계가 바뀌었다. 잠금 화면이 "생각 중 · 쓰는 중" 을 보인다. */
    fun stage(sessionKey: String, stage: AuthoringStage) {
        val lock = locks.values.firstOrNull { it.sessionKey == sessionKey } ?: return
        if (lock.stage == stage) return
        locks[lock.runId] = lock.copy(stage = stage)
        projectChanges.notify(lock.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 턴이 끝났다(결과를 다 반영했거나, 질문 · 거절 · 취소 · 오류 · 시한). */
    fun release(sessionKey: String) {
        val lock = locks.values.firstOrNull { it.sessionKey == sessionKey } ?: return
        if (locks.remove(lock.runId, lock)) projectChanges.notify(lock.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    fun lockOf(runId: Long): Lock? = locks[runId]

    /** 이 런을 쓸 수 있나. 잠겨 있으면 423. 같은 세션의 AI 자신이 쓰는 길은 이 검사를 거치지 않는다. */
    suspend fun assertWritable(runId: Long) {
        val lock = locks[runId] ?: return
        throw LockedException(lockedMessage(lock), RUN_LOCKED)
    }

    /** 이 시나리오가 든 런 가운데 하나라도 잠겨 있으면 423. */
    suspend fun assertScenarioWritable(testScenarioId: Long) {
        if (locks.isEmpty()) return
        val runIds = runScenarioRepository.findByTestScenarioId(testScenarioId).toList().map { it.testRunId }
        runIds.firstNotNullOfOrNull { locks[it] }?.let { throw LockedException(lockedMessage(it), RUN_LOCKED) }
    }

    suspend fun describe(runId: Long, viewerId: Long): RunAuthoringLockResponse {
        val lock = locks[runId] ?: return RunAuthoringLockResponse(locked = false)
        return RunAuthoringLockResponse(
            locked = true,
            requestedBy = lock.appUserId.toString(),
            requestedByName = nameOf(lock.appUserId),
            mine = lock.appUserId == viewerId,
            startedAt = lock.startedAt,
            stage = lock.stage,
        )
    }

    private suspend fun lockedMessage(lock: Lock): String = "${nameOf(lock.appUserId)} 님의 Artel Bot 이 시나리오를 고치는 중입니다. 끝나면 다시 해 주세요."

    private suspend fun nameOf(appUserId: Long): String {
        val user = appUserRepository.findById(appUserId) ?: return "누군가"
        return user.nickname.takeIf { it.isNotBlank() } ?: user.displayName
    }

    companion object {
        const val RUN_LOCKED = "run_locked"
    }
}
