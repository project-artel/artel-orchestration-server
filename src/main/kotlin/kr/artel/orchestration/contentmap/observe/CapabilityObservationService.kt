package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityObservationEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.repository.CapabilityObservationRepository
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.qa.service.QaActionObservationPort
import kr.artel.orchestration.sdk.dto.ActionItemDto
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.time.Clock

/**
 * 액션과 `pulse` 를 시간축으로 붙여 `capability_observation` 을 남긴다 (ARTEL-450).
 *
 * ```
 * ACTION       → ActionTimeline.open     창을 연다(앞선 창은 닫힌다)
 * ACTION_RESULT→ ActionTimeline.accept   또는 reject
 * PULSE        → ActionTimeline.absorb   창을 채우고, 다 찬 창을 관측으로 낸다
 *                                        └→ capability_observation
 * ```
 *
 * 무엇을 이 액션의 결과로 볼 것인가는 전부 [ActionTimeline] 에 있다. 이 클래스가 더하는 것은 둘
 * 뿐이다 — 조준을 지도의 기능으로 옮기는 것([capabilitiesFor])과, 그 결과를 행으로 쓰는 것.
 *
 * ## 컨트롤 하나에 기능이 여럿이다
 *
 * `capability` 는 **근거의 갈래마다** 한 행이다. 실측 `TitleScene` 의 `Canvas[2]/continue[2]` 뒤에
 * 다섯 행이 있고(`SavePlayData` · `LoadPlayData` · `LoadStoryScene` 셋), `CombineButton` 뒤에 둘,
 * `TurnEndButton` 뒤에 둘이다. 그런데 **클릭 한 번이 그 중 어느 갈래를 탔는지는 `pulse` 가 말하지
 * 않는다.**
 *
 * 그래서 관측은 그 컨트롤 뒤의 기능 **전부**에 대해 한 행씩 남긴다. 한 조작이 관측 행 여럿이 되는
 * 것은 의도다. 무엇을 주장하고 무엇을 안 주장하는지가 여기서 갈린다:
 *
 * - 주장한다 — "이 컨트롤에 이 조작을 보냈고, 그 뒤 이런 것들이 새로 달라졌다"
 * - **주장하지 않는다** — "그 변화를 이 기능이 일으켰다"
 *
 * 갈래를 가르는 것은 관측된 효과를 기대와 맞대는 ARTEL-451 의 몫이다. `LoadStoryScene` 의 기대는
 * 씬 전이라 실측 창에서 나타나고, `SavePlayData` 의 기대는 안 나타난다 — **그 차이가 갈래를
 * 가르는 유일한 근거이고, 그 재료가 `observed_effects` 다.** 여기서 하나를 골라 집으면 "안다"와
 * "여럿 중 하나를 골랐다"가 구분되지 않는다(`SceneEdgeRepository.verifyByScenePair` 와 같은 판단).
 *
 * ## 승격하지 않는다
 *
 * `capability.verification` 도 `capability_effect` 도 여기서 건드리지 않는다. 관측을 남기는 것과
 * 그것으로 상태를 올리는 것은 다른 이슈이고(ARTEL-451), 한 자리에 두면 관측이 도착하는 순서가 곧
 * 승격 규칙이 된다.
 *
 * ## `pulse` 처리를 세우지 않는다
 *
 * 실측 한 런의 `pulse` 가 14,489 개다. [observe] 가 `pulse` 마다 하는 일은 타임라인 하나를 접는
 * 것뿐이고, DB 를 만지는 것은 **창이 닫히는 순간에만**이다 — 같은 런에서 그 순간은 53 번이었다.
 * 실패는 여기서 삼킨다([observe]).
 */
