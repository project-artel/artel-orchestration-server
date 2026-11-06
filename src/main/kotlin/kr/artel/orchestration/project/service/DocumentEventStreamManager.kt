package kr.artel.orchestration.project.service

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kr.artel.orchestration.project.dto.DocumentStreamEvent
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Service
import java.util.Collections

/**
 * 프로젝트 문서 추출 상태 SSE를 관리하는 push 방식 관리자(ARTEL-760, `TestScenarioStreamManager`를
 * 본뜬다).
 *
 * key는 `projectId`다. `TestScenarioStreamManager`의 key(`userId:testScenarioId`)와 달리
 * **사용자별이 아니라 프로젝트별**이라 여러 사람이 같은 프로젝트를 동시에 구독할 수 있다.
 * 그래서 선례처럼 구독 종료(`onCompletion`) 시 entry를 지우지 않는다 — 지우면 아직 보고 있는
 * 다른 구독자의 stream까지 끊긴다. 대신 `ScanStatusRegistry`와 같은 방식으로
 * [MAX_TRACKED_PROJECTS] LRU를 걸어 오래 떠 있는 프로세스에서 맵이 끝없이 자라지 않게 막는다.
 *
 * 이 stream은 서버가 먼저 끊지 않는다(계약) — `TestScenarioStreamManager`의 `closed` sentinel /
 * `complete()`에 대응하는 것이 없다. 끝은 client의 `EventSource.close()`뿐이다.
 */
@Service
class DocumentEventStreamManager {

    private val logger = LoggerFactory.getLogger(DocumentEventStreamManager::class.java)

    /**
     * `LinkedHashMap`은 스스로 동기화하지 않으므로 감싼다. `emit`(추출 코디네이터 쪽 스레드)과
     * `stream`(SSE 구독 쪽 스레드)이 서로 다른 스레드에서 드나든다.
     */
    private val streams: MutableMap<Long, MutableSharedFlow<ServerSentEvent<DocumentStreamEvent>>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, MutableSharedFlow<ServerSentEvent<DocumentStreamEvent>>>(
                INITIAL_CAPACITY,
                LOAD_FACTOR,
                false
            ) {
                override fun removeEldestEntry(
                    eldest: MutableMap.MutableEntry<Long, MutableSharedFlow<ServerSentEvent<DocumentStreamEvent>>>
                ): Boolean = size > MAX_TRACKED_PROJECTS
            }
        )

    /** 주어진 projectId의 SSE 스트림을 구독한다. 스트림이 없으면 생성한다. */
    fun stream(projectId: Long): Flow<ServerSentEvent<DocumentStreamEvent>> =
        streams.computeIfAbsent(projectId) {
            MutableSharedFlow(extraBufferCapacity = 256, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    /**
     * 문서 상태 변화를 해당 projectId의 SSE 스트림으로 전달한다. `event.type`을 SSE 이벤트명으로
     * 쓴다. 활성 구독자가 없으면 조용히 드롭한다(경고 로그만 남긴다) — 아무도 보고 있지 않은
     * 프로젝트의 상태 변화까지 붙잡아 둘 이유가 없다(snapshot이 다음 구독자에게 최신 상태를 준다).
     */
    fun emit(projectId: Long, event: DocumentStreamEvent) {
        val sink = streams[projectId]
        if (sink == null) {
            logger.debug("활성 SSE 스트림이 없어 이벤트 드롭 [projectId: $projectId, type: ${event.type}]")
            return
        }
        val sse = ServerSentEvent.builder(event).event(event.type).build()
        if (!sink.tryEmit(sse)) {
            logger.warn("SSE 이벤트 emit 실패 [projectId: $projectId, type: ${event.type}]")
        }
    }

    private companion object {
        const val MAX_TRACKED_PROJECTS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
