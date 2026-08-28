package kr.artel.orchestration.contentmap.observe

import kr.artel.orchestration.contentmap.config.ActionObservationProperties
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 게임 인스턴스별 [ActionTimeline] 상태를 든다 (ARTEL-450).
 *
 * [ScreenFoldRegistry] 와 같은 모양이고 같은 이유로 밖에 있다 — 소비자가 셋이다. 액션이 나가는
 * 쪽(agent 인바운드) · 결과가 오는 쪽(SDK 인바운드) · `pulse` 를 접는 쪽. 셋이 서로를 주입하면 빈
 * 순환이 되므로 상태를 넷째 자리에 둔다.
 *
 * **`fold` 와 합치지 않는다.** 둘은 수명이 다르다. `ScreenFold` 는 화면 판정을 위해 `씬/selector`
 * 를 들고, 이쪽은 조준을 위해 instance id 를 든다 — 그 번호는 오브젝트가 다시 스폰되면 바뀌고
 * 프로세스를 넘지 못한다. 한 클래스에 넣으면 화면 판정이 조준용 상태의 수명에 묶인다.
 *
 * 프로세스 메모리라 재시작하면 사라진다. 그때 잃는 것은 아직 안 닫힌 창 하나이고, 다음 전량
 * `pulse` 가 나머지를 복구한다.
 */
@Component
class ActionTimelineRegistry(private val properties: ActionObservationProperties) {

    private val logger = LoggerFactory.getLogger(ActionTimelineRegistry::class.java)

    private val timelines = ConcurrentHashMap<Long, ActionTimeline>()

    /**
     * 이 인스턴스의 타임라인. 상한을 넘으면 **통째로 비우고** 다시 쌓는다.
     *
     * [ScreenFoldRegistry.of] 와 같은 판단이다. 여기 걸린다는 것은 `pulse` 를 흘리는 인스턴스가
     * 수백이라는 뜻이고, 그때는 메모리보다 먼저 볼 것이 있다.
     */
    fun of(gameInstanceId: Long): ActionTimeline {
        if (timelines.size >= MAX_TRACKED_INSTANCES && !timelines.containsKey(gameInstanceId)) {
            logger.warn("액션 타임라인이 {}개를 넘어 비운다", MAX_TRACKED_INSTANCES)
            timelines.clear()
        }
        return timelines.getOrPut(gameInstanceId) { ActionTimeline(properties) }
    }

    /** 이 인스턴스의 타임라인. 없으면 만들지 않는다 — 액션만 나가고 `pulse` 는 안 오는 경로용이다. */
    fun find(gameInstanceId: Long): ActionTimeline? = timelines[gameInstanceId]

    /**
     * 런이 끝난 인스턴스의 상태를 버린다.
     *
     * 스캔 순회가 흘리는 `pulse` 가 다음 런의 첫 관측을 오염시키지 않게 — `ScreenFoldRegistry` 가
     * 같은 자리에서 `fold` 를 버리는 것과 같은 이유다. 아직 안 닫힌 창도 함께 사라진다.
     */
    fun forget(gameInstanceId: Long) {
        timelines.remove(gameInstanceId)
    }

    companion object {
        /** 타임라인을 들고 있을 인스턴스 수의 상한. 넘으면 통째로 비우고 다시 쌓는다. */
        const val MAX_TRACKED_INSTANCES = 256
    }
}
