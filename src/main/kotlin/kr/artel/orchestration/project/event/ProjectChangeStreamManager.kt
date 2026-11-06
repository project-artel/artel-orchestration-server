package kr.artel.orchestration.project.event

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Service
import java.util.Collections

/**
 * 프로젝트 단위로 "이 데이터가 바뀌었다" 를 알리는 stream 관리자(ARTEL-1123).
 *
 * `DocumentEventStreamManager` 와 같은 구조다. key 는 `projectId` 이고 여러 사람이 같은 프로젝트를
 * 함께 구독한다. 구독이 끝나도 entry 를 지우지 않고 [MAX_TRACKED_PROJECTS] 개 LRU 로 크기를 막는다.
 *
 * **commit 뒤에 [notify] 한다.** 이벤트를 받은 home 이 곧바로 GET 을 보내므로, commit 전에 알리면
 * 바뀌기 전 데이터를 읽어 간다. `transactionalOperator.executeAndAwait { }` 블록 안이 아니라 블록이
 * 끝난 뒤에 부른다.
 *
 * 프로세스 안에서만 전달한다. 서버는 instance 하나로 배포되고, 다른 SSE stream 과 SDK WebSocket
 * session 도 같은 가정 위에 있다.
 */
@Service
class ProjectChangeStreamManager {

    private val logger = LoggerFactory.getLogger(ProjectChangeStreamManager::class.java)

    /** [notify] 와 [stream] 이 서로 다른 스레드에서 드나들므로 감싼다. */
    private val streams: MutableMap<Long, MutableSharedFlow<ProjectChangeTopic>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, MutableSharedFlow<ProjectChangeTopic>>(
                INITIAL_CAPACITY,
                LOAD_FACTOR,
                false
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Long, MutableSharedFlow<ProjectChangeTopic>>
                ): Boolean = size > MAX_TRACKED_PROJECTS
            }
        )

    /** projectId 의 변경 topic 을 구독한다. stream 이 없으면 만든다. */
    fun stream(projectId: Long): Flow<ProjectChangeTopic> =
        streams.computeIfAbsent(projectId) {
            MutableSharedFlow(extraBufferCapacity = BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    /**
     * projectId 를 구독 중인 client 에게 [topic] 이 바뀌었다고 알린다. 구독자가 없으면 버린다:
     * 다음 구독자는 연결 직후 `ready` 를 받고 전부 다시 읽는다.
     */
    fun notify(projectId: Long, topic: ProjectChangeTopic) {
        val sink = streams[projectId] ?: return
        if (!sink.tryEmit(topic)) {
            logger.warn("프로젝트 변경 이벤트 emit 실패 [projectId: $projectId, topic: $topic]")
        }
    }

    private companion object {
        const val MAX_TRACKED_PROJECTS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
        const val BUFFER_CAPACITY = 64
    }
}
