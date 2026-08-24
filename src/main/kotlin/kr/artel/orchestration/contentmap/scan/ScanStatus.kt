package kr.artel.orchestration.contentmap.scan

import java.time.Instant

/**
 * 한 번의 원격 스캔이 지나가는 세 자리.
 *
 * 화면이 기다리는 것이 이 값이다 — 버튼을 누르면 [REQUESTED] 를 받고, 조회를 되풀이하며 그것이
 * [SUCCEEDED] 나 [FAILED] 로 움직이기를 본다. 이 어휘가 없으면 화면은 "요청은 갔다"까지만 알고
 * 끝나고, 눌렀는데 아무 일도 안 일어난 것과 스캔이 깨진 것을 구분할 수 없다.
 */
enum class ScanState {
    /** 액션을 보낼 줄에 세웠다. 아직 게임이 답하지 않았다. */
    REQUESTED,

    /** 게임이 스캔을 마쳤고 그 문서가 씬 명세로 앉았다. */
    SUCCEEDED,

    /** 스캔이 깨졌거나(게임이 그렇게 답했다) 올라온 문서를 앉히지 못했다. */
    FAILED,
}

/**
 * 빌드 하나에 대한 **마지막** 스캔의 상태.
 *
 * 이 값이 프로세스 메모리에만 사는 이유는 적을 칸이 없기 때문이다. 스캔 자체가 실패하면 SDK 는
 * 아무것도 올리지 않아 `content_map_document` 행이 생기지 않고, 첫 스캔이라면 `content_map` 행조차
 * 없다. `ingest_failed_at` 에 적는 것은 거짓말이 된다 — 그 칸은 "적재가 깨졌다"는 뜻인데 적재는
 * 시작도 안 했다.
 *
 * 재시작하면 사라진다. 그것이 여기서는 맞는 성질이다 — 이 값이 답하는 질문은 "방금 내가 누른
 * 버튼이 어떻게 됐나"이고, 서버가 다시 뜬 뒤의 옳은 답은 "다시 눌러라"다. 내구성이 필요한 것,
 * 즉 **어떤 문서가 왜 못 앉았나**는 `ingest_failed_at` · `ingest_error` 에 그대로 남는다.
 */
data class ScanStatus(
    val gameBuildId: Long,
    /** 어느 인스턴스에 보냈나. 여러 대가 같은 빌드를 물고 있을 때 화면이 이것을 보여 준다. */
    val gameInstanceId: Long,
    /** id 만으로는 화면이 어느 게임인지 말할 수 없다. 고른 순간의 이름을 그대로 들고 간다. */
    val gameInstanceName: String,
    val state: ScanState,
    val requestedAt: Instant,
    val finishedAt: Instant? = null,
    /**
     * [ScanState.SUCCEEDED] 일 때 이번 결과가 앉힌 문서 수.
     *
     * 0 일 수 있다. 게임이 성공이라 답했는데 앉힐 문서가 없다는 뜻이고, 등록이 아직 안 끝났을 때
     * 그렇게 된다. 수를 싣지 않으면 화면은 그 경우와 정상 성공을 구분할 수 없다.
     */
    val ingestedDocuments: Int? = null,
    /** [ScanState.FAILED] 일 때 사람에게 보여 줄 사유 한 줄. */
    val error: String? = null,
)
