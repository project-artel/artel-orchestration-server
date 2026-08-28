package kr.artel.orchestration.contentmap.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 액션과 `pulse` 를 붙이는 귀속 창의 설정(`artel.content-map.action-observation`, ARTEL-450).
 *
 * 여기 있는 값 셋이 "무엇을 이 액션의 결과로 볼 것인가" 의 임계값 전부다. 실측 근거는
 * `kr.artel.orchestration.contentmap.observe.ActionTimeline` 의 KDoc 에 있다.
 */
@ConfigurationProperties(prefix = "artel.content-map.action-observation")
data class ActionObservationProperties(

    /**
     * 액션 뒤 몇 개의 `pulse` 까지를 창으로 볼 것인가.
     *
     * `ScreenFold.SETTLE_READINGS` 가 2 라, 액션이 일으킨 화면 변화가 창 안에 들어오려면 최소
     * 2 는 되어야 한다. 4 는 그 두 배이고, 실측 `pulse` 간격 중앙값 0.116 초에서 약 0.5 초다.
     */
    val readings: Int = 4,

    /**
     * 창의 시간 상한(밀리초). `pulse` 가 뜸해질 때 창이 분 단위로 늘어나는 것을 막는다.
     *
     * 실측 247 개 ACTION 에서 다음 `pulse` 까지 걸린 시간은 1 초 안이 대부분이고, 2 초를 넘는
     * 것은 `pulse` 가 아예 멎은 구간(런 종료 직전)뿐이었다. 1 초로 자르면 액션 34 개가 창에
     * `pulse` 를 하나도 못 담고, 2 초면 15 개, 3 초여도 15 개다 — 2 초가 회수의 끝이다.
     */
    val windowMillis: Long = 2_000,

    /**
     * 한 관측이 담는 `observed_effects` 항목 수의 상한.
     *
     * 씬을 넘는 액션은 세상이 통째로 바뀌어 실측 최대 412 항목이 나온다. 그 자체는 참이지만,
     * 값이 매번 바뀌는 게임에서 이 수가 얼마까지 갈지는 우리가 정할 수 없으므로 못을 박는다.
     */
    val maxObservedEffects: Int = 256,

    /**
     * 한 인스턴스의 instance id → selector 표에 들고 있을 항목 수의 상한.
     *
     * 넘으면 통째로 비운다. 다음 전량 `pulse` 가 복구하므로 자기 치유되고, 그동안 잃는 것은
     * 해석하지 못한 액션 몇 개다 — 새는 것보다 낫다.
     */
    val maxTrackedObjects: Int = 4_096,
) {
    init {
        require(readings >= 2) {
            "artel.content-map.action-observation.readings 는 2 이상이어야 합니다. " +
                "ScreenFold.SETTLE_READINGS 가 2 라, 그보다 짧은 창에는 액션이 일으킨 화면 변화가 들어올 수 없습니다."
        }
        require(windowMillis > 0) { "artel.content-map.action-observation.window-millis 는 1 이상이어야 합니다." }
        require(maxObservedEffects > 0) { "artel.content-map.action-observation.max-observed-effects 는 1 이상이어야 합니다." }
        require(maxTrackedObjects > 0) { "artel.content-map.action-observation.max-tracked-objects 는 1 이상이어야 합니다." }
    }
}
