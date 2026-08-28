package kr.artel.orchestration.contentmap.observe

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 게임 인스턴스별 [ScreenFold] 상태를 든다 (ARTEL-453 에서 옮겨 옴, ARTEL-655).
 *
 * `ScreenObservationService` 안의 맵이었다. 소비자가 둘이 되어 밖으로 냈다 — `pulse` 를 접는 쪽과,
 * 목록을 고치는 프레임이 "그 씬에서 실제로 본 selector 인가" 를 검증하는 쪽
 * ([ScreenSelectorProposalService]). 두 서비스가 서로를 주입하면 빈 순환이 되므로 상태를 셋째
 * 자리에 둔다.
 *
 * **락이 없다.** `SdkWebSocketHandler` 가 한 세션의 프레임을 `concatMap` 으로 하나씩 처리하고, 한
 * 게임 인스턴스의 `pulse` 는 한 세션으로만 온다. 그 보장이 깨지면 같은 `discriminator` 가 두 번
 * 굳어 `observed_count` 가 두 번 오르는데, 행이 갈리지는 않는다 — `uk_screen_discriminator` 가
 * 막는다.
 *
 * 프로세스 메모리라 재시작하면 사라진다. 다음 전량 `pulse` 가 복구한다.
 */
@Component
class ScreenFoldRegistry {

    private val logger = LoggerFactory.getLogger(ScreenFoldRegistry::class.java)

    private val folds = ConcurrentHashMap<Long, ScreenFold>()

    /**
     * 이 인스턴스의 `fold`. 상한을 넘으면 **통째로 비우고** 다시 쌓는다.
     *
     * 다음 전량 `pulse` 가 각자 복구하므로 자기 치유되고, 상한 없이 새는 것보다 낫다. 여기 걸린다는
     * 것은 `pulse` 를 흘리는 인스턴스가 수백이라는 뜻이고, 그때는 메모리보다 먼저 볼 것이 있다.
     */
    fun of(gameInstanceId: Long): ScreenFold {
        if (folds.size >= MAX_TRACKED_INSTANCES && !folds.containsKey(gameInstanceId)) {
            logger.warn("fold 상태가 {}개를 넘어 비운다", MAX_TRACKED_INSTANCES)
            folds.clear()
        }
        return folds.getOrPut(gameInstanceId) { ScreenFold() }
    }

    /** 런이 끝난 인스턴스의 상태를 버린다. 스캔 순회가 흘리는 `pulse` 가 다음 런을 오염시키지 않게. */
    fun forget(gameInstanceId: Long) {
        folds.remove(gameInstanceId)
    }

    /** 이 인스턴스가 [scene] 에서 지금까지 본 selector. 씬이 다르면 빈 집합이다. */
    fun observedSelectors(gameInstanceId: Long, scene: String): Set<String> {
        val fold = folds[gameInstanceId] ?: return emptySet()
        if (fold.scene != scene) return emptySet()
        return fold.observedSelectors()
    }

    /**
     * 이 씬에서 굳어 있던 화면을 전부 잊는다. **접기가 그 행을 지웠을 수 있어서다** (ARTEL-655).
     *
     * 인스턴스가 아니라 씬으로 도는 이유는 접기의 단위가 씬이기 때문이다. 같은 씬을 여러
     * 인스턴스가 동시에 보고 있으면 그 전부가 지워진 id 를 들고 있을 수 있다.
     */
    fun forgetSettledIn(sceneId: Long) {
        for (fold in folds.values) {
            if (fold.settledSceneId == sceneId) fold.forgetSettled()
        }
    }

    companion object {
        /** `fold` 상태를 들고 있을 인스턴스 수의 상한. 넘으면 통째로 비우고 다시 쌓는다. */
        const val MAX_TRACKED_INSTANCES = 256
    }
}
