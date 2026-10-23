package kr.artel.orchestration.testrun.service

import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.LockedException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.testrun.dto.RunAuthoringLockResponse
import kr.artel.orchestration.testrun.dto.ScenarioAuthoringLock
import kr.artel.orchestration.testscenario.dto.AuthoringStage
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/**
 * Artel Bot 이 고치는 시나리오의 잠금(ARTEL-1264).
 *
 * TS 본문을 쓰는 길(편집 화면 자동저장, 맵, AI 반영, 승인)은 모두 본문을 통째로 덮고 버전을 보지 않는다. 그래서 AI 가
 * 고치는 시나리오를 그동안 누가 고치면 한쪽이 말없이 사라진다.
 *
 * **런 전체가 아니라 AI 가 고친 시나리오만** 잠근다. AI 가 무엇을 고칠지는 턴을 시작할 때 알 수 없고, 시나리오를
 * 하나씩 내보낼 때(`submit_scenario`) 비로소 안다. 그래서 내보낸 순간부터 턴이 끝날 때까지 그 시나리오를 잠근다.
 * 그 전, AI 가 생각하는 동안 사람이 고친 시나리오는 잠금이 아니라 저장 직전 비교로 지킨다 — AI 가 본 것과 지금이
 * 다르면 덮지 않고 draft 로 돌린다([TestScenarioAgentService]).
 *
 * 턴은 저작 세션(`userId:runId`)마다 하나이고 세션은 이 서버의 메모리에 산다. 잠금도 같은 자리에 둔다.
 */
@Component
class RunAuthoringLocks(
    private val appUserRepository: AppUserRepository,
    private val projectChanges: ProjectChangeStreamManager,
) {
    data class Turn(
        val runId: Long,
        val projectId: Long,
        val appUserId: Long,
        val sessionKey: String,
        val startedAt: Instant,
        val stage: AuthoringStage?,
        /** 이 턴의 AI 가 고쳐서 잠긴 시나리오. */
        val scenarioIds: Set<Long>,
    )

    private val turns = ConcurrentHashMap<String, Turn>()

    /** 턴이 시작했다. 같은 세션의 재작성 턴이면 시작 시각과 이미 잠근 시나리오를 이어 쥔다. */
    fun hold(runId: Long, projectId: Long, appUserId: Long, sessionKey: String) {
        turns.compute(sessionKey) { _, previous ->
            previous?.copy(runId = runId) ?: Turn(runId, projectId, appUserId, sessionKey, Instant.now(), null, emptySet())
        }
    }

    /** AI 가 이 시나리오들을 고쳤다. 턴이 끝날 때까지 사람은 쓰지 못한다. */
    fun lockScenarios(sessionKey: String, scenarioIds: Collection<Long>) {
        val ids = scenarioIds.filter { it > 0 }.toSet()
        if (ids.isEmpty()) return
        val turn = turns.computeIfPresent(sessionKey) { _, t -> t.copy(scenarioIds = t.scenarioIds + ids) } ?: return
        projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 진행 단계가 바뀌었다. 잠긴 시나리오가 있으면 잠금 카드가 "고치는 중 · 검토 중" 을 바꿔 보인다. */
    fun stage(sessionKey: String, stage: AuthoringStage) {
        val turn = turns.computeIfPresent(sessionKey) { _, t -> if (t.stage == stage) t else t.copy(stage = stage) } ?: return
        if (turn.scenarioIds.isNotEmpty()) projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 턴이 끝났다(결과를 다 반영했거나, 질문 · 거절 · 취소 · 오류 · 시한 · 세션 종료). */
    fun release(sessionKey: String) {
        val turn = turns.remove(sessionKey) ?: return
        if (turn.scenarioIds.isNotEmpty()) projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 이 시나리오를 잠근 턴. 없으면 null. */
    fun holderOf(scenarioId: Long): Turn? = turns.values.firstOrNull { scenarioId in it.scenarioIds }

    /** 사람이 이 시나리오를 쓸 수 있나. AI 가 고치는 중이면 423 — 요청한 본인도 막는다(결과가 끝에 덮는다). */
    suspend fun assertScenarioWritable(testScenarioId: Long) {
        val turn = holderOf(testScenarioId) ?: return
        throw LockedException(lockedMessage(turn), RUN_LOCKED)
    }

    /** [sessionKey] 의 AI 가 이 시나리오를 쓸 수 있나. 다른 세션의 AI 가 고치는 중이면 423. */
    suspend fun assertWritableBy(testScenarioId: Long, sessionKey: String) {
        val turn = holderOf(testScenarioId) ?: return
        if (turn.sessionKey != sessionKey) throw LockedException(lockedMessage(turn), RUN_LOCKED)
    }

    /** 다른 세션의 AI 가 쥐고 있나. */
    fun heldByOther(testScenarioId: Long, sessionKey: String): Boolean = holderOf(testScenarioId)?.let { it.sessionKey != sessionKey } ?: false

    suspend fun describe(runId: Long, viewerId: Long): RunAuthoringLockResponse {
        val locks = turns.values.filter { it.runId == runId && it.scenarioIds.isNotEmpty() }.flatMap { turn ->
            val name = nameOf(turn.appUserId)
            turn.scenarioIds.map { id ->
                ScenarioAuthoringLock(
                    scenarioId = id.toString(),
                    requestedBy = turn.appUserId.toString(),
                    requestedByName = name,
                    mine = turn.appUserId == viewerId,
                    startedAt = turn.startedAt,
                    stage = turn.stage,
                )
            }
        }
        return RunAuthoringLockResponse(scenarios = locks)
    }

    private suspend fun lockedMessage(turn: Turn): String =
        "${nameOf(turn.appUserId)} 님의 Artel Bot 이 이 시나리오를 고치는 중입니다. 끝나면 다시 해 주세요."

    private suspend fun nameOf(appUserId: Long): String {
        val user = appUserRepository.findById(appUserId) ?: return "누군가"
        return user.nickname.takeIf { it.isNotBlank() } ?: user.displayName
    }

    companion object {
        const val RUN_LOCKED = "scenario_locked"
    }
}
