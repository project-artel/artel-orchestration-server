package kr.artel.orchestration.tracker.controller

import kr.artel.orchestration.tracker.client.UnsupportedTrackerException
import kr.artel.orchestration.tracker.entity.TrackerProvider

/**
 * 쿼리 파라미터로 들어온 `provider` 를 값으로. 모르는 이름은 400 이다 — 조용히 기본값으로 떨어지면
 * 오타가 "GitHub 연결이 없다"로 보인다.
 *
 * 오류 타입이 [UnsupportedTrackerException] 하나인 것이 중요하다. 같은 사용자 오류에 `code` 가 둘이면
 * artel-home 이 그 하나를 위해 분기를 두 벌 만든다.
 */
internal fun requireProvider(value: String): TrackerProvider =
    TrackerProvider.parse(value) ?: throw UnsupportedTrackerException(value)
