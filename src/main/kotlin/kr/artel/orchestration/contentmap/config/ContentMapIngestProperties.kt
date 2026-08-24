package kr.artel.orchestration.contentmap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 근거 문서 적재 배치 설정(`artel.content-map.ingest`).
 *
 * 등록은 문서 포인터만 만들고 즉시 돌아온다. 실제 파싱은 이 배치가 한다 — 실측 문서가 1.4 MB 라
 * 등록 응답에 매달면 SDK 가 게임 실행마다 그 시간을 기다리게 되는데, 등록이 SDK 에 돌려주는 것은
 * 문서를 받았다는 사실뿐이고 그것은 이미 참이다.
 */
@ConfigurationProperties(prefix = "artel.content-map.ingest")
data class ContentMapIngestProperties(

    /**
     * 배치를 돌릴지.
     *
     * **기본이 꺼짐인 이유.** 적재는 씬·기능 행을 앉히고 이번 문서에 없는 기능은 내린다. 배포의
     * 부수 효과로 그것이 시작되면 되돌리기 어렵다. 사람이 확인하고 켠다.
     */
    val enabled: Boolean = false,

    /** 한 tick 에 집을 문서 수의 상한. 문서 하나가 1.4 MB 파싱이라 크게 둘 값이 아니다. */
    val batchSize: Int = 5,

    /**
     * 한 문서를 몇 번까지 시도할지. 넘긴 문서는 큐 조회에서 빠지되 행은 남아 `last_error` 와
     * 함께 조회할 수 있다.
     *
     * 상한이 없으면 파싱에서 죽는 문서 하나가 매 tick 마다 스토리지에서 1.4 MB 를 다시 읽고,
     * `received_at ASC` 라 언제나 큐의 앞자리를 차지한다.
     */
    val maxAttempts: Int = 5,

    /** tick 간격(밀리초). 겹치지 않도록 fixedDelay 로 쓴다. */
    val intervalMillis: Long = 60_000,
) {
    init {
        require(batchSize > 0) { "artel.content-map.ingest.batch-size는 1 이상이어야 합니다." }
        require(maxAttempts > 0) { "artel.content-map.ingest.max-attempts는 1 이상이어야 합니다." }
        require(intervalMillis > 0) { "artel.content-map.ingest.interval-millis는 1 이상이어야 합니다." }
    }
}
