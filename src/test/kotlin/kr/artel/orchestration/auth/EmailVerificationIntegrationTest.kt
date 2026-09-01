package kr.artel.orchestration.auth

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.entity.EmailVerificationEntity
import kr.artel.orchestration.auth.mail.MailSender
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.EmailVerificationRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Import
import org.springframework.context.annotation.Primary
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import java.time.Instant

/**
 * 발송이 없으므로 토큰을 손에 넣는 유일한 길은 [MailSender] 다. 로그를 파싱하는 대신 보낸 본문을
 * 모아 두는 구현으로 갈아 끼운다 — 토큰이 실제로 메일로만 나가고 응답에는 없다는 것까지 함께
 * 검증하게 된다.
 */
@TestConfiguration
class RecordingMailSenderConfig {
    @Bean
    @Primary
    fun recordingMailSender() = RecordingMailSender()
}

class RecordingMailSender : MailSender {
    val sent = mutableListOf<Triple<String, String, String>>()

    override suspend fun send(to: String, subject: String, body: String) {
        sent += Triple(to, subject, body)
    }

    /** 본문에서 토큰만 꺼낸다. 본문 모양이 바뀌면 이 테스트가 먼저 깨지는 편이 맞다. */
    fun lastToken(): String = sent.last().third.lines().first { it.isNotBlank() && !it.contains(" ") }
}

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(RecordingMailSenderConfig::class)
class EmailVerificationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var verificationRepository: EmailVerificationRepository
    @Autowired private lateinit var mailSender: RecordingMailSender

    /** 리액티브 트랜잭션은 구독 컨텍스트에 묶여 있어 @Transactional 테스트 롤백이 동작하지 않는다. */
    @BeforeEach
    fun clean(): Unit = runBlocking {
        verificationRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
        mailSender.sent.clear()
    }

    @Test
    fun `treats a provider-supplied address as already verified`(): Unit = runBlocking {
        val token = signIn("42", "octocat", email = "octocat@example.com")

        val me = get(token, "/api/auth/me")

        assertThat(me["email"].asText()).isEqualTo("octocat@example.com")
        assertThat(me["emailVerified"].asBoolean()).isTrue()
        assertThat(me["pendingEmail"].isNull).isTrue()
    }

    @Test
    fun `leaves an account the provider gave no address unverified`(): Unit = runBlocking {
        val token = signIn("43", "nomail", email = null)

        val me = get(token, "/api/auth/me")

        assertThat(me["email"].isNull).isTrue()
        assertThat(me["emailVerified"].asBoolean()).isFalse()
    }

    @Test
    fun `holds a newly registered address as pending until the code is used`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)

        val status = registerEmail(session, "Chosen@Example.com")

        assertThat(status).isEqualTo(HttpStatus.ACCEPTED)
        val me = get(session, "/api/auth/me")
        // 아직 계정의 주소가 아니다. 코드를 넣기 전에는 초대를 받을 수 없다.
        assertThat(me["email"].isNull).isTrue()
        assertThat(me["emailVerified"].asBoolean()).isFalse()
        assertThat(me["pendingEmail"].asText()).isEqualTo("chosen@example.com")
    }

    @Test
    fun `sends the code by mail and keeps it out of the response`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)

        registerEmail(session, "chosen@example.com")

        assertThat(mailSender.sent).hasSize(1)
        assertThat(mailSender.sent.single().first).isEqualTo("chosen@example.com")
        val stored = verificationRepository.findAll().toList().single()
        // 저장된 것은 해시뿐이다. 원문과 같지 않아야 이 테이블을 읽는 것으로 남의 주소를 확정할 수 없다.
        assertThat(stored.tokenHash).hasSize(64).isNotEqualTo(mailSender.lastToken())
    }

    @Test
    fun `makes the address the account's own once the code is used`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)
        registerEmail(session, "chosen@example.com")

        val status = verifyEmail(session, mailSender.lastToken())

        assertThat(status).isEqualTo(HttpStatus.NO_CONTENT)
        val me = get(session, "/api/auth/me")
        assertThat(me["email"].asText()).isEqualTo("chosen@example.com")
        assertThat(me["emailVerified"].asBoolean()).isTrue()
        assertThat(me["pendingEmail"].isNull).isTrue()
    }

    @Test
    fun `refuses a code that was already used`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)
        registerEmail(session, "chosen@example.com")
        val code = mailSender.lastToken()
        verifyEmail(session, code)

        val status = verifyEmail(session, code)

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `refuses a code that expired`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)
        registerEmail(session, "chosen@example.com")
        expireEveryPendingCode()

        val status = verifyEmail(session, mailSender.lastToken())

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(session, "/api/auth/me")["emailVerified"].asBoolean()).isFalse()
    }

    @Test
    fun `refuses a code that never existed`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)

        val status = verifyEmail(session, "not-a-real-code")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
    }

    @Test
    fun `refuses another account's code`(): Unit = runBlocking {
        val owner = signIn("43", "nomail", email = null)
        registerEmail(owner, "chosen@example.com")
        val stolen = mailSender.lastToken()
        val thief = signIn("44", "thief", email = null)

        val status = verifyEmail(thief, stolen)

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(get(thief, "/api/auth/me")["emailVerified"].asBoolean()).isFalse()
    }

    @Test
    fun `refuses an address another account already verified`(): Unit = runBlocking {
        signIn("42", "octocat", email = "taken@example.com")
        val second = signIn("43", "nomail", email = null)

        val status = registerEmail(second, "taken@example.com")

        assertThat(status).isEqualTo(HttpStatus.CONFLICT)
    }

    @Test
    fun `lets an account register an address only an unverified account holds`(): Unit = runBlocking {
        // 확인 전 주소는 아직 아무것도 주장하지 않으므로 다른 사람이 그 주소를 가져갈 수 있다.
        val holder = signIn("43", "nomail", email = null)
        registerEmail(holder, "shared@example.com")
        val other = signIn("44", "other", email = null)

        val status = registerEmail(other, "shared@example.com")

        assertThat(status).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `rejects a malformed address before it reaches the database`(): Unit = runBlocking {
        val session = signIn("43", "nomail", email = null)

        val status = registerEmail(session, "not-an-address")

        assertThat(status).isEqualTo(HttpStatus.BAD_REQUEST)
        assertThat(verificationRepository.findAll().toList()).isEmpty()
    }

    @Test
    fun `lets the same account ask for a new code for the address it already verified`(): Unit = runBlocking {
        val session = signIn("42", "octocat", email = "octocat@example.com")

        val status = registerEmail(session, "octocat@example.com")

        // 메일이 안 왔을 때 다시 보낼 길이 있어야 한다. 자기 주소를 막을 이유가 없다.
        assertThat(status).isEqualTo(HttpStatus.ACCEPTED)
    }

    @Test
    fun `rejects both endpoints without a session`(): Unit = runBlocking {
        assertThat(registerEmail(null, "chosen@example.com")).isEqualTo(HttpStatus.UNAUTHORIZED)
        assertThat(verifyEmail(null, "whatever")).isEqualTo(HttpStatus.UNAUTHORIZED)
    }

    private suspend fun expireEveryPendingCode() {
        verificationRepository.findAll().toList().forEach {
            verificationRepository.save(it.expiredCopy())
        }
    }

    private fun EmailVerificationEntity.expiredCopy() =
        copy(expiresAt = Instant.now().minusSeconds(60))

    private suspend fun signIn(providerUserId: String, login: String, email: String?): String {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = email
            )
        )
        return jwtService.issue(user)
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun registerEmail(token: String?, email: String): HttpStatus =
        post(token, "/api/auth/me/email", """{"email":"$email"}""")

    private fun verifyEmail(token: String?, code: String): HttpStatus =
        post(token, "/api/auth/me/email/verify", """{"token":"$code"}""")

    private fun post(token: String?, uri: String, body: String): HttpStatus =
        try {
            val request = client().post().uri(uri)
            val withSession = if (token != null) request.cookie("artel_access_token", token) else request
            val response = withSession
                .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
                .retrieve().toBodilessEntity().block()!!
            HttpStatus.valueOf(response.statusCode.value())
        } catch (error: WebClientResponseException) {
            HttpStatus.valueOf(error.statusCode.value())
        }

    private fun client() = WebClient.create("http://localhost:$port")
}
