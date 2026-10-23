package kr.artel.orchestration.telemetry

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpMethod
import org.springframework.http.MediaType
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import reactor.core.publisher.Mono
import java.time.LocalDate
import java.time.ZoneOffset
import java.util.UUID

/**
 * artel.kr 의 receiver(`POST /telemetry/reports`, `DELETE /telemetry/reports/{installId}`).
 *
 * 세션 없이 부른다. 횟수 한도가 클라이언트 주소마다 걸리므로 테스트마다 다른 `X-Forwarded-For` 를 보내 서로의 횟수가
 * 섞이지 않게 한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TelemetryReceiverIntegrationTest {

    companion object {
        private val nextAddress = java.util.concurrent.atomic.AtomicInteger()
    }

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var databaseClient: DatabaseClient
    @Autowired private lateinit var objectMapper: ObjectMapper

    @Test
    fun `stores a report without a session and overwrites the same day on resend`(): Unit = runBlocking {
        val installId = UUID.randomUUID().toString()
        val address = uniqueAddress()

        assertThat(post(report(installId, model = "claude/opus"), address)).isEqualTo(204)
        assertThat(post(report(installId, model = "claude/sonnet"), address)).isEqualTo(204)

        val rows = databaseClient.sql(
            "SELECT report ->> 'qaStructures' AS structures, schema_version FROM telemetry_report WHERE install_id = CAST(:id AS uuid)"
        ).bind("id", installId)
            .map { row, _ -> row.get("structures", String::class.java)!! to row.get("schema_version", Int::class.javaObjectType)!! }
            .all().collectList().awaitSingle()
        assertThat(rows).hasSize(1)
        assertThat(rows.single().first).contains("claude/sonnet")
        assertThat(rows.single().second).isEqualTo(1)
    }

    @Test
    fun `deletes every report of an install`(): Unit = runBlocking {
        val installId = UUID.randomUUID().toString()
        val address = uniqueAddress()
        post(report(installId, model = "m", daysAgo = 1), address)
        post(report(installId, model = "m", daysAgo = 2), address)

        assertThat(exchange(HttpMethod.DELETE, "/telemetry/reports/$installId", null, address)).isEqualTo(204)

        val count = databaseClient.sql("SELECT COUNT(*) AS n FROM telemetry_report WHERE install_id = CAST(:id AS uuid)")
            .bind("id", installId).map { row, _ -> (row.get("n") as Number).toLong() }.one().awaitSingle()
        assertThat(count).isZero()
        assertThat(exchange(HttpMethod.DELETE, "/telemetry/reports/not-a-uuid", null, address)).isEqualTo(400)
    }

    @Test
    fun `a body over 256 KB is 413`() {
        val oversized = report(UUID.randomUUID().toString(), model = "x".repeat(300 * 1024))
        assertThat(post(oversized, uniqueAddress())).isEqualTo(413)
    }

    @Test
    fun `a report that does not match schema version 1 is 400`() {
        val address = uniqueAddress()
        val installId = UUID.randomUUID().toString()
        val valid = objectMapper.readTree(report(installId, model = "m")) as com.fasterxml.jackson.databind.node.ObjectNode

        val wrongVersion = valid.deepCopy().put("schemaVersion", 2)
        val unknownField = valid.deepCopy().put("projectName", "my secret game")
        val missingInstall = valid.deepCopy().also { it.remove("install") }
        val badInstallId = valid.deepCopy().also { (it["install"] as com.fasterxml.jackson.databind.node.ObjectNode).put("installId", "abc") }
        val futureDate = valid.deepCopy().also {
            (it["install"] as com.fasterxml.jackson.databind.node.ObjectNode).put("reportDate", today().plusDays(5).toString())
        }

        for (body in listOf(wrongVersion, unknownField, missingInstall, badInstallId, futureDate)) {
            assertThat(post(body.toString(), address)).describedAs(body.toString().take(80)).isEqualTo(400)
        }
        assertThat(post("not json", address)).isEqualTo(400)
    }

    @Test
    fun `the thirty-first request in an hour from one address is 429`() {
        val address = uniqueAddress()
        val installId = UUID.randomUUID().toString()
        repeat(30) { assertThat(post(report(installId, model = "m"), address)).isEqualTo(204) }

        assertThat(post(report(installId, model = "m"), address)).isEqualTo(429)
        // 다른 주소는 막히지 않는다.
        assertThat(post(report(installId, model = "m"), uniqueAddress())).isEqualTo(204)
    }

    private fun report(installId: String, model: String, daysAgo: Long = 1): String = """
        {"schemaVersion":1,
         "install":{"installId":"$installId","orchestrationVersion":null,"agentServerVersions":["agent-2.0.1"],
                    "reportDate":"${today().minusDays(daysAgo)}"},
         "qaStructures":[{"agentArch":"loop","agentFingerprint":null,"model":"$model","reasoningEffort":null,"tries":1,
                          "triesByStatus":{"COMPLETED":1},"stepsTotal":null,"stepsPassed":null,"casesTotal":null,
                          "casesPassed":null,"durationSecondsSum":5,"durationSecondsMax":5,"toolCalls":{"other":2}}],
         "llmUsage":[],
         "errors":[{"source":"qa_run","code":"run_incomplete","exceptionType":null,"count":1,"frames":[]}]}
    """.trimIndent()

    /** 테스트마다 겹치지 않는 문서용 주소(198.51.100.0/24). */
    private fun uniqueAddress(): String = "198.51.100.${nextAddress.incrementAndGet()}"

    private fun today(): LocalDate = LocalDate.now(ZoneOffset.UTC)

    private fun post(body: String, address: String): Int = exchange(HttpMethod.POST, "/telemetry/reports", body, address)

    private fun exchange(method: HttpMethod, path: String, body: String?, address: String): Int {
        val spec = WebClient.create("http://localhost:$port").method(method).uri(path).header("X-Forwarded-For", address)
        val request = if (body != null) spec.contentType(MediaType.APPLICATION_JSON).bodyValue(body) else spec
        return request.exchangeToMono { response ->
            response.releaseBody().then(Mono.just(response.statusCode().value()))
        }.block()!!
    }
}
