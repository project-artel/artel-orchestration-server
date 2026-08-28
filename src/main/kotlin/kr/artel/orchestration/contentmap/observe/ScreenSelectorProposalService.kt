package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.ScreenSelectorMatch
import kr.artel.orchestration.contentmap.entity.ScreenSelectorProposalReason
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.SceneScreenSelectorRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenSelectorProposalRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.qa.service.QaScreenSelectorPort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock
import java.util.UUID

/**
 * 제안을 하나 보내는 데 필요한 것 전부. `ScreenObservationService` 가 채워 넘긴다.
 *
 * `ScreenFold` 를 그대로 넘기지 않는 이유는 방향이다. 이 서비스가 `fold` 를 알면 두 서비스가 서로를
 * 알게 되고, `fold` 의 어떤 칸이 프레임에 실리는지가 두 파일에 흩어진다.
 */
data class ScreenSelectorProposalContext(
    val reason: ScreenSelectorProposalReason,
    val sceneId: Long,
    val sceneName: String,
    val previousScreenId: Long?,
    val currentScreenId: Long?,
    val changes: List<ScreenSelectorChange>,
    val candidates: List<ScreenSelectorCandidate>,
)

/** 목록을 고치는 프레임 하나를 적용한 결과. `SCREEN_SELECTOR_RESULT` 가 이것을 그대로 싣는다. */
data class ScreenSelectorApplyOutcome(
    val sceneId: Long?,
    val accepted: List<ScreenSelectorAcceptedEntry> = emptyList(),
    val rejected: List<ScreenSelectorRejectedEntry> = emptyList(),
    val foldedScreens: Int = 0,
)

/**
 * 목록에 없는 selector 를 물어보고, 답이 오면 목록에 넣고 같아지는 화면을 접는다 (ARTEL-655).
 *
 * ```
 * pulse → 목록 밖 selector → SCREEN_SELECTOR_PROPOSAL ─┐
 *   화면 행은 그대로 앉는다 (제안을 기다리지 않는다)     │
 *                                                      ▼
 *          scene_screen_selector ← SCREEN_SELECTOR_VERDICT / SCREEN_SELECTOR_RULE
 *                     │
 *                     └→ fold_scene_screens(scene_id) → 같아진 화면을 접는다
 * ```
 *
 * ## 런을 세우지 않는다
 *
 * 제안은 보내고 끝이다. 답을 기다리는 자리가 없고, 답이 끝내 안 와도 화면 기록은 지금과 똑같이
 * 돌아간다 — 목록에 없는 것은 무시한다는 ARTEL-654 의 기본값이 그대로 남기 때문이다. 제안을
 * 기다렸다가 화면을 앉히면 답이 늦거나 안 오는 동안 관측이 통째로 사라진다. **행 없는 지도보다
 * 나중에 접히는 행이 낫다.**
 *
 * ## 한 번만 묻는다
 *
 * `screen_selector_proposal` 의 `uk_screen_selector_proposal` 이 그 보장이다. 없으면 카드를 뽑을
 * 때마다 제안이 하나씩 나간다.
 *
 * ## 넣는 답과 빼는 답이 비대칭이다
 *
 * 넣는 답(`screen_defining=true`)은 과거 화면을 **가르지 않는다.** 그 selector 의 값이 애초에
 * `discriminator` 에 안 들어갔으니 기록이 없어 복원할 수 없고, 다음 관측부터 갈린다. 빼는
 * 답(`false`)은 소급해서 접을 수 있다 — 지워야 할 값은 기록에 있기 때문이다. 그 비대칭이 버그가
 * 아니라 이 설계의 값이다.
 */
