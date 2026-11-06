package kr.artel.orchestration.testscenario.config

import org.springframework.boot.context.properties.ConfigurationProperties

/**
 * 저작 한 판을 처음부터 끝까지 받아 적는 기록 설정(`artel.scenario.trace`).
 *
 * **기본은 꺼져 있다.** 한 판의 기록에는 케이스 전량 목록과 모델이 낸 원문이 통째로 들어가
 * 수십 KB 가 된다. 배포 환경에서 판마다 그것을 디스크에 쌓을 이유는 없고, 이 기록은 저작이
 * 왜 그렇게 답했는지 되짚는 자리 — 로컬에서 켠다.
 */
@ConfigurationProperties(prefix = "artel.scenario.trace")
data class AuthoringTraceProperties(

    /** 켜야 적는다. 끄면 부르는 쪽은 그대로 두고 이 클래스가 아무것도 하지 않는다. */
    val enabled: Boolean = false,

    /** 판마다 파일 하나가 여기 쌓인다. 없으면 만든다. */
    val dir: String = ".trace",
)
