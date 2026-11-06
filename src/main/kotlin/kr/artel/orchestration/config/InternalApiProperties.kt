package kr.artel.orchestration.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 무인증 서버-투-서버 API(`/internal/` 하위) 전용 포트 설정.
 *
 * 공개 포트(`server.port`, 8080)와 갈라 놓는 것이 목적이다. 리버스 프록시(NPM)는 8080만
 * 알고 이 포트는 존재조차 모르므로, "프록시에서 `/internal/`을 막는다"는 레포 밖 규칙에
 * 기대지 않아도 된다.
 *
 * 값을 바꿀 수 있게 둔 이유는 배포 환경마다 포트가 겹칠 수 있어서이고, 테스트가 0을 넣어
 * 빈 포트를 잡기 위해서다(스위트에 Spring 컨텍스트가 여럿 뜨므로 고정 포트면 충돌한다).
 */
@ConfigurationProperties("artel.internal-api")
data class InternalApiProperties(
    /** 0이면 빈 포트를 OS가 고른다. 테스트 전용 값이며 배포에서는 쓰지 않는다. */
    val port: Int = 8081
)
