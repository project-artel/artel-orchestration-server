package kr.artel.orchestration.auth.mail

/**
 * 메일 한 통을 내보내는 자리.
 *
 * 이 저장소에는 아직 실제 발송 경로가 없다. SMTP 나 SES 를 붙이려면 자격 증명과 발신 도메인이
 * 필요하고 그것은 별도 작업이다. 그래서 부르는 쪽은 이 인터페이스만 알고, 지금 붙어 있는 구현은
 * [LoggingMailSender] 하나다.
 *
 * 인터페이스를 먼저 두는 이유는 나중에 갈아 끼우기 위해서가 아니라, 발송이 없다는 사실을 한 곳에
 * 가두기 위해서다. [EmailVerificationService] 가 로그를 직접 찍으면 실제 발송이 생길 때 그
 * 서비스를 고쳐야 하고, 그때 확인 로직과 발송 로직이 한 커밋에 섞인다.
 */
interface MailSender {
    /**
     * @param to 받는 주소. 소문자로 정규화된 뒤에 들어온다
     * @param subject 제목
     * @param body 본문. 지금은 평문만 쓴다
     */
    suspend fun send(to: String, subject: String, body: String)
}
