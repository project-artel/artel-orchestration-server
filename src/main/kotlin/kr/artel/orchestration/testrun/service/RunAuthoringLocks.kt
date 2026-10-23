package kr.artel.orchestration.testrun.service

import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.common.error.LockedException
import kr.artel.orchestration.project.event.ProjectChangeStreamManager
import kr.artel.orchestration.project.event.ProjectChangeTopic
import kr.artel.orchestration.testrun.dto.RunAuthoringLockResponse
import kr.artel.orchestration.testrun.dto.ScenarioAuthoringLock
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
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
 * **요청을 보낸 순간부터 잠근다.** AI 가 무엇을 고칠지는 그 순간 알 수 없으므로, 처음에는 런의 TS 전부를 "고칠 것을
 * 고르는 중" 으로 잠근다([Turn.choosing]). 그리고 좁힌다.
 * - 새 TS 를 쓰는 흐름(묶기 · 쓰기 단계)이면 있는 TS 는 고치지 않으므로 바로 다 푼다.
 * - AI 가 시나리오를 내보내면(`submit_scenario`) 그것만 남기고 나머지를 푼다.
 * - 턴이 끝나면 다 푼다.
 * 고르기와 쓰기가 한 모델 호출인 수정 흐름은 그 호출 동안 런 전체가 잠긴다. 그래도 덮는 일은 저장 직전 비교가 한 번 더
 * 막는다([TestScenarioAgentService]).
 *
 * 턴은 저작 세션(`userId:runId`)마다 하나이고 세션은 이 서버의 메모리에 산다. 잠금도 같은 자리에 둔다.
 */
@Component
class RunAuthoringLocks(
    private val appUserRepository: AppUserRepository,
    private val projectChanges: ProjectChangeStreamManager,
    private val runScenarioRepository: TestRunScenarioRepository,
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
        /** 아직 고칠 것을 고르는 중이라 런의 TS 전부를 잠근다. */
        val choosing: Boolean,
    )

    private val turns = ConcurrentHashMap<String, Turn>()

    /**
     * 턴이 시작했다. 런의 TS 전부를 "고르는 중" 으로 잠근다. 같은 세션의 재작성 턴이면 시작 시각과 이미 좁힌 잠금을
     * 이어 쥔다 — 재작성은 앞서 낸 것을 고칠 뿐이다. [choosing] 이 false 면(저장하지 않고 draft 로만 받는 턴) 아무것도
     * 잠그지 않는다 — 덮을 일이 없다.
     */
    fun hold(runId: Long, projectId: Long, appUserId: Long, sessionKey: String, choosing: Boolean = true) {
        val turn = turns.compute(sessionKey) { _, previous ->
            previous?.copy(runId = runId) ?: Turn(runId, projectId, appUserId, sessionKey, Instant.now(), null, emptySet(), choosing = choosing)
        }!!
        if (turn.choosing) projectChanges.notify(projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** AI 가 이 시나리오들을 고쳤다. 고르기는 끝났다 — 이것만 남기고 턴이 끝날 때까지 잠근다. */
    fun lockScenarios(sessionKey: String, scenarioIds: Collection<Long>) {
        val ids = scenarioIds.filter { it > 0 }.toSet()
        val turn = turns.computeIfPresent(sessionKey) { _, t -> t.copy(scenarioIds = t.scenarioIds + ids, choosing = false) } ?: return
        projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /**
     * 진행 단계가 바뀌었다. 새 TS 를 쓰는 흐름의 단계면 있는 TS 는 고치지 않으므로 "고르는 중" 잠금을 푼다. 잠금이 남아
     * 있으면 카드가 단계를 바꿔 보인다.
     */
    fun stage(sessionKey: String, stage: AuthoringStage) {
        val before = turns[sessionKey] ?: return
        val writesNew = stage in WRITES_NEW
        val turn = turns.computeIfPresent(sessionKey) { _, t ->
            t.copy(stage = stage, choosing = t.choosing && !writesNew)
        } ?: return
        if (before.stage != stage && (turn.choosing || turn.scenarioIds.isNotEmpty() || before.choosing)) {
            projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
        }
    }

    /** 턴이 끝났다(결과를 다 반영했거나, 질문 · 거절 · 취소 · 오류 · 시한 · 세션 종료). */
    fun release(sessionKey: String) {
        val turn = turns.remove(sessionKey) ?: return
        if (turn.choosing || turn.scenarioIds.isNotEmpty()) projectChanges.notify(turn.projectId, ProjectChangeTopic.RUN_LOCKS)
    }

    /** 이 시나리오를 잠근 턴. 고르는 중인 턴은 그 런의 TS 전부를 쥔다. 없으면 null. */
    suspend fun holderOf(scenarioId: Long): Turn? {
        turns.values.firstOrNull { scenarioId in it.scenarioIds }?.let { return it }
        val choosing = turns.values.filter { it.choosing }
        if (choosing.isEmpty()) return null
        val runIds = runScenarioRepository.findByTestScenarioId(scenarioId).toList().map { it.testRunId }.toSet()
        return choosing.firstOrNull { it.runId in runIds }
    }

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
    suspend fun heldByOther(testScenarioId: Long, sessionKey: String): Boolean = holderOf(testScenarioId)?.let { it.sessionKey != sessionKey } ?: false

    suspend fun describe(runId: Long, viewerId: Long): RunAuthoringLockResponse {
        val inRun by lazy { runScenarioRepository.findByTestRunIdOrderByPosition(runId) }
        val locks = turns.values.filter { it.runId == runId && (it.choosing || it.scenarioIds.isNotEmpty()) }.flatMap { turn ->
            val name = nameOf(turn.appUserId)
            val ids = if (turn.choosing) inRun.toList().map { it.testScenarioId }.toSet() + turn.scenarioIds else turn.scenarioIds
            ids.map { id ->
                ScenarioAuthoringLock(
                    scenarioId = id.toString(),
                    requestedBy = turn.appUserId.toString(),
                    requestedByName = name,
                    mine = turn.appUserId == viewerId,
                    startedAt = turn.startedAt,
                    stage = turn.stage,
                    choosing = turn.choosing && id !in turn.scenarioIds,
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

        /** 새 TS 를 쓰는 흐름의 단계(묶기 · 잇기 · 쓰기). 이 단계가 오면 있는 TS 는 고치지 않는다. */
        private val WRITES_NEW = setOf(AuthoringStage.GROUPING, AuthoringStage.GROUPED, AuthoringStage.BRIDGING, AuthoringStage.WRITING)
    }
}
