package kr.artel.orchestration.project.service

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kr.artel.orchestration.project.dto.DocumentParseStatusResponse
import kr.artel.orchestration.project.dto.DocumentStreamEvent
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test

/**
 * [DocumentEventStreamManager] 단위 테스트. Spring 컨텍스트가 필요 없는 순수 클래스라
 * `@SpringBootTest` 없이 직접 인스턴스를 만들어 검증한다.
 */
class DocumentEventStreamManagerTest {

    @Test
    fun `emits an event using its type as the SSE event name`(): Unit = runBlocking {
        val manager = DocumentEventStreamManager()
        val flow = manager.stream(7L)

        // UNDISPATCHED로 시작해 collect가 emit보다 먼저 SharedFlow에 붙게 한다(그러지 않으면
        // emit이 구독자 없이 지나가 드롭될 수 있다).
        val eventDeferred = async(start = CoroutineStart.UNDISPATCHED) { flow.first() }
        manager.emit(
            7L,
            DocumentStreamEvent(
                type = "document",
                document = DocumentParseStatusResponse(documentId = "1", parseStatus = "EXTRACTING", stale = false)
            )
        )

        val sse = withTimeout(2_000) { eventDeferred.await() }
        assertThat(sse.event()).isEqualTo("document")
        assertThat(sse.data()?.document?.documentId).isEqualTo("1")
    }

    @Test
    fun `drops an event silently when nobody has ever subscribed to that projectId`() {
        val manager = DocumentEventStreamManager()

        // stream()을 부른 적 없는 projectId — 예외 없이 조용히 드롭돼야 한다.
        manager.emit(999L, DocumentStreamEvent(type = "document"))
    }

    @Test
    fun `keeps the projectId entry after a subscriber's collection ends`(): Unit = runBlocking {
        val manager = DocumentEventStreamManager()
        val flow = manager.stream(42L)

        // 구독자 하나가 collect를 시작했다가 취소로 끝낸다 — client의 EventSource.close()를 흉내낸다.
        withTimeoutOrNull(100) { flow.first() }

        // 지워지지 않았다: 같은 projectId를 다시 구독하면 같은 sink를 그대로 받는다(다른 구독자가
        // 볼 수 있는 stream이 끊기지 않는다는 뜻이다).
        assertThat(manager.stream(42L)).isSameAs(flow)
    }

    @Test
    fun `evicts the oldest projectId once more than 256 are tracked`() {
        val manager = DocumentEventStreamManager()
        val firstFlow = manager.stream(1L)
        (2L..256L).forEach { manager.stream(it) }

        // 아직 256개뿐이라 가장 먼저 넣은 것도 살아 있다.
        assertThat(manager.stream(1L)).isSameAs(firstFlow)

        // 257번째 projectId가 들어오며 상한을 넘어, 가장 먼저 넣은 것이 밀려난다.
        manager.stream(257L)

        assertThat(manager.stream(1L)).isNotSameAs(firstFlow)
    }
}