@Service
class CapabilityObservationService(
    private val timelines: ActionTimelineRegistry,
    private val scenes: SceneRepository,
    private val capabilities: CapabilityRepository,
    private val observations: CapabilityObservationRepository,
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
) : QaActionObservationPort {

    private val logger = LoggerFactory.getLogger(CapabilityObservationService::class.java)

    override suspend fun dispatched(gameInstanceId: Long, requestId: Long, actions: List<ActionItemDto>) {
        try {
            val timeline = timelines.of(gameInstanceId)
            val actedAt = clock.instant()
            for (action in actions) {
                // 겨눈 것이 없는 액션도 창을 닫는다 — 배타성의 근거는 겨냥이 아니라 조작이 있었다는
                // 사실이다([ActionTimeline] 의 규칙 3).
                timeline.open(requestId, targetOf(timeline, action), action.method, action.params, actedAt)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "액션을 관측 타임라인에 넣지 못했다 [gameInstanceId={}, requestId={}]: {}",
                gameInstanceId, requestId, failure.message, failure,
            )
        }
    }

    override suspend fun settled(gameInstanceId: Long, requestId: Long, succeeded: Boolean) {
        // 액션이 나간 적 없는 인스턴스에 타임라인을 만들지 않는다.
        val timeline = timelines.find(gameInstanceId) ?: return
        if (succeeded) timeline.accept(requestId) else timeline.reject(requestId)
    }

    /**
     * `pulse` 하나를 관측 타임라인에 넣고, 그 결과 닫힌 창을 행으로 남긴다.
     *
     * **실패를 삼킨다.** `ScreenObservationService` 와 같은 판단이다 — 관측을 못 남겼다고 화면
     * 적재까지 멈추면, 액션을 해석하지 못하는 게임에서 화면 기록마저 사라진다.
     */
    suspend fun observe(context: ActionObservationContext, reading: PulseReading) {
        try {
            val closed = timelines.of(context.gameInstanceId).absorb(reading, context.screenId, clock.instant())
            for (window in closed) record(context, window)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "액션 관측을 남기지 못했다 [gameInstanceId={}]: {}",
                context.gameInstanceId, failure.message, failure,
            )
        }
    }

    /**
     * 이 인스턴스의 타임라인을 버린다. 런이 끝났을 때 `ScreenFoldRegistry.forget` 과 나란히 부른다.
     *
     * 아직 안 닫힌 창도 함께 사라진다. 런이 끝난 뒤에 닫히는 창은 그 런의 `qa_run_id` 를 못 받아
     * 어차피 행이 되지 못한다.
     */
    fun forget(gameInstanceId: Long) {
        timelines.forget(gameInstanceId)
    }

    private suspend fun record(context: ActionObservationContext, window: ClosedActionWindow) {
        val matched = capabilitiesFor(context.contentMapId, window.target)
        if (matched.isEmpty()) {
            // 오류가 아니다. 지도가 모르는 컨트롤을 눌러 본 것이고, 그것은 근거의 구멍이지 이 코드의
            // 실패가 아니다. 어디에 구멍이 있는지 사람이 볼 자리가 여기뿐이라 남긴다.
            logger.info(
                "지도가 모르는 것을 조작했다 [gameInstanceId={}, target={}, method={}]",
                context.gameInstanceId, window.target.key, window.method,
            )
            return
        }
        val params = Json.of(objectMapper.writeValueAsString(window.params))
        val effects = Json.of(objectMapper.writeValueAsString(window.effects))
        for (capability in matched) {
            observations.save(
                CapabilityObservationEntity(
                    capabilityId = capability.id ?: continue,
                    qaRunId = context.qaRunId,
                    screenId = window.screenId,
                    actedAt = window.actedAt,
                    actionMethod = window.method.take(ACTION_METHOD_LIMIT),
                    actionParams = params,
                    attempts = window.attempts,
                    readingBefore = window.readingBefore,
                    readingAfter = window.readingAfter,
                    fired = window.fired,
                    observedEffects = effects,
                )
            )
        }
    }

    /**
     * 이 조준 뒤에 있는 기능 전부. 없으면 빈 목록이다.
     *
     * 씬은 **액션이 나갈 때의 씬**으로 찾는다. 창이 닫힐 때의 씬으로 찾으면, 씬을 넘긴 조작이
     * 도착한 씬의 기능에 붙는다 — `continue` 를 눌러 `Map_scene` 으로 갔는데 관측이 `Map_scene` 의
     * 기능에 달리는 식이다.
     */
    private suspend fun capabilitiesFor(contentMapId: Long, target: ActionTarget): List<CapabilityEntity> {
        val sceneId = scenes.findByContentMapIdAndName(contentMapId, target.scene)?.id ?: return emptyList()
        val sceneCapabilities = capabilities.findBySceneIdOrderByIdAsc(sceneId).toList()
        return when (target) {
            is ActionTarget.Control -> sceneCapabilities.filter { it.controlSelector == target.selector }
            is ActionTarget.Key -> sceneCapabilities.filter { matchesKey(it, target.inputKey) }
        }
    }

    /**
     * 이 기능이 이 키를 받나.
     *
     * `any` 는 근거가 특정 키를 지목하지 않은 "아무 키" 조작이라 어느 키에도 맞는다
     * ([Interaction.ANY_INPUT_KEY]). 키 이름 자체는 SDK 표기와 근거 표기가 대소문자만 다를 수 있어
     * 대소문자를 무시한다 — `Space` 와 `space` 를 다른 키로 읽으면 그 기능은 영영 관측되지 않는다.
     */
    private fun matchesKey(capability: CapabilityEntity, inputKey: String): Boolean {
        val declared = capability.inputKey ?: return false
        return declared == Interaction.ANY_INPUT_KEY || declared.equals(inputKey, ignoreCase = true)
    }

    /**
     * 이 액션이 겨눈 것. 겨냥이 이름으로 남지 않는 액션은 null 이다([ActionTarget] 참고).
     *
     * `button_click` 의 인자는 런타임 instance id 라 그 자체로는 지도가 모르는 숫자다. 타임라인이
     * 든 `pulse` 표가 그것을 selector 로 바꿔 준다 — 그 표가 아직 그 오브젝트를 못 봤으면 null 이고,
     * 그때 조준을 지어내지 않는다.
     */
    private fun targetOf(timeline: ActionTimeline, action: ActionItemDto): ActionTarget? = when (action.method) {
        in CLICK_METHODS -> (action.params.firstOrNull() as? Number)?.let { timeline.controlOf(it.toLong()) }
        in KEY_METHODS -> (action.params.firstOrNull() as? String)
            ?.takeIf { it.isNotBlank() }
            ?.let { key -> timeline.scene?.let { ActionTarget.Key(it, key) } }

        else -> null
    }

    private companion object {
        /** 오브젝트 하나를 instance id 로 겨누는 메서드. */
        val CLICK_METHODS = setOf("button_click")

        /** 키 하나를 이름으로 겨누는 메서드. */
        val KEY_METHODS = setOf("key_click", "key_down", "key_up")

        /** `capability_observation.action_method` 의 폭. 프로토콜이 긴 이름을 내도 행이 거절되지 않게 자른다. */
        const val ACTION_METHOD_LIMIT = 32
    }
}

/**
 * 관측 한 행이 어느 런 · 어느 지도 · 어느 화면에 속하는지 (ARTEL-450).
 *
 * `ScreenObservationService` 가 `pulse` 마다 이미 풀어 둔 값들이다. 여기서 다시 풀면 같은 조회가
 * `pulse` 마다 두 벌이 되고, 두 벌이 다른 지도를 고르면 화면과 관측이 다른 지도에 앉는다.
 */
data class ActionObservationContext(
    val gameInstanceId: Long,
    val qaRunId: Long,
    val contentMapId: Long,

    /** 이 `pulse` 직전까지 굳어 있던 화면. 액션이 나갈 때 그 액션이 앉은 화면으로 집힌다. */
    val screenId: Long?,
)
