package kr.artel.orchestration.testscenario.service

import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.CurrentScenario
import kr.artel.orchestration.testscenario.dto.DraftScenario
import kr.artel.orchestration.testscenario.dto.DraftScenarioEdit
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStreamEvent
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 한 저작 대화(사용자 × 런)의 draft(ARTEL-1074). 자동저장을 끈 턴이 낸 것을 **저장하지 않고** 모아 둔다.
 *
 * 턴을 넘어 산다. 사용자는 draft 를 본 채로 "이건 빼 줘" 라고 이어 말하고, 그 말은 draft 를 고쳐야
 * 한다 — 그래서 다음 턴의 Agent 는 [overlay] 로 draft 가 적용된 모습을 현재 상태로 받는다.
 *
 * 새로 생길 draft 는 아직 번호가 없다. Agent 에게는 **음수 번호**(`new-3` → `-3`)로 보인다.
 * Agent 는 `current_scenarios` 에 있는 번호만 고칠 대상으로 받으므로, 그 번호를 되돌려 주면 같은
 * draft 를 고친 것이다. 음수 번호는 저장 경로로 넘어가면 안 된다 — 받는 쪽([accept])이 푼다.
 *
 * 동시에 고쳐질 수 있다(도구 처리 코루틴과 REST 요청). 모든 연산을 이 객체로 잠근다.
 */
class AuthoringDraft {
    private val entries = LinkedHashMap<String, DraftScenario>()
    private var lastNew = 0L

    @Synchronized
    fun list(): List<DraftScenario> = entries.values.toList()

    @Synchronized
    fun isEmpty(): Boolean = entries.isEmpty()

    /**
     * 검수를 지난 결과를 draft 에 넣는다.
     *
     * @param targetId Agent 가 지목한 번호. 양수면 기존 시나리오 수정, 음수면 draft 의 새 시나리오를
     *   다시 고친 것, null 이면 새것이다.
     * @param checked 검수를 지난 최종본. 나누기가 하나를 여럿으로 만들 수 있다 — 첫 것이 지목한
     *   자리에 들고, 나머지는 새 draft 가 된다.
     * @param absorbed 이 시나리오로 합쳐질 번호. 음수(draft 의 새것)는 지금 draft 에서 뺀다 — 저장된
     *   적이 없으니 런에서 걷어낼 것도 없다.
     * @param savedSteps 기존 시나리오를 고친 것이면 그 시나리오의 지금 스텝. 처음 고칠 때 화면이 견줄
     *   기준([DraftScenario.baseSteps])이 된다. 이미 draft 가 있으면 그 draft 가 기준이다.
     */
    @Synchronized
    fun accept(
        targetId: Long?,
        checked: List<ScenarioResult>,
        absorbed: List<Long>,
        savedSteps: List<ChatScenarioStep>? = null,
    ): List<DraftScenario> {
        if (checked.isEmpty()) return emptyList()
        val firstKey = when {
            targetId != null && targetId > 0 -> editKey(targetId)
            targetId != null && newKey(-targetId) in entries -> newKey(-targetId)
            else -> nextNewKey()
        }
        val kept = absorbed.filter { it > 0 && it != targetId }.distinct()
        absorbed.filter { it < 0 }.map { newKey(-it) }.filter { it != firstKey }.forEach { entries.remove(it) }

        val placed = mutableListOf<DraftScenario>()
        val base = entries[firstKey]?.steps ?: savedSteps?.takeIf { firstKey.startsWith(EDIT) }
        checked.forEachIndexed { index, scenario ->
            val key = if (index == 0) firstKey else nextNewKey()
            val draft = DraftScenario(
                draftId = key,
                scenarioId = if (key.startsWith(EDIT)) targetId else null,
                title = scenario.title,
                description = scenario.description,
                steps = scenario.steps,
                absorbedScenarioIds = if (index == 0) kept else emptyList(),
                baseSteps = if (index == 0) base else null,
            )
            entries[key] = draft
            placed += draft
        }
        return placed
    }

    /** 사용자가 고친 본문으로 바꾼다. 없는 draft 면 null. */
    @Synchronized
    fun update(draftId: String, edit: DraftScenarioEdit): DraftScenario? {
        val current = entries[draftId] ?: return null
        val next = current.copy(title = edit.title, description = edit.description, steps = edit.steps)
        entries[draftId] = next
        return next
    }

    /** 고른 draft 를 꺼낸다(승인 · 버리기). draft 순서를 지킨다. 없는 이름은 건너뛴다. */
    @Synchronized
    fun take(draftIds: Collection<String>): List<DraftScenario> {
        val wanted = draftIds.toSet()
        val taken = entries.values.filter { it.draftId in wanted }
        taken.forEach { entries.remove(it.draftId) }
        return taken
    }

