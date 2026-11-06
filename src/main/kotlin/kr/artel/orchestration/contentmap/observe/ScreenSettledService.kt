package kr.artel.orchestration.contentmap.observe

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import kr.artel.orchestration.qa.service.QaScreenFramePort
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.UUID

/**
 * 관측이 화면을 확정할 때마다 그 화면을 agent 에게 알린다 (ARTEL-668).
 *
 * ```
 * pulse → ScreenFold.confirm → 화면 행이 바뀌었나 → SCREEN_SETTLED
 * ```
 *
 * ## 왜 제안으로는 안 되나
 *
 * 화면 판정을 싣고 가던 유일한 프레임이 `SCREEN_SELECTOR_PROPOSAL` 이었고, 그것은
 * `(scene, selector)` 마다 평생 한 번만 나간다(`uk_screen_selector_proposal`). 그래서 **이미 한 번
 * 플레이한 빌드에서는 제안이 한 장도 안 나가고**, agent 는 런 내내 지도가 자기를 어느 화면이라고
 * 부르는지 못 본다. 그 상태에서는 목록을 고치는 tool 둘(ARTEL-657)을 부를 계기 자체가 없다 —
 * 그 계기가 "화면이 눈에 띄게 달라졌는데 지도가 같은 화면이라고 한다" 이기 때문이다.
 *
 * ## 화면을 못 가른 것도 알린다
 *
 * 목록이 비면 씬 전체가 화면 하나이고 `discriminator` 가 빈 배열이다. 그것을 오류로 보고 안 보내면
 * 안 된다 — ARTEL-654 가 그것을 옳은 동작으로 정했고, **agent 가 목록을 고쳐야 한다는 것을 알아챌
 * 유일한 신호가 바로 그 빈 배열**이다. 있는 그대로 싣는다.
 *
 * ## 런을 세우지 않는다
 *
 * 답을 기다리는 자리가 없고, 보낼 곳이 없거나 보내다 실패해도 화면 기록은 그대로 돈다. `pulse` 는
 * 관측 채널이지 런의 전제가 아니라는 `ScreenObservationService` 의 판단이 여기도 그대로다.
 */
@Service
class ScreenSettledService(
    private val screenRefs: ScreenRefs,
    private val agent: QaScreenFramePort,
    private val objectMapper: ObjectMapper,
) {

    private val logger = LoggerFactory.getLogger(ScreenSettledService::class.java)

    /**
     * 방금 굳은 화면을 알린다. **실패를 삼킨다.**
     *
     * 부르는 쪽이 이미 "화면이 바뀌었나" 를 판정하고 부른다. 그 판정을 여기서 한 번 더 하지 않는
     * 이유는 바뀌었는지를 아는 것이 `ScreenFold` 뿐이기 때문이다 — 여기서 다시 재려면 직전에 무엇을
     * 보냈는지를 이 서비스가 인스턴스별로 들어야 하고, 그것이 `fold` 상태의 두 번째 벌이 된다.
     */
    suspend fun announce(
        gameInstanceId: Long,
        sceneId: Long,
        sceneName: String,
        previousScreenId: Long?,
        currentScreenId: Long,
    ) {
        try {
            send(gameInstanceId, sceneId, sceneName, previousScreenId, currentScreenId)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "확정한 화면을 알리지 못했다 [gameInstanceId={}, scene={}, screenId={}]: {}",
                gameInstanceId, sceneName, currentScreenId, failure.message, failure,
            )
        }
    }

    private suspend fun send(
        gameInstanceId: Long,
        sceneId: Long,
        sceneName: String,
        previousScreenId: Long?,
        currentScreenId: Long,
    ) {
        // 행을 못 읽으면 보낼 것이 없다. 화면 id 만 실은 통보는 받는 쪽이 무엇으로 가른 화면인지
        // 모른 채 번호만 읽는 것이라, 목록을 고칠 판단에 쓰이지 않는다.
        val current = screenRefs.of(currentScreenId) ?: return
        val payload = ScreenSettledPayload(
            scene = ScreenSelectorSceneRef(sceneId.toString(), sceneName),
            previousScreen = screenRefs.of(previousScreenId),
            currentScreen = current,
        )
        agent.sendScreenSettled(
            gameInstanceId = gameInstanceId,
            messageId = UUID.randomUUID().toString(),
            summary = summaryOf(sceneName, current),
            payload = objectMapper.valueToTree(payload),
        )
    }

    /**
     * QA 타임라인의 한 줄. 화면을 무엇으로 갈랐는지까지 적는다.
     *
     * 가른 것이 없다는 것을 그 줄에서 바로 보여야 한다. 사람이 타임라인을 훑다가 "이 씬은 내내
     * 화면 하나였네" 를 알아채는 자리가 여기 말고 없다.
     */
    private fun summaryOf(sceneName: String, current: ScreenSelectorScreenRef): String {
        val name = current.name?.let { " ($it)" } ?: ""
        val told = if (current.discriminator.isEmpty()) {
            "nothing on this scene's selector list showed up"
        } else {
            "${current.discriminator.size} selector(s) told it apart"
        }
        return "Settled on screen ${current.screenId}$name of $sceneName; $told."
    }
}
