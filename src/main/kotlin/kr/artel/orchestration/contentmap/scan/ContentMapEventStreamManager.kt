package kr.artel.orchestration.contentmap.scan

import kotlinx.coroutines.channels.BufferOverflow
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import org.slf4j.LoggerFactory
import org.springframework.http.codec.ServerSentEvent
import org.springframework.stereotype.Component
import java.util.Collections

/**
 * 빌드별 content map SSE 를 흘리는 자리. `TestScenarioStreamManager` 를 본뜨되 두 가지가 다르다.
 *
 * **key 가 `gameBuildId` 라 구독자가 여럿일 수 있다.** `TestScenarioStreamManager` 는 key 가
 * `userId:testScenarioId` 라 구독자가 하나뿐이고, 그래서 `onCompletion` 에서 map 항목을 지운다.
 * 여기서 그러면 구독자 하나가 나갈 때 같은 빌드를 보던 다른 사람의 스트림이 함께 죽는다. 대신
 * [ScanStatusRegistry] 처럼 **LRU 256 으로 상한만 둔다** — 항목을 지우지 않는다.
 *
 * **서버가 먼저 끊지 않는다.** `closed` sentinel 이 없다 — 스캔이 끝나도 스트림은 살아 있고, 화면
 * 단위 구독이지 작업 단위가 아니다. 끝은 client 가 `EventSource.close()` 하는 것뿐이고, 컨트롤러
 * 쪽에서 구독이 끝나면(연결이 끊기면) `MutableSharedFlow` 의 그 collector 만 조용히 멎는다.
 */
@Component
class ContentMapEventStreamManager {

    private val logger = LoggerFactory.getLogger(ContentMapEventStreamManager::class.java)

    /**
     * 삽입 순서로 늙은 것부터 버린다. [ScanStatusRegistry] 와 같은 상수·이유다 — 설정값으로 두지
     * 않는 것도, 상한을 아예 두지 않으면 오래 떠 있는 프로세스에서 이 맵만 끝없이 자라는 것도 같다.
     *
     * `LinkedHashMap` 은 스스로 동기화하지 않으므로 감싼다. 쓰는 쪽([emit], 여러 도메인 서비스)과
     * 읽는 쪽(구독) 이 서로 다른 스레드다.
     */
    private val streams: MutableMap<Long, MutableSharedFlow<ServerSentEvent<ContentMapStreamEvent>>> =
        Collections.synchronizedMap(
            object : LinkedHashMap<Long, MutableSharedFlow<ServerSentEvent<ContentMapStreamEvent>>>(
                INITIAL_CAPACITY, LOAD_FACTOR, false
            ) {
                override fun removeEldestEntry(
                    eldest: Map.Entry<Long, MutableSharedFlow<ServerSentEvent<ContentMapStreamEvent>>>
                ): Boolean = size > MAX_TRACKED_BUILDS
            }
        )

    /**
     * 이 빌드의 스트림을 구독한다. 스트림이 없으면 만든다.
     *
     * replay 가 없다(`MutableSharedFlow` 기본값 0) — 구독 시점 **이전**에 나간 이벤트는 새 구독자에게
     * 오지 않는다. "구독 직후 현재 상태를 한 번 보낸다"는 계약은 이 함수가 채우지 않는다 — 호출자가
     * 구독 전에 현재 상태를 직접 읽어 `snapshot` 이벤트로 먼저 흘려보낸다([ContentMapViewService.events]).
     */
    fun stream(gameBuildId: Long): Flow<ServerSentEvent<ContentMapStreamEvent>> = sinkFor(gameBuildId)

    /**
     * 이벤트를 이 빌드의 스트림으로 흘린다. `event:` 이름은 [ContentMapStreamEvent.type] 과 같다.
     *
     * 구독자가 없어도 버리지 않는다 — 다음 구독자가 올 때까지 `extraBufferCapacity` 안에서
     * 기다린다(`TestScenarioStreamManager` 와 달리 이쪽은 그 사이 이미 있던 구독자에게도 그대로
     * 나간다. 여럿이 같은 빌드를 보고 있는 것이 정상이다). 그 폭을 넘으면 오래된 것부터 버린다 —
     * SDK 가 한 번에 문서 수백 개를 올리면 화면은 가장 최근 진행만 봐도 충분하다.
     */
    fun emit(gameBuildId: Long, event: ContentMapStreamEvent) {
        val sink = sinkFor(gameBuildId)
        val sse = ServerSentEvent.builder(event).event(event.type).build()
        if (!sink.tryEmit(sse)) {
            logger.warn("content map SSE emit 실패 [gameBuildId={}, type={}]", gameBuildId, event.type)
        }
    }

    private fun sinkFor(gameBuildId: Long): MutableSharedFlow<ServerSentEvent<ContentMapStreamEvent>> =
        streams.computeIfAbsent(gameBuildId) {
            MutableSharedFlow(extraBufferCapacity = STREAM_BUFFER_CAPACITY, onBufferOverflow = BufferOverflow.DROP_OLDEST)
        }

    private companion object {
        const val MAX_TRACKED_BUILDS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
        const val STREAM_BUFFER_CAPACITY = 256
    }
}
