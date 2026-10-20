package kr.artel.orchestration.projectagent.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 대화마다 하나의 [Mutex]. tool result 저장 · 취소 · step 결과 저장이 같은 대화의 메시지 목록을 읽고 쓰므로
 * 서로 겹치지 않게 한다. step 호출(네트워크)을 기다리는 동안에는 잡지 않는다.
 */
@Component
class ProjectAgentLocks {
    private val mutexes = ConcurrentHashMap<Long, Mutex>()

    suspend fun <T> withLock(conversationId: Long, block: suspend () -> T): T =
        mutexes.computeIfAbsent(conversationId) { Mutex() }.withLock { block() }

    fun forget(conversationId: Long) {
        mutexes.remove(conversationId)
    }
}
