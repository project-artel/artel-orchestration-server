package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.capture.ScreenCaptureService
import kr.artel.orchestration.contentmap.dto.ScreenObservationRow
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.ScreenSelectorMatch
import kr.artel.orchestration.contentmap.entity.ScreenSelectorProposalReason
import kr.artel.orchestration.contentmap.entity.ScreenSelectorSource
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.SceneScreenSelectorRepository
import kr.artel.orchestration.contentmap.repository.ScreenCapabilityRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.repository.ScreenTransitionRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.qa.repository.QaRunRepository
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.time.Clock

/**
 * `pulse` 에서 화면을 가르고 화면 전이를 남긴다 (ARTEL-453).
 *
 * QA 런 전에는 `screen` 이 0행이다 — 정적 분석은 화면을 모른다. 이것이 그 0을 깨는 자리이고,
 * 둘이 이 행들을 기다린다: 읽기 API(ARTEL-596) · 다이어그램(ARTEL-597).
 *
 * ```
 * PULSE → ScreenFold(fold + discriminator) → settle → screen
 *                                             → screen_capability (씬 목록의 부분집합)
 *                                             → screen_transition (관측만)
 *                                             → scene_edge (씬을 넘었으면 verified / runtime)
 *                                             → capture_screen (처음 앉힐 때만, ARTEL-456)
 *                                          ├→ SCREEN_SELECTOR_PROPOSAL (목록 밖 selector, ARTEL-655)
 *                                          └→ SCREEN_SETTLED (화면이 바뀌었으면, ARTEL-668)
 * ```
 *
 * ## 확정한 화면은 물어보지 않아도 알린다
 *
 * 제안은 `(scene, selector)` 마다 평생 한 번뿐이라, 이미 한 번 플레이한 빌드에서는 한 장도 안
 * 나간다. 그래서 화면이 굳었다는 **사실**은 제안과 별개의 프레임으로 나간다
 * ([ScreenSettledService]) — agent 가 지도의 판정을 보고 목록을 고치는 tool 을 부르려면
 * (ARTEL-657) 그 판정이 런마다 보여야 한다.
 *
 * ## 목록 밖은 무시하되 물어본다
 *
 * `discriminator` 는 목록에 있는 selector 만 담는다(ARTEL-654). 그 기본값이 화면 폭발을 멈췄지만,
 * 목록이 얇으면 서로 다른 두 화면이 한 행에 앉고 **그 실패는 조용하다.** 그래서 목록에도 제외에도
 * 없는 selector 를 만나면 제안을 보낸다([propose]). 화면 행은 그대로 앉고 런은 답을 기다리지
 * 않는다 — 답이 끝내 안 와도 이 서비스는 지금과 똑같이 돈다.
 *
 * ## ARTEL-450 이 없어서 못 채우는 것
 *
 * 액션과 `pulse` 를 시간축으로 붙이는 ARTEL-450 이 아직 없다. 그것이 "이 전이를 무엇이 일으켰나"를
 * 답하는 유일한 자리라, 지금은 **`pulse` 만으로** 화면과 전이를 유도한다. 그 결과:
 *
 * - `screen_transition.capability_id` 는 **늘 null** 이다. 정직하게 귀속할 수 없는 자리에
 *   추측을 넣으면 "실제로 어떻게 흘렀나"가 오염된다 — 이 표를 정적으로 만들지 않는 것과 같은
 *   이유다
 * - `screen_capability.fired_count` 는 **늘 0** 이다. "눌렀는데 아무것도 안 변했다"를 세려면
 *   무엇을 눌렀는지 알아야 한다. `observed_count` 만 참이고, 둘의 차이를 결함 신호로 읽는
 *   소비자는 ARTEL-450 뒤에 와야 한다
 * - `scene_edge` 검증이 **씬 쌍 단위**다. 기능 단위로는 과다 주장한다
 *   ([SceneEdgeRepository.verifyByScenePair] 참고)
 * - `TransitionKind.AUTO` 를 **한 번도 내지 않는다**. 아래 [kindOf] 참고
 *
 * ## 실패를 삼킨다
 *
 * `pulse` 는 관측 채널이지 런의 전제가 아니다(`QaReadingsService` 와 같은 판단). 화면 적재가
 * 실패했다고 `pulse` 중계가 끊기면, 화면을 못 만드는 게임에서 QA 자체가 눈을 잃는다.
 */