@Service
class ScreenSelectorProposalService(
    private val proposals: ScreenSelectorProposalRepository,
    private val screenSelectors: SceneScreenSelectorRepository,
    private val screens: ScreenRepository,
    private val scenes: SceneRepository,
    private val contentMaps: ContentMapRepository,
    private val gameInstances: GameInstanceRepository,
    private val folds: ScreenFoldRegistry,
    private val agent: QaScreenSelectorPort,
    private val storage: DocumentStorage,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenSelectorProposalService::class.java)

    /**
     * 아직 물어본 적 없는 후보가 있으면 제안 하나를 보낸다.
     *
     * **실패를 삼킨다.** `pulse` 는 관측 채널이지 런의 전제가 아니고, 제안은 그 관측의 곁가지다 —
     * 물어보다 실패했다고 `pulse` 중계가 끊기면 화면을 못 만드는 게임에서 QA 자체가 눈을 잃는다.
     */
    suspend fun propose(gameInstanceId: Long, qaRunId: Long?, context: ScreenSelectorProposalContext) {
        try {
            send(gameInstanceId, qaRunId, context)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "화면 selector 제안을 보내지 못했다 [gameInstanceId={}, scene={}]: {}",
                gameInstanceId, context.sceneName, failure.message, failure,
            )
        }
    }

    private suspend fun send(gameInstanceId: Long, qaRunId: Long?, context: ScreenSelectorProposalContext) {
        if (context.candidates.isEmpty()) return
        val messageId = UUID.randomUUID().toString()
        val askedAt = clock.instant()
        // 물어볼 권리를 먼저 집는다. 집기와 보내기 사이에 같은 selector 가 다시 와도 두 번째
        // 호출은 빈 손이 되어 조용히 멈춘다.
        //
        // 상한 제안은 대상이 씬 하나라 집기도 하나다. 후보마다 집으면 첫 후보만 집히고 나머지가
        // 유니크에 걸려 빠진다 — 물어보는 것은 "이 씬의 목록이 너무 잘다" 한 가지이고, 후보는 그
        // 판단에 필요한 재료일 뿐이다.
        val candidates = context.candidates.take(MAX_CANDIDATES_PER_PROPOSAL)
        val claimed = if (context.reason == ScreenSelectorProposalReason.SCENE_SCREEN_CAP) {
            val taken = proposals.claim(
                sceneId = context.sceneId,
                reason = context.reason.wire,
                selector = CAP_TARGET,
                messageId = messageId,
                qaRunId = qaRunId,
                askedAt = askedAt,
            ) != null
            if (taken) candidates else emptyList()
        } else {
            candidates.filter { candidate ->
                proposals.claim(
                    sceneId = context.sceneId,
                    reason = context.reason.wire,
                    selector = candidate.selector,
                    messageId = messageId,
                    qaRunId = qaRunId,
                    askedAt = askedAt,
                ) != null
            }
        }
        if (claimed.isEmpty()) return

        val payload = ScreenSelectorProposalPayload(
            reason = context.reason.wire,
            scene = ScreenSelectorSceneRef(context.sceneId.toString(), context.sceneName),
            previousScreen = screenRefOf(context.previousScreenId),
            currentScreen = screenRefOf(context.currentScreenId),
            changes = context.changes.take(MAX_CHANGES_PER_PROPOSAL),
            candidates = claimed,
        )
        val delivered = agent.sendScreenSelectorProposal(
            gameInstanceId = gameInstanceId,
            messageId = messageId,
            summary = summaryOf(context, claimed),
            payload = objectMapper.valueToTree(payload),
        )
        if (delivered) return
        // 보낼 곳이 없었다. 집어 둔 것을 놓지 않으면 나가지 못한 질문이 물어본 것으로 남아
        // 영영 안 나간다.
        proposals.release(messageId)
    }

    private fun summaryOf(
        context: ScreenSelectorProposalContext,
        candidates: List<ScreenSelectorCandidate>,
    ): String = when (context.reason) {
        ScreenSelectorProposalReason.UNKNOWN_SELECTOR ->
            "Unlisted selectors appeared in ${context.sceneName}: ${candidates.size} candidate(s)."
        ScreenSelectorProposalReason.SCENE_SCREEN_CAP ->
            "Scene ${context.sceneName} hit the screen cap; the selector list is too fine."
    }

    private suspend fun screenRefOf(screenId: Long?): ScreenSelectorScreenRef? {
        val screen = screenId?.let { screens.findById(it) } ?: return null
        val signed = screen.imageObjectKey?.let { storage.presignDownload(it, "screen-${screen.id}.jpg") }
        return ScreenSelectorScreenRef(
            screenId = screen.id.toString(),
            name = screen.name,
            discriminator = discriminatorOf(screen),
            captureUrl = signed?.url,
            captureExpiresAt = signed?.expiresAt,
        )
    }

    private fun discriminatorOf(screen: ScreenEntity): List<ScreenDiscriminatorEntry> = try {
        objectMapper.readValue(
            screen.discriminator.asString(),
            objectMapper.typeFactory.constructCollectionType(List::class.java, ScreenDiscriminatorEntry::class.java),
        )
    } catch (failure: Exception) {
        // 화면 행 하나를 못 읽었다고 제안을 통째로 버리지 않는다. 후보와 캡처만으로도 답할 수 있다.
        logger.warn("화면 {}의 discriminator 를 읽지 못했다: {}", screen.id, failure.message)
        emptyList()
    }

    /**
     * 제안에 대한 답을 적용한다 (`SCREEN_SELECTOR_VERDICT`).
     *
     * 씬은 payload 가 아니라 **제안 기록**에서 푼다. 답이 늦게 오면 그 사이 agent 는 다른 씬에 가
     * 있고, 그때 지금 서 있는 씬으로 풀면 남의 씬 목록에 항목이 앉는다.
     */
    suspend fun applyVerdict(
        proposalMessageId: String?,
        payload: ScreenSelectorVerdictPayload,
    ): ScreenSelectorApplyOutcome {
        val messageId = payload.proposalId?.takeIf { it.isNotBlank() } ?: proposalMessageId
        if (messageId.isNullOrBlank()) {
            return rejectAll(payload.entries, "SCREEN_SELECTOR_VERDICT needs the proposal id it answers")
        }
        val asked = proposals.findByMessageIdOrderByIdAsc(messageId).toList()
        if (asked.isEmpty()) {
            return rejectAll(payload.entries, "SCREEN_SELECTOR_VERDICT references an unknown proposal: $messageId")
        }
        val sceneId = asked.first().sceneId
        // 답이 후보 하나하나에 대응하지 않아도 닫는다. "화면을 가르는 것이 없다" 도 답이고, 그
        // 답을 받고도 열어 두면 같은 후보를 계속 다시 묻는다.
        proposals.markAnswered(messageId, clock.instant())
        val observed = asked.map { it.selector }.filter { it.isNotBlank() }.toSet()
        return apply(sceneId, payload.entries, observed)
    }

    /**
     * QA agent 의 tool 이 목록을 고친다 (`SCREEN_SELECTOR_RULE`, ARTEL-657).
     *
     * **씬을 넘겨 고치지 못한다.** 목록은 씬 단위이고, 지금 서 있지 않은 씬은 그 씬에 서서 본 것이
     * 아니므로 근거가 없다. 그래서 씬 이름을 받아 그 런의 지도에서 풀고, 없으면 거절한다.
     */
    suspend fun applyRule(
        gameInstanceId: Long,
        payload: ScreenSelectorRulePayload,
    ): ScreenSelectorApplyOutcome {
        val sceneName = payload.scene?.trim()
        if (sceneName.isNullOrEmpty()) {
            return rejectAll(payload.entries, "SCREEN_SELECTOR_RULE payload.scene is required")
        }
        val sceneId = resolveScene(gameInstanceId, sceneName)
            ?: return rejectAll(payload.entries, "SCREEN_SELECTOR_RULE references an unknown scene: $sceneName")
        val observed = folds.observedSelectors(gameInstanceId, sceneName) +
            proposals.findBySceneIdOrderByIdAsc(sceneId).toList().map { it.selector }.filter { it.isNotBlank() }
        return apply(sceneId, payload.entries, observed)
    }

    private suspend fun resolveScene(gameInstanceId: Long, sceneName: String): Long? {
        val buildId = gameInstances.findById(gameInstanceId)?.lastGameBuildId ?: return null
        val contentMapId = contentMaps.findByGameBuildIdOrderByIdDesc(buildId).firstOrNull()?.id ?: return null
        return scenes.findByContentMapIdAndName(contentMapId, sceneName)?.id
    }

    /**
     * 항목을 저장하고 접는다. **접기는 저장한 뒤 한 번만 돈다.**
     *
     * 항목마다 접으면 중간 상태의 목록으로 접힌 결과가 남아, 같은 답을 항목 순서만 바꿔 보내면 다른
     * 최종 상태가 나온다. 마지막에 한 번 도는 접기는 지금의 목록 전체를 보므로 그 순서를 지운다.
     *
     * 접기를 조건 없이 부르는 것도 같은 이유다. "빼는 방향의 답일 때만" 이라고 조건을 달면 그
     * 판정이 접기 규칙의 두 번째 벌이 된다 — `fold_scene_screens` 는 접을 것이 없으면 0 을 돌려주고
     * 값이 그대로인 행은 건드리지 않는다.
     */
    private suspend fun apply(
        sceneId: Long,
        entries: List<ScreenSelectorEntryFrame>,
        observedSelectors: Set<String>,
    ): ScreenSelectorApplyOutcome {
        val accepted = ArrayList<ScreenSelectorAcceptedEntry>()
        val rejected = ArrayList<ScreenSelectorRejectedEntry>()
        for (entry in entries) {
            val verdict = validate(entry, observedSelectors)
            when (verdict) {
                is EntryVerdict.Rejected -> rejected.add(
                    ScreenSelectorRejectedEntry(entry.match, entry.pattern, verdict.reason)
                )
                is EntryVerdict.Accepted -> {
                    screenSelectors.upsertRule(
                        sceneId = sceneId,
                        matchKind = verdict.match.wire,
                        pattern = verdict.pattern,
                        source = ScreenSelectorSource.AGENT.wire,
                        screenDefining = verdict.screenDefining,
                    )
                    accepted.add(
                        ScreenSelectorAcceptedEntry(verdict.match.wire, verdict.pattern, verdict.screenDefining)
                    )
                }
            }
        }
        if (accepted.isEmpty()) return ScreenSelectorApplyOutcome(sceneId, accepted, rejected)

        val folded = screens.foldScene(sceneId)
        if (folded > 0) {
            // 접힌 화면 행은 사라졌다. 그 id 를 들고 있는 `fold` 는 다음 전이를 없는 행에서 출발시킨다.
            folds.forgetSettledIn(sceneId)
            logger.info("씬 {}의 화면 {}개가 목록 변경으로 접혔다", sceneId, folded)
        }
        return ScreenSelectorApplyOutcome(sceneId, accepted, rejected, folded)
    }

    private sealed interface EntryVerdict {
        data class Accepted(
            val match: ScreenSelectorMatch,
            val pattern: String,
            val screenDefining: Boolean,
        ) : EntryVerdict

        data class Rejected(val reason: String) : EntryVerdict
    }

    /**
     * 항목 하나가 저장할 수 있는 것인가.
     *
     * 없는 대상을 거절하는 것이 요점이다(ARTEL-657). 목록에 없는 문자열을 그대로 받으면 아무것에도
     * 안 맞는 항목이 조용히 쌓이고, 부른 쪽은 고쳤다고 믿는다.
     *
     * 정규식을 따로 막지 않는다. `.*` 를 보낸 답은 그 자체로 이 검사에 걸린다 — 아무 selector 와도
     * 글자 그대로 같지 않으므로 "맞는 것이 없다" 로 거절된다. 메타문자로 걸러 보려던 첫 판은
     * 버렸다: 실측 selector 에 `Card(Clone)[37]` 처럼 괄호가 들어 있어, 그 검사가 멀쩡한 항목을
     * 거절했다.
     *
     * [observedSelectors] 가 비면 검증을 건너뛴다. 서버가 재시작해 `fold` 를 잃었고 그 씬에 물어본
     * 기록도 없는 경우인데, 그때 전부 거절하면 **고칠 방법이 사라진다** — 잘못된 정확 문자열은
     * 아무것에도 안 맞고 끝나므로 그 위험이 거절보다 싸다.
     */
    private fun validate(entry: ScreenSelectorEntryFrame, observedSelectors: Set<String>): EntryVerdict {
        val match = ScreenSelectorMatch.from(entry.match)
            ?: return EntryVerdict.Rejected("match must be one of selector, path, subtree: ${entry.match}")
        val pattern = entry.pattern?.trim()
        if (pattern.isNullOrEmpty()) return EntryVerdict.Rejected("pattern is required")
        if (pattern.length > MAX_PATTERN_LENGTH) {
            return EntryVerdict.Rejected("pattern is longer than $MAX_PATTERN_LENGTH characters")
        }
        val screenDefining = entry.screenDefining
            ?: return EntryVerdict.Rejected("screen_defining is required")
        if (entry.reason.isNullOrBlank()) return EntryVerdict.Rejected("reason is required")
        if (observedSelectors.isNotEmpty() && !matchesAny(match, pattern, observedSelectors)) {
            return EntryVerdict.Rejected("pattern matches nothing observed in this scene: $pattern")
        }
        return EntryVerdict.Accepted(match, pattern, screenDefining)
    }

    private fun matchesAny(match: ScreenSelectorMatch, pattern: String, observed: Set<String>): Boolean {
        val rule = ScreenSelectorRule(match, pattern, ScreenSelectorSource.AGENT, true)
        return observed.any { rule.matches(it, indexFreePathOf(it)) }
    }

    private fun rejectAll(entries: List<ScreenSelectorEntryFrame>, reason: String) =
        ScreenSelectorApplyOutcome(
            sceneId = null,
            rejected = entries.map { ScreenSelectorRejectedEntry(it.match, it.pattern, reason) }
                .ifEmpty { listOf(ScreenSelectorRejectedEntry(reason = reason)) },
        )

    companion object {
        /**
         * 제안 하나에 실을 후보 수의 상한.
         *
         * 첫 전량 `pulse` 는 그 씬의 selector 전부를 처음 보는 것으로 들고 온다 — 실측
         * `TurnBattleScene` 이 62 개다. 상한을 두는 것은 답하는 쪽의 예산 때문이기도 하지만, 그보다
         * 후보가 수십이면 캡처와 대조해 판단하는 것이 사실상 불가능하기 때문이다. 넘친 것은 집히지
         * 않았으므로 다음 `pulse` 에서 다시 후보가 된다.
         */
        const val MAX_CANDIDATES_PER_PROPOSAL = 12

        /** 제안에 실을 변화 수의 상한. 후보와 달리 이쪽은 참고용이라 잘려도 잃는 것이 적다. */
        const val MAX_CHANGES_PER_PROPOSAL = 32

        /** `scene_screen_selector.pattern` 의 길이. */
        const val MAX_PATTERN_LENGTH = 512

        /** `scene-screen-cap` 제안의 대상은 씬 자체다. 표의 `ck_screen_selector_proposal_target` 과 짝이다. */
        private const val CAP_TARGET = ""
    }
}
