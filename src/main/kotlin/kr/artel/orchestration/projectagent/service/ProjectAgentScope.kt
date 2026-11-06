package kr.artel.orchestration.projectagent.service

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.springframework.beans.factory.DisposableBean
import org.springframework.stereotype.Component

/**
 * step 호출이 도는 coroutine scope. 요청 하나가 끝나도 step 호출은 이어져야 하므로 요청 scope 와 따로 둔다.
 * 종료하면 도는 step 이 취소되고, 그 대화는 다음 기동 때 복구(RUNNING → ERROR)된다.
 *
 * `CoroutineScope` 를 직접 구현하지 않는 것은 다른 bean(`trackerSyncScope`)이 타입으로 scope 를 주입받기 때문이다.
 */
@Component
class ProjectAgentScope : DisposableBean {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun launch(start: CoroutineStart, block: suspend () -> Unit): Job = scope.launch(start = start) { block() }

    override fun destroy() {
        scope.cancel()
    }
}
