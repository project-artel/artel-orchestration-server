package kr.artel.orchestration.sdkperf.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 원본 성능 표본 보존 설정(`artel.sdk-performance.retention`).
 *
 * 요약·1초 시계열·budget 도수는 이 정책이 건드리지 않는다. 조회 API가 읽는 것은 그쪽뿐이라
 * 보존 기간이 지난 런의 상세 화면도 그대로 뜬다 — 사라지는 것은 표본 단위 드릴다운이고,
 * 그런 경로는 지금 없다. 그래서 이 설정은 응답 계약에 영향을 주지 않는다.
 */
@ConfigurationProperties(prefix = "artel.sdk-performance.retention")
data class SdkPerformanceRetentionProperties(

    /**
     * 삭제 잡을 돌릴지.
     *
     * **기본값이 꺼짐인 이유**: 배포의 부수 효과로 운영 데이터가 지워지면 되돌릴 수 없다.
     * 보존 기간이 의도한 값인지 확인한 뒤 사람이 켠다.
     *
     * `@ConditionalOnProperty`가 같은 키를 문자열로 읽는다. 스케줄러 빈 자체를 만들지 말지를
     * 정하는 값이라 빈 주입보다 먼저 필요하고, 그래서 두 곳에 나타난다.
     */
    val enabled: Boolean = false,

    /** 원본 표본을 며칠까지 남길지. */
    val days: Long = 30,

    /** 한 번의 DELETE가 지우는 행 수. 한 문장으로 다 지우면 긴 잠금이 수신 경로를 막는다. */
    val batchSize: Int = 5_000,

    /**
     * 한 tick의 상한.
     *
     * 배치 하나로 끝내면 삭제 속도가 유입 속도를 못 따라간다 — 인스턴스 하나가 시간당 3600행을
     * 만드는데 시간당 5000행만 지우면 동시 접속 두 개부터 기준선이 영영 전진하지 않고,
     * `deleted > 0`이 계속 참이라 로그만 보면 정상으로 보인다.
     */
    val maxRowsPerTick: Int = 500_000,

    /**
     * tick 간격(밀리초). 겹치지 않도록 fixedDelay로 쓴다.
     *
     * `@Scheduled`는 빈을 읽을 수 없어 스케줄러가 이 키를 문자열 placeholder로 다시 읽는다.
     * 두 곳의 기본값은 같이 움직여야 한다.
     */
    val intervalMillis: Long = 3_600_000
) {
    init {
        require(days > 0) { "artel.sdk-performance.retention.days는 1 이상이어야 합니다." }
        require(batchSize > 0) { "artel.sdk-performance.retention.batch-size는 1 이상이어야 합니다." }
        require(maxRowsPerTick >= batchSize) {
            "artel.sdk-performance.retention.max-rows-per-tick은 batch-size 이상이어야 합니다. " +
                "작으면 tick마다 한 배치도 다 지우지 못한다."
        }
    }
}
