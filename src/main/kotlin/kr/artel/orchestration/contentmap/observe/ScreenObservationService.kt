package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.firstOrNull
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.TransitionKind
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
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
import java.util.concurrent.ConcurrentHashMap

/**
 * `pulse` 에서 화면을 가르고 화면 전이를 남긴다 (ARTEL-453).
 *
 * QA 런 전에는 `screen` 이 0행이다 — 정적 분석은 화면을 모른다. 이것이 그 0을 깨는 자리이고,
 * 세 가지가 이 행들을 기다린다: 읽기 API(ARTEL-596) · 다이어그램(ARTEL-597) ·
 * 화면 캡처(ARTEL-595 · ARTEL-456).
 *
 * ```
 * PULSE → ScreenFold(fold + discriminator) → settle → screen
 *                                             → screen_capability (씬 목록의 부분집합)
 *                                             → screen_transition (관측만)
 *                                             → scene_edge (씬을 넘었으면 verified / runtime)
 * ```
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
    private val capabilities: CapabilityRepository,
    private val screens: ScreenRepository,
    private val screenCapabilities: ScreenCapabilityRepository,
    private val transitions: ScreenTransitionRepository,
    private val sceneEdges: SceneEdgeRepository,
    private val objectMapper: ObjectMapper,
    private val transactionalOperator: TransactionalOperator,
    private val clock: Clock,
) {

    private val logger = LoggerFactory.getLogger(ScreenObservationService::class.java)

    /**
     * 인스턴스별 `fold` 상태.
     *
     * **락이 없다.** `SdkWebSocketHandler` 가 한 세션의 프레임을 `concatMap` 으로 하나씩 처리하고,
     * 한 게임 인스턴스의 `pulse` 는 한 세션으로만 온다. 그 보장이 깨지면 같은 `discriminator` 가 두 번 굳어
     * `observed_count` 가 두 번 오르는데, 행이 갈리지는 않는다 — `uk_screen_discriminator` 가
     * 막는다.
     *
     * 프로세스 메모리라 재시작하면 사라진다. 다음 전량 `pulse` 가 복구한다.
     */
    private val folds = ConcurrentHashMap<Long, ScreenFold>()

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
            folds.remove(gameInstanceId)
            return
        }

        val buildId = gameInstances.findById(gameInstanceId)?.lastGameBuildId ?: return
        // 지도 고르기는 `ScenarioPathService` · `ScenarioCaseFactService` 와 같은 규칙이다 —
        // 그 빌드의 가장 최근 지도. 여기서만 다른 규칙을 쓰면 화면이 TC 가 읽는 지도와 다른
        // 지도에 붙는다.
        val contentMap = contentMaps.findByGameBuildIdOrderByIdDesc(buildId).firstOrNull() ?: return
        val contentMapId = contentMap.id ?: return

        if (folds.size >= MAX_TRACKED_INSTANCES && !folds.containsKey(gameInstanceId)) {
            // 통째로 비운다. 다음 전량 `pulse` 가 각자 복구하므로 자기 치유되고, 상한 없이 새는
            // 것보다 낫다. 여기 걸린다는 것은 `pulse` 를 흘리는 인스턴스가 수백이라는 뜻이고,
            // 그때는 메모리보다 먼저 볼 것이 있다.
            logger.warn("fold 상태가 {}개를 넘어 비운다", MAX_TRACKED_INSTANCES)
            folds.clear()
        }
        val fold = folds.getOrPut(gameInstanceId) { ScreenFold() }
        fold.apply(reading)

        val sceneName = fold.scene ?: return
        // `evidence` 가 모르는 씬에는 화면을 앉히지 않는다. `screen.scene_id` 가 NOT NULL 이고, 씬을
        // 여기서 만들면 정적 순회가 만든 씬과 이름만 같은 행이 둘 생긴다.
        val scene = scenes.findByContentMapIdAndName(contentMapId, sceneName) ?: return
        val sceneId = scene.id ?: return

        val sceneCapabilities = capabilities.findBySceneIdOrderByIdAsc(sceneId).toList()
        val controlSelectors = sceneCapabilities.mapNotNull { it.controlSelector }.toSet()

        val candidate = fold.discriminate(controlSelectors)
        if (!fold.settle(candidate)) return

        val discriminatorJson = objectMapper.writeValueAsString(candidate.entries)
        val fromScreenId = fold.settledScreenId
        val fromSceneId = fold.settledSceneId
        val screenId = transactionalOperator.executeAndAwait {
            val screenId = upsertScreen(sceneId, discriminatorJson, qaRun.id) ?: return@executeAndAwait null

            val active = candidate.activeSelectors
            for (capability in sceneCapabilities) {
                if (capability.controlSelector !in active) continue
                // firedIncrement 는 0 이다. 무엇을 눌렀는지는 ARTEL-450 이 알려 준다.
                screenCapabilities.observe(screenId, capability.id ?: continue, firedIncrement = 0)
            }

            if (fromScreenId != null && fromSceneId != null && fromScreenId != screenId) {
                recordTransition(fromScreenId, fromSceneId, screenId, sceneId, sceneName, contentMapId, qaRun.id)
            }
            screenId
        } ?: return

        fold.confirm(candidate, screenId, sceneId)
    }

    /**
     * 화면 행 하나. 상한에 걸린 씬에서는 **이미 아는 화면만** 갱신한다.
     *
     * 상한은 임계값이 너무 민감할 때 소리를 내라고 둔 것이지, 그 씬의 관측을 얼리라고 둔 것이
     * 아니다. 새 화면을 만들지 않는 것으로 폭발을 멈추고, 기존 화면의 방문 수는 계속 센다.
     */
    private suspend fun upsertScreen(sceneId: Long, discriminatorJson: String, qaRunId: Long?): Long? {
        if (screens.countBySceneId(sceneId) < MAX_SCREENS_PER_SCENE) {
            return screens.observe(sceneId, discriminatorJson, qaRunId)
        }
        val known = screens.findIdBySceneIdAndDiscriminator(sceneId, discriminatorJson)
        if (known == null) {
            logger.warn(
                "씬 {}의 화면이 {}개를 넘어 새 화면을 만들지 않는다. discriminator 임계값을 의심할 자리다",
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
         * 32 는 "사람이 한 씬에서 구분해 부를 수 있는 화면"의 넉넉한 상한이다. 실측 근거는
         * 아직 없다 — 화면을 만드는 코드가 지금까지 없었다. 첫 런들의 씬별 화면 수를 보고
         * 조정할 값이고, 그 조정은 상한이 아니라 `discriminator` 규칙 쪽이어야 한다.
         */
        const val MAX_SCREENS_PER_SCENE = 32

        /** `fold` 상태를 들고 있을 인스턴스 수의 상한. 넘으면 통째로 비우고 다시 쌓는다. */
        const val MAX_TRACKED_INSTANCES = 256
    }
}
