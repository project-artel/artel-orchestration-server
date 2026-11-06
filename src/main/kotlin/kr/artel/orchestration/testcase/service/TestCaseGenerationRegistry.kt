package kr.artel.orchestration.testcase.service

import kotlinx.coroutines.Job
import kr.artel.orchestration.testcase.generator.GenerationPhase
import org.springframework.stereotype.Component
import java.time.Instant
import java.util.Collections

/** 생성 하나의 끝 상태까지. */
enum class TestCaseGenerationState { RUNNING, SUCCEEDED, FAILED, CANCELLED }

/**
 * 빌드 하나의 **마지막** 지도 TC 생성 상태(ARTEL-1187).
 *
 * 메모리에만 산다. [kr.artel.orchestration.contentmap.scan.ScanStatus] 와 같은 이유다 — 답하는 질문이 "방금 누른
 * 생성이 어떻게 됐나" 이고, 서버가 다시 뜨면 도는 생성도 함께 사라지므로 옳은 답은 "다시 눌러라" 다. 만든 TC 는
 * DB 에 남는다.
 */
data class TestCaseGenerationStatus(
    val gameBuildId: Long,
    val state: TestCaseGenerationState,
    val phase: GenerationPhase,
    /** 지금 단계의 몇 개 중 몇 개. 분모가 없는 단계면 둘 다 null. */
    val done: Int? = null,
    val total: Int? = null,
    val startedAt: Instant,
    val updatedAt: Instant = startedAt,
    val finishedAt: Instant? = null,
    /** [TestCaseGenerationState.FAILED] 일 때 사람에게 보일 사유. */
    val error: String? = null,
)

/**
 * 빌드별로 도는 생성 하나를 쥔다. 세 곳이 쓴다 — 서비스가 자리를 잡고 진행을 적고 끝낸다, 취소 API 가 [cancel] 로
 * 끊는다, 화면 지도 SSE 스냅샷이 [find] 로 읽는다.
 *
 * **자리 잡기가 한 덩어리다.** 따로 보고 넣으면 거의 같은 때 들어온 두 요청이 둘 다 통과한다. 예전에는 둘째가
 * 프로젝트 잠금을 말없이 기다리다가, 덮어쓰기면 끝난 뒤 한 번 더 돌았다.
 */
@Component
class TestCaseGenerationRegistry {

    private class Entry(val status: TestCaseGenerationStatus, val job: Job?, val cancelRequested: Boolean = false)

    /** [kr.artel.orchestration.contentmap.scan.ScanStatusRegistry] 와 같은 상한 · 이유. */
    private val entries: MutableMap<Long, Entry> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, Entry>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, Entry>): Boolean = size > MAX_TRACKED_BUILDS
        }
    )

    fun find(gameBuildId: Long): TestCaseGenerationStatus? = entries[gameBuildId]?.status

    /** 이 빌드에 도는 생성이 없으면 [job] 으로 자리를 잡고 null, 있으면 그 상태를 돌려준다. */
    fun start(gameBuildId: Long, job: Job, now: Instant): TestCaseGenerationStatus? = synchronized(entries) {
        val current = entries[gameBuildId]
        if (current != null && current.status.state == TestCaseGenerationState.RUNNING && current.job?.isActive != false) {
            return current.status
        }
        entries[gameBuildId] = Entry(
            TestCaseGenerationStatus(
                gameBuildId = gameBuildId,
                state = TestCaseGenerationState.RUNNING,
                phase = GenerationPhase.READING_MAP,
                startedAt = now,
            ),
            job,
        )
        null
    }

    /** 도는 생성의 단계 · 진행을 적는다. 돌고 있지 않으면 null. */
    fun progress(gameBuildId: Long, phase: GenerationPhase, done: Int?, total: Int?, now: Instant): TestCaseGenerationStatus? =
        update(gameBuildId) { it.copy(phase = phase, done = done, total = total, updatedAt = now) }

    /** 끝 상태로 옮긴다. 돌고 있지 않으면 null. */
    fun finish(
        gameBuildId: Long,
        state: TestCaseGenerationState,
        now: Instant,
        error: String? = null,
    ): TestCaseGenerationStatus? = synchronized(entries) {
        val current = entries[gameBuildId] ?: return null
        if (current.status.state != TestCaseGenerationState.RUNNING) return null
        val finished = current.status.copy(state = state, updatedAt = now, finishedAt = now, error = error)
        entries[gameBuildId] = Entry(finished, null)
        finished
    }

    /** 도는 생성을 끊는다. 끊을 것이 있었으면 true. 끝 상태는 생성 쪽이 [finish] 로 적는다. */
    fun cancel(gameBuildId: Long): Boolean = synchronized(entries) {
        val current = entries[gameBuildId] ?: return false
        val job = current.job ?: return false
        if (current.status.state != TestCaseGenerationState.RUNNING || !job.isActive) return false
        entries[gameBuildId] = Entry(current.status, job, cancelRequested = true)
        job.cancel()
        true
    }

    /** 사용자가 [cancel] 로 끊었나. 끊긴 것이 사용자 때문인지, 요청 자체가 끊긴 것인지 가른다. */
    fun cancelRequested(gameBuildId: Long): Boolean = entries[gameBuildId]?.cancelRequested == true

    private fun update(gameBuildId: Long, change: (TestCaseGenerationStatus) -> TestCaseGenerationStatus): TestCaseGenerationStatus? =
        synchronized(entries) {
            val current = entries[gameBuildId] ?: return null
            if (current.status.state != TestCaseGenerationState.RUNNING) return null
            val next = change(current.status)
            entries[gameBuildId] = Entry(next, current.job, current.cancelRequested)
            next
        }

    private companion object {
        const val MAX_TRACKED_BUILDS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
