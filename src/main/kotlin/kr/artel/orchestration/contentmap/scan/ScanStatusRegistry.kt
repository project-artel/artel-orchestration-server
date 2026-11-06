package kr.artel.orchestration.contentmap.scan

import org.springframework.stereotype.Component
import java.util.Collections

/**
 * 빌드별 **마지막** 스캔 상태를 들고 있는 자리.
 *
 * 네 곳이 쓴다 — [ContentMapScanService] 가 보내기 전에 [ScanState.REQUESTED] 자리를 잡고,
 * [ScanProgressRecorder] 가 게임이 알려 준 진행을 적고, [ScanResultRouter] 가 결과를 받아 단계를 옮기고 끝
 * 상태로 바꾸고, 조회 API 가 읽어 `lastScan` 으로 낸다.
 *
 * 왜 DB 가 아닌지는 [ScanStatus] 의 KDoc 에 있다.
 *
 * 빌드 하나당 행 하나만 남긴다. 같은 빌드를 다시 스캔하면 앞 결과는 덮인다 — 답해야 할 질문이
 * "마지막에 어떻게 됐나" 하나뿐이라, 이력을 쌓으면 아무도 안 읽는 것이 자란다.
 */
@Component
class ScanStatusRegistry {

    /**
     * 삽입 순서로 늙은 것부터 버린다.
     *
     * [MAX_TRACKED_BUILDS] 를 설정값이 아니라 상수로 둔 이유: 한 서버가 동시에 이만큼의 빌드를
     * 스캔 중인 상황은 이 기능의 모양을 이미 벗어났고, 운영 중에 누가 다시 고를 값이 아니다.
     * 상한을 아예 두지 않으면 오래 떠 있는 프로세스에서 이 맵만 끝없이 자란다.
     *
     * `LinkedHashMap` 은 스스로 동기화하지 않으므로 감싼다. 읽는 쪽(조회 API)과 쓰는 쪽
     * (WebSocket 프레임 처리)이 서로 다른 스레드다.
     */
    private val statuses: MutableMap<Long, ScanStatus> = Collections.synchronizedMap(
        object : LinkedHashMap<Long, ScanStatus>(INITIAL_CAPACITY, LOAD_FACTOR, false) {
            override fun removeEldestEntry(eldest: Map.Entry<Long, ScanStatus>): Boolean =
                size > MAX_TRACKED_BUILDS
        }
    )

    fun put(status: ScanStatus) {
        statuses[status.gameBuildId] = status
    }

    /**
     * 이 빌드에 돌고 있는 스캔이 없으면 [status] 로 자리를 잡고 null 을, 있으면 그 스캔을 돌려준다(ARTEL-1178).
     *
     * **보기와 넣기가 한 덩어리여야 한다.** 따로 하면 거의 같은 때 들어온 두 요청이 둘 다 "없다" 를 보고 둘 다
     * 게임에 스캔을 보낸다 — 버튼을 빠르게 두 번 누르거나 두 사람이 같은 빌드를 볼 때 실제로 그랬다. 그래서
     * 게임에 보내기 **전에** 여기서 자리를 잡는다.
     *
     * [stillRunning] 이 false 인 [ScanState.REQUESTED] 는 없는 것으로 친다. 게임이 끊겼거나 오래 소식이 없는
     * 스캔이 버튼을 영원히 막지 않게 하려는 것이다.
     */
    fun reserve(status: ScanStatus, stillRunning: (ScanStatus) -> Boolean): ScanStatus? =
        synchronized(statuses) {
            val current = statuses[status.gameBuildId]
            if (current != null && current.state == ScanState.REQUESTED && stillRunning(current)) {
                current
            } else {
                statuses[status.gameBuildId] = status
                null
            }
        }

    /** [reserve] 로 잡은 자리를 돌려놓는다. 게임에 보내지 못했을 때만 쓴다. 그사이 다른 것이 들어섰으면 그대로 둔다. */
    fun release(status: ScanStatus) {
        statuses.remove(status.gameBuildId, status)
    }

    /**
     * 돌고 있는 스캔의 단계 · 진행을 적는다. [ScanState.REQUESTED] 가 아니면 아무것도 안 하고 null 이다.
     *
     * 끝난 스캔을 되살리지 않으려는 것이다 — 결과 뒤에 늦게 닿은 진행 프레임이 SUCCEEDED 를 덮으면 안 된다.
     */
    fun advance(gameBuildId: Long, change: (ScanStatus) -> ScanStatus?): ScanStatus? {
        var advanced: ScanStatus? = null
        statuses.computeIfPresent(gameBuildId) { _, current ->
            if (current.state != ScanState.REQUESTED) return@computeIfPresent current
            change(current)?.also { advanced = it } ?: current
        }
        return advanced
    }

    fun find(gameBuildId: Long): ScanStatus? = statuses[gameBuildId]

    /**
     * 결과가 왔을 때 상태를 끝으로 옮긴다. **[ScanState.REQUESTED] 인 행이 없으면 아무것도 안 한다.**
     *
     * 그 경우는 우리가 보낸 적 없는 스캔의 결과라는 뜻이다 — 서버가 재시작했거나, 게임이 스스로
     * 스캔을 돌렸을 때 그렇다. 없는 자리에 끝 상태를 만들어 두면 화면이 누른 적 없는 스캔의
     * 결과를 보게 된다.
     */
    fun complete(gameBuildId: Long, finish: (ScanStatus) -> ScanStatus): ScanStatus? =
        statuses.computeIfPresent(gameBuildId) { _, current ->
            if (current.state == ScanState.REQUESTED) finish(current) else current
        }

    /**
     * 이 인스턴스에 보낸, 아직 게임 쪽 단계(씬 스캔 · 업로드)에 있는 스캔을 모두 끝으로 옮긴다. 옮긴 것을 돌려준다.
     *
     * 게임이 끊기면 결과는 영영 오지 않는다. 서버 쪽 단계(적재 · TC 생성)는 이미 결과를 받은 뒤라 끊겨도 끝까지 간다.
     */
    fun completeGameSideOn(gameInstanceId: Long, finish: (ScanStatus) -> ScanStatus): List<ScanStatus> =
        synchronized(statuses) {
            statuses.values
                .filter {
                    it.gameInstanceId == gameInstanceId &&
                        it.state == ScanState.REQUESTED &&
                        it.phase in GAME_SIDE_PHASES
                }
                .map { finish(it).also { finished -> statuses[finished.gameBuildId] = finished } }
        }

    private companion object {
        val GAME_SIDE_PHASES = setOf(ScanPhase.SCANNING, ScanPhase.UPLOADING)

        const val MAX_TRACKED_BUILDS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
