package kr.artel.orchestration.contentmap.scan

import org.springframework.stereotype.Component
import java.util.Collections

/**
 * 빌드별 **마지막** 스캔 상태를 들고 있는 자리.
 *
 * 세 곳이 쓴다 — [ContentMapScanService] 가 보내면서 [ScanState.REQUESTED] 를 넣고,
 * [ScanResultRouter] 가 결과를 받아 끝 상태로 바꾸고, 조회 API 가 읽어 `lastScan` 으로 낸다.
 * 그 셋이 이 클래스의 존재 이유 전부이고, 더 담을 계획은 없다.
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

    private companion object {
        const val MAX_TRACKED_BUILDS = 256
        const val INITIAL_CAPACITY = 32
        const val LOAD_FACTOR = 0.75f
    }
}
