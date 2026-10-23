package kr.artel.orchestration.projectagent.service

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

/**
 * 대화마다 하나의 [Mutex]. tool result 저장 · 취소 · 삭제 · step 결과 저장이 같은 대화의 메시지 목록을 읽고
 * 쓰므로 한 프로세스 안에서 서로 겹치지 않게 한다. 대화 사이의 경합을 최종으로 막는 것은 status 를 조건부로 바꾸는
 * UPDATE 이고, 이 lock 은 그 위에서 같은 프로세스의 쓰기 순서를 맞출 뿐이다. step 호출(네트워크)을 기다리는
 * 동안에는 잡지 않는다.
 *
 * 쓰는 쪽과 기다리는 쪽을 세어 두었다가 아무도 없으면 항목을 지운다. 대화가 늘어도 map 이 따라 자라지 않는다.
 */
@Component
class ProjectAgentLocks {
    private class Entry {
        val mutex = Mutex()
        var users = 0
    }

    private val entries = ConcurrentHashMap<Long, Entry>()

    suspend fun <T> withLock(conversationId: Long, block: suspend () -> T): T {
        val entry = entries.compute(conversationId) { _, existing -> (existing ?: Entry()).also { it.users++ } }!!
        try {
            return entry.mutex.withLock { block() }
        } finally {
            entries.compute(conversationId) { _, existing -> existing?.also { it.users-- }?.takeIf { it.users > 0 } }
        }
    }
}