@Service
class ScreenObservationService(
    private val gameInstances: GameInstanceRepository,
    private val qaRuns: QaRunRepository,
    private val contentMaps: ContentMapRepository,
    private val scenes: SceneRepository,
    private val screenSelectors: SceneScreenSelectorRepository,
    private val capabilities: CapabilityRepository,
    private val screens: ScreenRepository,
    private val screenCapabilities: ScreenCapabilityRepository,
    private val transitions: ScreenTransitionRepository,
    private val sceneEdges: SceneEdgeRepository,
    private val folds: ScreenFoldRegistry,
    private val selectorProposals: ScreenSelectorProposalService,
    private val settledScreens: ScreenSettledService,
    private val screenCaptures: ScreenCaptureService,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenObservationService::class.java)

    suspend fun observe(gameInstanceId: Long, payloadText: String) {
        try {
            val reading = objectMapper.readValue(payloadText, PulseReading::class.java)
            record(gameInstanceId, reading)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            // 삼키되 조용하지는 않게. 화면이 안 생길 때 사람이 볼 유일한 자리다.
            logger.warn(
                "pulse 에서 화면을 가르지 못했다 [gameInstanceId={}]: {}",
                gameInstanceId, failure.message, failure,
            )
        }
    }

    private suspend fun record(gameInstanceId: Long, reading: PulseReading) {
        // 화면은 **QA 런이 관측한 것**이다. 런이 없으면 `fold` 상태도 버린다 — 스캔 순회가 흘리는
        // `pulse` 가 다음 런의 첫 화면을 오염시키지 않게.
        val qaRun = qaRuns.findActiveByGameInstanceId(gameInstanceId)
        if (qaRun == null) {
            folds.forget(gameInstanceId)
            return
        }

        val buildId = gameInstances.findById(gameInstanceId)?.lastGameBuildId ?: return
        // 빌드마다 지도가 하나다(ARTEL-642). 고를 것이 없으므로 화면이 TC 가 읽는 지도와
        // 다른 지도에 붙는 일도 없다.
        val contentMap = contentMaps.findByGameBuildId(buildId) ?: return
        val contentMapId = contentMap.id ?: return

        val fold = folds.of(gameInstanceId)
        fold.apply(reading)

        val sceneName = fold.scene ?: return
        // `evidence` 가 모르는 씬에는 화면을 앉히지 않는다. `screen.scene_id` 가 NOT NULL 이고, 씬을
        // 여기서 만들면 정적 순회가 만든 씬과 이름만 같은 행이 둘 생긴다.
        val scene = scenes.findByContentMapIdAndName(contentMapId, sceneName) ?: return
        val sceneId = scene.id ?: return

        val sceneCapabilities = capabilities.findBySceneIdOrderByIdAsc(sceneId).toList()

        val whitelist = seededWhitelist(sceneId, sceneCapabilities)
        val candidate = fold.discriminate(whitelist)
        // **화면 행을 먼저 만들고 제안은 그 뒤에 보낸다** (ARTEL-655). 제안을 기다렸다가 앉히면
        // 답이 늦거나 안 오는 동안 관측이 통째로 사라진다. 행 없는 지도보다 나중에 합쳐지는 행이 낫다.
        val cappedScene = if (fold.settle(candidate)) {
            settle(gameInstanceId, fold, candidate, sceneId, sceneName, contentMapId, sceneCapabilities, qaRun.id)
        } else {
            false
        }
        propose(gameInstanceId, qaRun.id, fold, whitelist, sceneId, sceneName, cappedScene)
    }

    /**
     * 굳은 `discriminator` 를 화면 행으로 앉히고 전이를 남긴다. 씬이 상한에 걸렸으면 `true`.
     */
    private suspend fun settle(
        gameInstanceId: Long,
        fold: ScreenFold,
        candidate: ScreenDiscriminator,
        sceneId: Long,
        sceneName: String,
        contentMapId: Long,
        sceneCapabilities: List<CapabilityEntity>,
        qaRunId: Long?,
    ): Boolean {
        val discriminatorJson = objectMapper.writeValueAsString(candidate.entries)
        val fromScreenId = fold.settledScreenId
        val fromSceneId = fold.settledSceneId
        var capped = false
        val observed = transactionalOperator.executeAndAwait {
            val observed = upsertScreen(sceneId, discriminatorJson, qaRunId)
            if (observed == null) {
                capped = true
                return@executeAndAwait null
            }
            val screenId = observed.id

            // `discriminator` 가 아니라 `fold` 에서 읽는다 — 목록에 없는 컨트롤이라고 해서 그 화면이
            // 그 기능을 제공하지 않은 것은 아니다([ScreenFold.activeSelectors]).
            val active = fold.activeSelectors()
            for (capability in sceneCapabilities) {
                if (capability.controlSelector !in active) continue
                // firedIncrement 는 0 이다. 무엇을 눌렀는지는 ARTEL-450 이 알려 준다.
                screenCapabilities.observe(screenId, capability.id ?: continue, firedIncrement = 0)
            }

            if (fromScreenId != null && fromSceneId != null && fromScreenId != screenId) {
                recordTransition(fromScreenId, fromSceneId, screenId, sceneId, sceneName, contentMapId, qaRunId)
            }
            observed
        } ?: return capped

        fold.confirm(candidate, observed.id, sceneId)

        // 화면을 **처음 앉혔을 때만** 그림을 요청한다 (ARTEL-456). 다시 본 화면은 다시 찍지 않고,
        // 이미 붙은 그림도 바꾸지 않는다 — 화면이 무엇인지 말하는 그림은 그 화면을 처음 만나
        // 화면이라고 판정한 순간의 것이다.
        //
        // 커밋 **뒤에** 부른다. 트랜잭션 안에 두면 롤백된 화면의 그림을 요청하게 되고, 그 그림은
        // 존재하지 않는 행을 기다리다 버려진다.
        if (observed.inserted) screenCaptures.request(gameInstanceId, observed.id)

        // **화면이 실제로 바뀐 관측에서만 알린다** (ARTEL-668). 지금 모양에서는 [ScreenFold.settle]
        // 이 `discriminator` 가 달라졌을 때만 true 라 이 판정이 늘 참이지만, 조건으로 적어 둔다 —
        // 굳히는 규칙이 바뀌어 같은 화면에서도 여기까지 오게 되면 실측 14489 개의 `pulse` 가
        // 그대로 프레임 14489 개가 된다.
        if (observed.id != fromScreenId) {
            settledScreens.announce(gameInstanceId, sceneId, sceneName, fold.previousScreenId, observed.id)
        }
        return capped
    }

    /**
     * 목록에 없는 selector 를 물어본다 (ARTEL-655).
     *
     * 두 계기가 한 프레임으로 나간다.
     *
     * - **목록 밖 selector** — 이 `pulse` 에서 상태가 달라졌는데 목록에도 제외에도 없는 것. 그것이
     *   화면을 가르는 것이면 지금 지도는 서로 다른 두 화면을 한 행에 앉히고 있고, 그 실패는 조용하다
     * - **씬의 화면 상한** — 목록이 너무 잘다는 신호다. 그때 할 일은 기록을 멈추는 것이 아니라
     *   목록을 좁히는 것이므로, 지금 화면을 가르고 있는 것들을 후보로 내어 무엇을 뺄지 묻는다
     *
     * 상한 쪽이 먼저다. 상한에 걸린 씬에서 목록 밖을 더 물어봐야 답이 와도 화면을 더 못 만든다.
     */
    private suspend fun propose(
        gameInstanceId: Long,
        qaRunId: Long?,
        fold: ScreenFold,
        whitelist: ScreenSelectorWhitelist,
        sceneId: Long,
        sceneName: String,
        cappedScene: Boolean,
    ) {
        val reason = if (cappedScene) {
            ScreenSelectorProposalReason.SCENE_SCREEN_CAP
        } else {
            ScreenSelectorProposalReason.UNKNOWN_SELECTOR
        }
        val candidates = if (cappedScene) {
            fold.whitelistedCandidates(whitelist)
        } else {
            fold.unknownCandidates(whitelist)
        }
        if (candidates.isEmpty()) return
        selectorProposals.propose(
            gameInstanceId,
            qaRunId,
            ScreenSelectorProposalContext(
                reason = reason,
                sceneId = sceneId,
                sceneName = sceneName,
                previousScreenId = fold.previousScreenId,
                currentScreenId = fold.settledScreenId,
                changes = fold.lastChanges,
                candidates = candidates,
            ),
        )
    }

    /**
     * 이 씬의 목록을 낸다 — 저장된 항목에 `capability.control_selector` 씨앗을 심어서 (ARTEL-654).
     *
     * ## 왜 씨앗이 필요한가
     *
     * 목록이 비면 씬 전체가 화면 하나라서 초반 런의 지도가 쓸모없다. `control_selector` 는 정적
     * 분석이 코드에서 뽑은 것이라 런타임에 스폰된 이름이 아니고, **정의상 조작할 수 있는 것들**
     * 이다. 실측 빌드에서 capability 472 개 중 그 칸이 있는 것은 24 개로 얇지만 0 보다 낫다.
     *
     * ## 왜 마이그레이션만으로는 부족한가
     *
     * `V60__whitelist_screen_defining_selectors.sql` 은 **그때 있던** capability 만 심는다. 다음
     * 빌드의 `evidence` 가 새 씬과 새 capability 를 만들면 그 씬은 목록이 비어 화면 하나가 된다.
     * 그래서 런타임도 같은 씨앗을 심는다.
     *
     * ## 왜 캐시하지 않는가
     *
     * `screen` 의 식별 키(`uk_screen_discriminator`)가 런과 프로세스를 넘어 사는 값이므로 그 값을
     * 만드는 규칙도 그래야 한다. 프로세스 메모리에 들면 서버 재시작·서버 두 대·사람이 목록을 고친
     * 직후 — 셋 다에서 같은 화면이 다른 `discriminator` 로 앉아 행이 갈린다. `V59` 가 `fold` 상태를
     * 믿지 않기로 한 것과 정확히 같은 판단이다.
     *
     * ## 쓰기 비용
     *
     * 새로 본 씨앗만 쓴다. 그 집합은 씬의 capability 수로 수렴하므로 몇 번의 `pulse` 뒤에는 늘 비고,
     * 남는 것은 `SELECT` 하나다.
     */
    private suspend fun seededWhitelist(
        sceneId: Long,
        sceneCapabilities: List<CapabilityEntity>,
    ): ScreenSelectorWhitelist {
        val stored = screenSelectors.findBySceneIdOrderByIdAsc(sceneId).toList()
        val seeded = stored.asSequence()
            .filter { it.source == ScreenSelectorSource.STATIC_ANALYSIS.wire }
            .filter { it.matchKind == ScreenSelectorMatch.SELECTOR.wire }
            .map { it.pattern }
            .toSet()
        val fresh = sceneCapabilities.mapNotNull { it.controlSelector }
            .filter { it.isNotBlank() && it !in seeded }
            .toSet()
        if (fresh.isEmpty()) return ScreenSelectorWhitelist(stored.mapNotNull { it.toRule() })

        for (pattern in fresh) screenSelectors.seedFromControlSelector(sceneId, pattern)
        // 심은 뒤 다시 읽는다. 항목의 id 가 우선순위의 마지막 못이라(`ScreenSelectorWhitelist.defines`)
        // 메모리에서 지어낸 행으로 대신하면 그 못이 DB 와 다른 값을 갖는다.
        return ScreenSelectorWhitelist(
            screenSelectors.findBySceneIdOrderByIdAsc(sceneId).toList().mapNotNull { it.toRule() }
        )
    }

    /**
     * 화면 행 하나. 상한에 걸린 씬에서는 **이미 아는 화면만** 갱신한다.
     *
     * 상한은 임계값이 너무 민감할 때 소리를 내라고 둔 것이지, 그 씬의 관측을 얼리라고 둔 것이
     * 아니다. 새 화면을 만들지 않는 것으로 폭발을 멈추고, 기존 화면의 방문 수는 계속 센다.
     *
     * null 을 돌려주는 것이 곧 "이 씬은 지금 상한에 걸려 있다" 이고, 부르는 쪽은 그것으로
     * 목록을 좁힐 제안을 낸다(ARTEL-655). **경고만 찍고 끝내지 않는 이유**는 상한에 닿았다는 것이
     * 목록이 너무 잘다는 뜻이라서다 — 그때 할 일은 포기가 아니라 좁히는 것이고, 좁히는 유일한
     * 방법은 묻는 것이다.
     */
    private suspend fun upsertScreen(
        sceneId: Long,
        discriminatorJson: String,
        qaRunId: Long?,
    ): ScreenObservationRow? {
        if (screens.countBySceneId(sceneId) < MAX_SCREENS_PER_SCENE) {
            return screens.observe(sceneId, discriminatorJson, qaRunId)
        }
        val known = screens.findIdBySceneIdAndDiscriminator(sceneId, discriminatorJson)
        if (known == null) {
            logger.warn(
                "씬 {}의 화면이 {}개를 넘었다. 목록이 너무 잘다는 뜻이라 무엇을 뺄지 제안을 낸다",
                sceneId, MAX_SCREENS_PER_SCENE,
            )
            return null
        }
        return screens.observe(sceneId, discriminatorJson, qaRunId)
    }

    private suspend fun recordTransition(
        fromScreenId: Long,
        fromSceneId: Long,
        toScreenId: Long,
        toSceneId: Long,
        toSceneName: String,
        contentMapId: Long,
        qaRunId: Long?,
    ) {
        val crossesScene = fromSceneId != toSceneId
        val transitionId = transitions.observeUnattributed(
            fromScreenId = fromScreenId,
            toScreenId = toScreenId,
            kind = kindOf(crossesScene).wire,
            crossesScene = crossesScene,
            qaRunId = qaRunId,
        )
        if (!crossesScene) return

        val observedAt = clock.instant()
        val verified = sceneEdges.verifyByScenePair(fromSceneId, toSceneName, transitionId, observedAt)
        if (verified > 0) return
        // 정적 후보에 없던 전이다. **오류가 아니라 발견이다** — 정적 분석이 놓친 씬 전이이고,
        // `evidence` 수집을 어디서 고칠지 알려주는 신호다.
        sceneEdges.observeRuntime(fromSceneId, toSceneName, contentMapId, transitionId, observedAt)
        logger.info(
            "정적 후보에 없던 씬 전이를 관측했다 [fromSceneId={}, toSceneName={}]",
            fromSceneId, toSceneName,
        )
    }

    /**
     * 이 전이를 무엇으로 분류하나. **ARTEL-450 이 붙기 전의 잠정 규칙이다.**
     *
     * 이슈가 정한 세 `kind` 는 원인을 말한다:
     * ```
     * action   조작으로 일어남        capability_id 있음
     * state    씬 안 상태 변화        있거나 없음
     * auto     타이머·로딩·컷신 종료   capability_id 없음. TC 가 지시할 수 없다
     * ```
     * 그런데 원인은 액션-`pulse` 시간축 없이는 알 수 없다. 그래서 **관측만으로 참인 것**을 고른다.
     *
     * - 같은 씬 안의 변화 → [TransitionKind.STATE]. `state` 의 정의가 글자 그대로 "씬 안 상태
     *   변화"이고 `capability_id` 가 없어도 되는 유일한 `kind` 다
     * - 씬을 넘는 변화 → [TransitionKind.ACTION]. 정의상 딱 맞지는 않지만, 셋 중 **틀렸을 때
     *   가장 싼** 것이다. `auto` 는 "TC 가 지시할 수 없다"는 주장이라, 실은 버튼으로 가는
     *   전이에 그것을 달면 TC 생성기가 실제로 지시 가능한 경로를 **영영** 제외한다. `action`
     *   이 틀리면 지시할 기능이 없어 아무 일도 안 일어날 뿐이다
     *
     * 그래서 이 유도는 `auto` 를 **한 번도 내지 않는다.** ARTEL-450 이 붙으면 여기서 다시 판정한다 —
     * 전이 직전 귀속 창에 액션이 없었으면 그것이 `auto` 이고, 있었으면 그 기능이 `capability_id` 다.
     */
    private fun kindOf(crossesScene: Boolean): TransitionKind =
        if (crossesScene) TransitionKind.ACTION else TransitionKind.STATE

    companion object {
        /**
         * 한 씬이 가질 수 있는 화면 수의 상한. **임계값이 틀렸을 때 소리를 내는 안전판이다.**
         *
         * 여기 걸린다는 것은 `discriminator` 가 너무 민감하다는 뜻이다. 상한이 없으면 그 사실이 조용히
         * 수만 행과 행마다 튀는 캡처로만 드러나고, 그때는 이미 다이어그램이 읽을 수 없다.
         *
         * 32 는 "사람이 한 씬에서 구분해 부를 수 있는 화면"의 넉넉한 상한이다.
         *
         * **첫 실측이 이 주석의 예상대로 왔다.** `artel_integration` 의 `TurnBattleScene` 이 29행까지
         * 올라 이 값에 닿기 직전이었고, 고칠 자리는 상한이 아니라 `discriminator` 규칙 쪽이었다 —
         * 화면 판정에 쓸 selector 를 목록으로 두자 같은 관측이 2행으로 합쳐졌다(ARTEL-654,
         * [seededWhitelist]). 32 는 그대로 둔다. 여기 다시 걸리면 그때도 먼저 의심할 것은 목록에 든
         * 항목이 너무 넓은가다.
         */
        const val MAX_SCREENS_PER_SCENE = 32
    }
}
