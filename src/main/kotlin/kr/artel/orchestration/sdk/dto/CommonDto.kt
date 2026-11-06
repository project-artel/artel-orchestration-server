package kr.artel.orchestration.sdk.dto

import com.fasterxml.jackson.annotation.JsonIgnoreProperties

/**
 * 웹소켓으로 들어오는 모든 메시지의 상위 공통 타입을 파악하기 위한 기본 메시지 클래스
 *
 * 이 타입은 프레임 전체를 `type` 하나로 줄여 읽으므로 **본문의 나머지 필드는 전부 미지 필드다.**
 * `ignoreUnknown`을 명시하지 않으면 이 클래스는 Spring Boot가 기본으로 꺼 두는
 * `FAIL_ON_UNKNOWN_PROPERTIES`에 수명을 의존하게 된다. 그 기본값이 설정 한 줄로 뒤집히는 순간
 * 모든 프레임이 타입 분기 이전에 파싱 예외로 떨어지고, SDK가 초당 한 번 보내는 `PERFORMANCE`는
 * "모르는 타입"이 아니라 "깨진 프레임"으로 기록된다. SDK와 서버는 따로 배포되므로 명시가 곧 계약이다.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
data class BaseMessage(
    val type: String
)
