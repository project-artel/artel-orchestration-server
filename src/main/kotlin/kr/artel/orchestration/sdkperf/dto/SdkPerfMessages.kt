package kr.artel.orchestration.sdkperf.dto

import com.fasterxml.jackson.annotation.JsonAnySetter
import com.fasterxml.jackson.annotation.JsonIgnore
import com.fasterxml.jackson.annotation.JsonIgnoreProperties
import com.fasterxml.jackson.annotation.JsonProperty

/**
 * SDK가 `/ws/sdk`로 보내는 성능 보고 메시지 (artel-sdk PR #56).
 *
 * 모든 타입에 `@JsonIgnoreProperties(ignoreUnknown = true)`를 명시한다. Spring Boot 기본
 * ObjectMapper가 이미 미지 필드를 무시하지만, 그 기본값은 언제든 설정 한 줄로 뒤집힌다.
 * 그때 이 경로가 같이 죽으면 SDK가 필드를 하나 더하는 순간 초당 한 번씩 파싱이 실패한다.
 * SDK와 서버는 따로 배포되므로 여기서는 명시가 곧 계약이다.
 */

/**
 * 1초마다 오는 성능 표본.
 *
 * @property process **없을 수 있다.** 읽을 수 없는 플랫폼이거나 아직 비교할 이전 판독이
 *   없으면 필드째 빠진다. 0으로 오지 않으므로 서버도 없음과 0을 구분해 저장한다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkPerformanceMessage(
    val type: String,
    val id: Long? = null,
    val frameTimes: SdkFrameTimes,
    val status: SdkRunStatus,
    val process: SdkProcessMetrics? = null
) {
    /**
     * 이름을 모르는 지표군 (ARTEL-435).
     *
     * 최상위의 **객체** 필드 중 위에서 이름을 붙이지 않은 것이 지표군이다. SDK는 이미 군 단위로
     * 묶어 보내고(`PerformanceMessageDto`의 클래스 주석), 못 잰 군은 필드째 뺀다.
     *
     * 서버가 아는 군만 남기고 나머지를 버리면, SDK가 군을 하나 더할 때마다 서버를 먼저 고쳐야
     * 배포 순서가 성립한다. 여기서 통째로 받아 두면 서버 코드를 고치지 않아도 값이 흐른다.
     */
    @get:JsonIgnore
    val groups: MutableMap<String, Map<String, Any?>> = linkedMapOf()

    @JsonAnySetter
    @Suppress("UNCHECKED_CAST")
    fun captureUnknown(name: String, value: Any?) {
        // 스칼라는 군이 아니다. 군은 언제나 한 단계 아래로 묶인 객체다.
        if (value is Map<*, *>) groups[name] = value as Map<String, Any?>
    }
}

/**
 * @property sampledMs 이 창이 실제로 덮은 시간. 전송 주기(1초)와 다르다 — 포커스를 잃은
 *   프레임이 빠지고, 재연결 뒤에는 1초보다 긴 창이 온다. 시간 가중 지표의 기준이다.
 * @property budgetMs 그 구간에 적용된 프레임 예산. 결함 판정은 절대값이 아니라 이 값 기준이다.
 * @property pointOnePercentLowFps 1초 창(약 60프레임)에서는 대상이 1프레임이라 `maxMs`의
 *   역수와 같아진다. 창 길이의 한계이고 서버가 보정하지 않는다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkFrameTimes(
    val frameCount: Int,
    val sampledMs: Double,
    val meanMs: Double,
    val minMs: Double,
    val maxMs: Double,
    val p95Ms: Double,
    val p99Ms: Double,
    val onePercentLowFps: Double,
    val pointOnePercentLowFps: Double,
    val hitchCount: Int,
    val hitchThresholdMs: Double,
    val budgetMs: Double
)

/**
 * @property batteryStatus `Discharging`이면 노트북 스로틀링이 걸린 표본일 수 있다.
 *   정상 표본과 섞어 평균 내면 통계가 망가지므로 비율을 요약에 남긴다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkRunStatus(
    // `is` 접두 불리언은 Jackson의 빈 명명 규칙이 "is"를 떼어 `focused`로 읽으려 드는
    // 자리다. 코틀린 모듈이 생성자 파라미터 이름으로 덮어 주긴 하지만, 그 동작에 기대면
    // 모듈 설정이 바뀌는 순간 값이 조용히 기본값으로 들어온다. 와이어 이름을 못 박는다.
    @JsonProperty("isFocused")
    val isFocused: Boolean,
    val batteryStatus: String? = null
)

@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkProcessMetrics(
    val cpuPercent: Double? = null,
    val workingSetBytes: Long? = null,
    val privateBytes: Long? = null,
    val managedHeapBytes: Long? = null,
    val gen0Collections: Int? = null,
    val gen1Collections: Int? = null,
    val gen2Collections: Int? = null,
    val sampledMs: Double? = null
)

/** 연결당 한 번 오는 하드웨어·렌더링·빌드 컨텍스트. */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkDeviceContextMessage(
    val type: String,
    val id: Long? = null,
    val device: SdkDeviceInfo
)

/**
 * @property isEditor true인 런을 Standalone 통계에 섞으면 안 된다. 에디터는 씬 뷰 렌더와
 *   인스펙터 갱신이 얹혀 수치가 부풀려진다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class SdkDeviceInfo(
    val deviceModel: String? = null,
    val processorType: String? = null,
    val processorCount: Int? = null,
    val systemMemoryMb: Int? = null,
    val graphicsDeviceName: String? = null,
    val graphicsDeviceType: String? = null,
    val graphicsMemoryMb: Int? = null,
    val operatingSystem: String? = null,
    val qualityLevel: Int? = null,
    val resolutionWidth: Int? = null,
    val resolutionHeight: Int? = null,
    val refreshRateHz: Double? = null,
    val dpi: Double? = null,
    val fullScreenMode: String? = null,
    val targetFrameRate: Int? = null,
    val vSyncCount: Int? = null,
    // SdkRunStatus.isFocused와 같은 이유로 와이어 이름을 못 박는다.
    @JsonProperty("isEditor")
    val isEditor: Boolean? = null,
    @JsonProperty("isDebugBuild")
    val isDebugBuild: Boolean? = null,
    val scriptingBackend: String? = null,
    val sdkVersion: String? = null,
    /**
     * 이 SDK 빌드가 수집을 **시도하는** 지표군 이름 (ARTEL-435).
     *
     * 값이 오지 않은 군을 두고 "재려다 못 쟀다"(`UNSUPPORTED`)와 "이 SDK는 이 군을
     * 모른다"(`NOT_REPORTED`)를 가르는 유일한 근거다. 둘을 구분하지 못하면 값이 사라졌을 때
     * 게임 코드 탓인지 SDK 탓인지 알 수 없어 빌드 간 회귀 판단이 성립하지 않는다.
     *
     * `null`이면 이 필드 이전 SDK다. 그 연결의 새 군은 전부 `NOT_REPORTED`이며 그것이 정확한
     * 답이다 — 서버에 SDK 버전 표를 두는 대안은 릴리스마다 서버를 고쳐야 하고, 잊으면 조용히
     * 틀린 답을 낸다.
     */
    val collectedGroups: List<String>? = null
)
