package kr.artel.orchestration.auth.mail

import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component

/**
 * 메일을 보내지 않고 로그에 남기는 [MailSender].
 *
 * 실제 발송 provider 가 붙기 전까지 이것이 유일한 구현이다. 확인 토큰은 여기 찍힌 로그에서만
 * 읽을 수 있고, 계정 설정 화면은 그 값을 붙여 넣는 자리로 만들어져 있다(ARTEL-733).
 *
 * 조용히 성공하지 않는다. `warn` 으로 남기는 것은, 이 줄이 로그에 있다는 것 자체가 "이 배포는
 * 메일을 못 보낸다"는 뜻이기 때문이다. `info` 로 두면 provider 를 붙였다고 생각한 뒤에도 아무도
 * 눈치채지 못한다.
 *
 * 본문을 통째로 찍는다. 토큰이 로그에 남는다는 뜻이고, 그것을 감수하는 것은 지금 그 로그가
 * 사용자가 확인을 마칠 수 있는 유일한 경로이기 때문이다. 실제 발송이 붙는 순간 이 구현은
 * 지워져야 한다.
 */
@Component
class LoggingMailSender : MailSender {

    private val log = LoggerFactory.getLogger(javaClass)

    override suspend fun send(to: String, subject: String, body: String) {
        log.warn(
            "메일을 보내지 않고 로그에만 남긴다. 발송 provider 가 아직 없다. to={} subject={}\n{}",
            to,
            subject,
            body
        )
    }
}
