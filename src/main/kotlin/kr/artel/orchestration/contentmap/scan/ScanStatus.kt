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
    /**
     * 사람이 끊어서 [ScanState.FAILED] 가 된 것인가(ARTEL-1183).
     *
     * 상태를 하나 더 두는 대신 칸으로 둔다. 취소는 끝나는 방식이 실패와 같다 — 문서가 안 앉고 다음 요청이 풀린다.
     * 다른 것은 화면이 할 말뿐이라 그 한 가지만 적는다. [ScanState] 에 값을 더하면 그것을 읽는 모든 자리가
     * "성공도 실패도 아닌 것" 을 새로 다뤄야 한다.
     */
    val cancelled: Boolean = false,
    /** 스캔이 끝나면 TC 도 만들라는 요청과 그 결과(ARTEL-1040). 그냥 스캔이면 null 이다. */
    val testCases: ScanTestCases? = null,
    /**
     * [ScanState.REQUESTED] 동안 지금 어느 단계인가(ARTEL-1178). 끝난 뒤에는 마지막으로 지난 단계가 남는다.
     *
     * 앞 둘은 게임이 `EVIDENCE_SCAN_PROGRESS` 로 알려 주고, 뒤 둘은 서버가 스스로 옮긴다. 진행을 안 보내는 옛
     * SDK 면 결과가 올 때까지 [ScanPhase.SCANNING] 에 머문다.
     */
    val phase: ScanPhase = ScanPhase.SCANNING,
    /** 지금 단계의 몇 개 중 몇 개. 게임이 알려 준 적이 없거나 서버 쪽 단계면 null 이다. */
    val progress: ScanProgress? = null,
    /**
     * 마지막으로 움직인 시각. 요청 · 진행 · 단계 · 끝이 다 이것을 민다.
     *
     * 중복 요청을 거절할 때 "아직 돌고 있나" 를 이것으로 가른다 — 결과가 영영 안 오는 스캔이 버튼을 영원히 막지
     * 않게 하려는 것이다([ContentMapScanService.STALE_AFTER]).
     */
    val updatedAt: Instant = requestedAt,
)

/**
 * 스캔 하나가 지나가는 단계. 화면은 단계마다 몫을 정해 도넛 하나로 잇는다.
 *
 * 퍼센트를 서버가 만들지 않는 이유는 [kr.artel.orchestration.contentmap.dto.IngestProgressResponse] 와 같다 —
 * 단계마다 분모가 다르고 그 몫을 정하는 것은 그리는 쪽의 일이다.
 */
enum class ScanPhase {
    /** 게임이 씬을 하나씩 열며 근거를 모은다. [ScanStatus.progress] 는 씬 수다. */
    SCANNING,

    /** 게임이 모은 문서를 올린다. */
    UPLOADING,

    /** 서버가 올라온 문서를 씬 명세로 앉힌다. 문서 수는 `ingest` 이벤트가 따로 싣는다. */
    INGESTING,

    /** 스캔하고 TC 생성일 때, 앉힌 지도로 TC 를 만든다. */
    GENERATING_TEST_CASES,
}

/** [ScanStatus.phase] 안의 진행. [scene] 은 지금 여는 씬 이름이고 모르면 null 이다. */
data class ScanProgress(
    val done: Int,
    val total: Int,
    val scene: String? = null,
)

/**
 * "스캔하고 TC 생성" 의 TC 쪽. 스캔이 성공해 문서가 다 읽힌 뒤에만 만든다 — 스캔이 깨지면 [ScanTestCaseState.PENDING]
 * 으로 남는다.
 */
data class ScanTestCases(
    val carryOver: Boolean,
    val state: ScanTestCaseState = ScanTestCaseState.PENDING,
    val created: Int? = null,
    val carriedOver: Int? = null,
    /** [ScanTestCaseState.SKIPPED] · [ScanTestCaseState.FAILED] 일 때 사람에게 보여 줄 사유. */
    val reason: String? = null,
)

enum class ScanTestCaseState {
    /** 스캔이 아직 끝나지 않았다. */
    PENDING,

    /** 만들었다. */
    GENERATED,

    /** 그사이 이 빌드에서 TC 를 만들어 두어 건너뛰었다. */
    SKIPPED,

    /** 만들지 못했다. */
    FAILED,
}
