package kr.artel.orchestration.qa.state

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.CancellationException
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.time.Clock
import java.util.concurrent.ConcurrentHashMap

/**
 * 게임 인스턴스별 [StateSet] 을 든다.
 *
 * QA try 가 아니라 게임 인스턴스로 든다. 게임 상태는 try 가 바뀐다고 처음부터 다시 시작하지 않고, try 사이에
 * 도착한 델타를 놓치면 다음 try 가 틀린 상태를 받는다. 그래서 진행 중인 try 가 없어도 모든 `pulse` 를 적용한다.
 *
 * 프로세스 메모리라 재시작하면 사라진다. 다음 whole `pulse` 가 복구한다. 그 전까지는 `AWAITING_WHOLE` 이다.
 */
@Component
class StateSetRegistry(
    private val objectMapper: ObjectMapper,
    private val clock: Clock,
    private val resync: StateResyncRequester,
) {

    private val logger = LoggerFactory.getLogger(StateSetRegistry::class.java)

    private val states = ConcurrentHashMap<Long, StateSet>()

    /**
     * `pulse` 하나를 적용한다. 실패는 삼킨다 — 상태를 못 합쳤다고 Agent 중계가 끊기면 QA 가 통째로 눈을 잃는다.
     *
     * [connectionId] 는 SDK WebSocket 세션 id 다. SDK 가 `stream` 을 보내지 않으면 이것이 바뀔 때 새 stream 이다.
     * `seq` 에 빈자리가 보이면 SDK 에 whole 을 청한다(ARTEL-1300).
     */
    suspend fun observe(gameInstanceId: Long, connectionId: String, payloadText: String) {
        val outcome = try {
            val pulse = objectMapper.readValue(payloadText, StatePulse::class.java)
            stateOf(gameInstanceId).observe(connectionId, pulse, clock.instant())
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (failure: Exception) {
            logger.warn(
                "pulse 를 STATE_SET 에 적용하지 못했다 [gameInstanceId={}]: {}",
                gameInstanceId, failure.message, failure,
            )
            return
        }
        if (outcome == ObserveOutcome.GAP_DETECTED) resync.request(gameInstanceId)
    }

    fun snapshot(gameInstanceId: Long, spec: ObservationSpec): StateSnapshot =
        states[gameInstanceId]?.snapshot(spec) ?: StateSnapshot.empty(gameInstanceId)

    /** 상한을 넘으면 통째로 비운다. 각 인스턴스의 다음 whole 이 복구한다. */
    private fun stateOf(gameInstanceId: Long): StateSet {
        if (states.size >= MAX_TRACKED_INSTANCES && !states.containsKey(gameInstanceId)) {
            logger.warn("STATE_SET 이 {}개를 넘어 비운다", MAX_TRACKED_INSTANCES)
            states.clear()
        }
        return states.getOrPut(gameInstanceId) { StateSet(gameInstanceId) }
    }

    companion object {
        const val MAX_TRACKED_INSTANCES = 256
    }
}