    /** 승인하려다 막힌 것을 되돌려 놓는다. 같은 이름이 새로 생겼으면 그쪽이 더 새것이라 두지 않는다. */
    @Synchronized
    fun restore(draft: DraftScenario) {
        entries.putIfAbsent(draft.draftId, draft)
    }

    /**
     * draft 가 적용된 모습. 고친 것은 본문이 바뀌고, 합쳐질 것은 빠지고, 새것은 음수 번호로 끝에 붙는다.
     */
    @Synchronized
    fun overlay(current: List<CurrentScenario>): List<CurrentScenario> {
        if (entries.isEmpty()) return current
        val absorbed = entries.values.flatMap { it.absorbedScenarioIds }.toSet()
        val edited = current
            .filter { it.scenarioId == null || it.scenarioId !in absorbed }
            .map { scenario ->
                val draft = scenario.scenarioId?.let { entries[editKey(it)] } ?: return@map scenario
                CurrentScenario(scenario.scenarioId, draft.title, draft.description, draft.steps)
            }
        val added = entries.values
            .filter { it.draftId.startsWith(NEW) }
            .map { CurrentScenario(agentIdOf(it.draftId), it.title, it.description, it.steps) }
        return edited + added
    }

    /**
     * draft 만 — 승인 전 draft 만 고치는 턴(ARTEL-1074)이 Agent 에게 보일 것. 고친 것은 원래 번호, 새것은
     * 음수 번호다. 저장된 시나리오는 싣지 않는다.
     */
    @Synchronized
    fun draftsOnly(): List<CurrentScenario> =
        entries.values.map { CurrentScenario(agentIdOf(it.draftId), it.title, it.description, it.steps) }

    /**
     * draft 만 고치는 턴에서 Agent 가 낸 번호를 고칠 draft 의 번호로 푼다. draft 의 번호면 그대로, 번호가
     * 없으면 draft 가 하나일 때만 그 draft 다. 그 밖(저장된 시나리오 · 없는 번호 · 여럿 중 미지정)은 null 이다.
     */
    @Synchronized
    fun draftTarget(submittedId: Long?): Long? {
        val ids = entries.keys.mapNotNull(::agentIdOf)
        return when {
            submittedId != null -> submittedId.takeIf { it in ids }
            ids.size == 1 -> ids.single()
            else -> null
        }
    }

    private fun nextNewKey(): String {
        lastNew += 1
        return newKey(lastNew)
    }

    companion object {
        private const val EDIT = "edit-"
        private const val NEW = "new-"

        private fun editKey(scenarioId: Long) = "$EDIT$scenarioId"
        private fun newKey(n: Long) = "$NEW$n"

        /** Agent 에게 보일 번호. 새 draft 는 음수다. */
        fun agentIdOf(draftId: String): Long? = when {
            draftId.startsWith(NEW) -> draftId.removePrefix(NEW).toLongOrNull()?.let { -it }
            draftId.startsWith(EDIT) -> draftId.removePrefix(EDIT).toLongOrNull()
            else -> null
        }
    }
}

/**
 * 저작 대화별 [AuthoringDraft](ARTEL-1074). 서버 메모리에만 있다 — 서버가 다시 뜨면 사라진다.
 *
 * Agent 세션과 따로 두는 것이 요점이다. 세션은 Agent WS 가 끊기면 함께 치워지는데, 그건 사용자가
 * 한 일이 아니다. draft 는 사용자가 대화를 닫을 때([drop])만 버린다.
 */
@Component
class AuthoringDraftStore(
    private val streamManager: TestScenarioStreamManager,
) {
    private val drafts = ConcurrentHashMap<String, AuthoringDraft>()

    fun of(sessionKey: String): AuthoringDraft = drafts.computeIfAbsent(sessionKey) { AuthoringDraft() }

    fun list(sessionKey: String): List<DraftScenario> = drafts[sessionKey]?.list().orEmpty()

    /** 승인 전 draft 가 있는가. 있으면 새 요청은 받지 않고 draft 만 고친다(ARTEL-1074). */
    fun hasDrafts(sessionKey: String): Boolean = drafts[sessionKey]?.isEmpty() == false

    /** draft 만 — [AuthoringDraft.draftsOnly]. */
    fun draftsOnly(sessionKey: String): List<CurrentScenario> = drafts[sessionKey]?.draftsOnly().orEmpty()

    /** draft 가 있을 때만 겹친다. */
    fun overlay(sessionKey: String, current: List<CurrentScenario>): List<CurrentScenario> =
        drafts[sessionKey]?.overlay(current) ?: current

    fun drop(sessionKey: String) {
        drafts.remove(sessionKey)
    }

    /** 바뀐 draft 전부를 화면에 보낸다. 조각이 아니라 전부를 보내 화면이 합칠 일을 없앤다. */
    fun publish(sessionKey: String) {
        streamManager.emit(sessionKey, ScenarioStreamEvent(type = "draft", drafts = list(sessionKey)))
    }
}
