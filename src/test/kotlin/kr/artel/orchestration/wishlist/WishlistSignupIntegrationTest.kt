package kr.artel.orchestration.wishlist

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.config.AuthProperties
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.OffsetDateTime
import java.util.UUID
import java.util.concurrent.atomic.AtomicInteger

/**
 * 소개 사이트의 Wishlist 가입(`POST /wishlist`).
 *
 * 세션 없이 부른다. 횟수 한도가 클라이언트 주소마다 걸리므로 테스트마다 다른 `X-Forwarded-For` 를 보내 서로의 횟수가
 * 섞이지 않게 한다. database 를 다른 테스트와 같이 쓰므로 이메일도 테스트마다 새로 만든다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class WishlistSignupIntegrationTest {

    companion object {
        private val nextAddress = AtomicInteger()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var authProperties: AuthProperties

    private data class Reply(val status: Int, val body: String, val headers: HttpHeaders)

    @Test
    fun `stores a normalized email without a session`(): Unit = runBlocking {
        val email = uniqueEmail()

        val reply = post("""{"consent":true,"email":"  ${email.uppercase()} ","locale":"ko"}""", uniqueAddress())

        assertThat(reply.status).isEqualTo(204)
        assertThat(reply.body).isEmpty()
        assertThat(rowsFor(email)).containsExactly(email to "ko")
        assertThat(consentedAtFor(email)).isBeforeOrEqualTo(OffsetDateTime.now())
        assertThat(verifiedAtIsNull(email)).isTrue()
    }

    @Test
    fun `a two-letter TLD is accepted`(): Unit = runBlocking {
        val email = "v${UUID.randomUUID().toString().take(8)}@b.co"

        assertThat(post("""{"consent":true,"email":"$email"}""", uniqueAddress()).status).isEqualTo(204)
        assertThat(rowsFor(email)).hasSize(1)
    }

    @Test
    fun `a filled honeypot is 204 and stores nothing, and still counts toward the limit`(): Unit = runBlocking {
        val email = uniqueEmail()
        val address = uniqueAddress()

        val reply = post("""{"consent":true,"email":"$email","locale":"ko","website":"https://spam.example"}""", address)

        assertThat(reply.status).isEqualTo(204)
        assertThat(rowsFor(email)).isEmpty()
        // 검사보다 먼저 끝나므로 형식이 틀린 본문도 204 다.
        assertThat(post("""{"email":"not-an-email","website":"x"}""", address).status).isEqualTo(204)
        // 공백만 있으면 비어 있는 것으로 본다.
        assertThat(post("""{"consent":true,"email":"$email","website":"   "}""", address).status).isEqualTo(204)
        assertThat(rowsFor(email)).hasSize(1)
        // 앞의 세 요청이 한도에 들어갔으므로 27번 더 보내면 다음 요청이 429 다.
        repeat(27) { post("""{"website":"x"}""", address) }
        assertThat(post("""{"website":"x"}""", address).status).isEqualTo(429)
    }

    @Test
    fun `a duplicate email in another case is 204 and leaves one row with the first locale`(): Unit = runBlocking {
        val email = uniqueEmail()
        val address = uniqueAddress()
        assertThat(post("""{"consent":true,"email":"$email","locale":"en"}""", address).status).isEqualTo(204)
        val firstConsentedAt = consentedAtFor(email)

        val mixedCase = email.replace("visitor", "Visitor").replace("example.com", "EXAMPLE.com")
        val again = post("""{"consent":true,"email":"$mixedCase","locale":"ko"}""", address)

        assertThat(again.status).isEqualTo(204)
        assertThat(rowsFor(email)).containsExactly(email to "en")
        assertThat(consentedAtFor(email)).isEqualTo(firstConsentedAt)
    }

    @Test
    fun `a request without consent is 400 consent_required and stores nothing`(): Unit = runBlocking {
        val email = uniqueEmail()
        val address = uniqueAddress()
        val bodies = listOf(
            """{"email":"$email","locale":"ko"}""",
            """{"email":"$email","locale":"ko","consent":false}""",
            """{"email":"$email","locale":"ko","consent":null}"""
        )

        for (body in bodies) {
            val reply = post(body, address)
            assertThat(reply.status).describedAs(body).isEqualTo(400)
            assertThat(objectMapper.readTree(reply.body)["code"].asText()).describedAs(body).isEqualTo("consent_required")
        }
        assertThat(rowsFor(email)).isEmpty()
    }

    @Test
    fun `locale is optional`(): Unit = runBlocking {
        val email = uniqueEmail()

        assertThat(post("""{"consent":true,"email":"$email"}""", uniqueAddress()).status).isEqualTo(204)

        assertThat(rowsFor(email)).containsExactly(email to null)
    }

    @Test
    fun `an email of 254 characters is accepted and 255 is not`() {
        val address = uniqueAddress()
        val localPart = UUID.randomUUID().toString().replace("-", "")

        assertThat(post("""{"consent":true,"email":"${localPart.padEnd(249, 'a')}@x.kr"}""", address).status).isEqualTo(204)
        assertThat(post("""{"consent":true,"email":"${localPart.padEnd(250, 'a')}@x.kr"}""", address).status).isEqualTo(400)
    }

    @Test
    fun `an invalid email is 400 invalid_email`() {
        val address = uniqueAddress()
        val bodies = listOf(
            """{"consent":true,"email":"not-an-email","locale":"ko"}""",
            """{"consent":true,"email":"two@@example.com"}""",
            // 로그인 이메일은 받지만 메일을 보낼 수 없는 주소다.
            """{"consent":true,"email":"owner@localhost"}""",
            // TLD 가 한 글자다.
            """{"consent":true,"email":"o@s.s"}""",
            // TLD 가 글자가 아니다.
            """{"consent":true,"email":"visitor@example.123"}""",
            """{"consent":true,"email":"has space@example.com"}""",
            """{"consent":true,"email":"   "}""",
            """{"consent":true,"locale":"ko"}"""
        )

        for (body in bodies) {
            val reply = post(body, address)
            assertThat(reply.status).describedAs(body.take(80)).isEqualTo(400)
            assertThat(objectMapper.readTree(reply.body)["code"].asText()).describedAs(body.take(80)).isEqualTo("invalid_email")
        }
    }

    @Test
    fun `a body that is not JSON is 400`() {
        val reply = post("not json", uniqueAddress())

        assertThat(reply.status).isEqualTo(400)
        assertThat(objectMapper.readTree(reply.body)["code"].asText()).isEqualTo("error")
    }

    @Test
    fun `an unknown locale is 400 invalid_locale and stores nothing`(): Unit = runBlocking {
        val email = uniqueEmail()

        val reply = post("""{"consent":true,"email":"$email","locale":"fr"}""", uniqueAddress())

        assertThat(reply.status).isEqualTo(400)
        assertThat(objectMapper.readTree(reply.body)["code"].asText()).isEqualTo("invalid_locale")
        assertThat(rowsFor(email)).isEmpty()
    }

    @Test
    fun `a stale session cookie does not turn the request into 401`(): Unit = runBlocking {
        val email = uniqueEmail()

        val reply = post(
            """{"consent":true,"email":"$email"}""",
            uniqueAddress(),
            cookie = "${authProperties.cookieName}=not-a-valid-jwt"
        )

        assertThat(reply.status).isEqualTo(204)
        assertThat(rowsFor(email)).hasSize(1)
    }

    @Test
    fun `a CORS preflight from the onboarding site is allowed with credentials`() {
        val reply = WebClient.create("http://localhost:$port").method(HttpMethod.OPTIONS).uri("/wishlist")
            .header(HttpHeaders.ORIGIN, "https://artel.kr")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_METHOD, "POST")
            .header(HttpHeaders.ACCESS_CONTROL_REQUEST_HEADERS, "content-type")
            .exchangeToMono { response ->
                response.releaseBody().then(Mono.just(response.statusCode().value() to response.headers().asHttpHeaders()))
            }.block()!!

        assertThat(reply.first).isEqualTo(200)
        assertThat(reply.second.accessControlAllowOrigin).isEqualTo("https://artel.kr")
        assertThat(reply.second.accessControlAllowCredentials).isTrue()
    }

    @Test
    fun `the thirty-first request in an hour from one address is 429`() {
        val address = uniqueAddress()
        repeat(30) { assertThat(post("""{"consent":true,"email":"${uniqueEmail()}"}""", address).status).isEqualTo(204) }

        val limited = post("""{"consent":true,"email":"${uniqueEmail()}"}""", address)

        assertThat(limited.status).isEqualTo(429)
        assertThat(objectMapper.readTree(limited.body)["code"].asText()).isEqualTo("too_many_requests")
        assertThat(limited.headers.getFirst(HttpHeaders.RETRY_AFTER)).isNotBlank()
        // 다른 주소는 막히지 않는다.
        assertThat(post("""{"consent":true,"email":"${uniqueEmail()}"}""", uniqueAddress()).status).isEqualTo(204)
    }

    private suspend fun rowsFor(email: String): List<Pair<String, String?>> =
        databaseClient.sql("SELECT email, locale FROM wishlist_signup WHERE email = :email")
            .bind("email", email)
            .map { row, _ -> row.get("email", String::class.java)!! to row.get("locale", String::class.java) }
            .all().collectList().awaitSingle()

    private suspend fun consentedAtFor(email: String): OffsetDateTime =
        databaseClient.sql("SELECT consented_at FROM wishlist_signup WHERE email = :email")
            .bind("email", email)
            .map { row, _ -> row.get("consented_at", OffsetDateTime::class.java)!! }
            .one().awaitSingle()

    private suspend fun verifiedAtIsNull(email: String): Boolean =
        databaseClient.sql("SELECT verified_at IS NULL AS unverified FROM wishlist_signup WHERE email = :email")
            .bind("email", email)
            .map { row, _ -> row.get("unverified", java.lang.Boolean::class.java)!!.booleanValue() }
            .one().awaitSingle()

    private fun uniqueEmail(): String = "visitor-${UUID.randomUUID()}@example.com"

    /** 테스트마다 겹치지 않는 문서용 주소(203.0.113.0/24). telemetry 테스트의 198.51.100.0/24 와 겹치지 않는다. */
    private fun uniqueAddress(): String = "203.0.113.${nextAddress.incrementAndGet()}"

    private fun post(body: String, address: String, cookie: String? = null): Reply {
        val spec = WebClient.create("http://localhost:$port").post().uri("/wishlist")
            .header("X-Forwarded-For", address)
            .contentType(MediaType.APPLICATION_JSON)
        cookie?.let { spec.header(HttpHeaders.COOKIE, it) }
        return spec.bodyValue(body).exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("").map {
                Reply(response.statusCode().value(), it, response.headers().asHttpHeaders())
            }
        }.block()!!
    }
}
