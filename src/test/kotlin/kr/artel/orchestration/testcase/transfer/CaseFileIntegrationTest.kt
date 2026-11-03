package kr.artel.orchestration.testcase.transfer

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.contract.CaseBatchItem
import kr.artel.orchestration.testcase.contract.CaseBatchRequest
import kr.artel.orchestration.testcase.contract.CaseBatchService
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.service.TestCaseVersionService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import java.time.Instant
import java.util.UUID

/**
 * TC 파일 내보내기 · 가져오기(ARTEL-1327).
 * - 버전을 고르면 그 버전의 TC 를 그 버전의 판정과 함께 내보낸다. 화면에 보이는 TC 만 고를 수도 있다.
 * - 내보낸 파일을 그대로 다시 가져오면 아무것도 바뀌지 않는다(계약이 사람 값으로 굳지 않는다).
 * - 번호가 있는 줄은 그 TC 를 고치고 그 버전에 넣는다. 번호가 없으면 새로 만든다. 못 넣는 줄은 이유와 함께 남는다.
 * - "버전 없음" 에 가져오면 어느 버전에도 넣지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class CaseFileIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var batch: CaseBatchService
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionService
    @Autowired private lateinit var contracts: kr.artel.orchestration.testcase.contract.CaseContractService

    @Test
    fun `내보낸 파일을 고쳐 다시 가져오면 고친 줄만 반영된다`(): Unit = runBlocking {
        val world = world()
        val base = "/api/projects/${world.projectId}/test-cases"

        // 1.1.0 버전을 엑셀로 내보낸다. 버전에 든 TC 둘(A · B)만, 번호 · 종류 · 계약과 함께다.
        val xlsx = download(world.token, "$base/exports", """{"format":"xlsx","gameBuildId":"${world.current}"}""")
        assertThat(xlsx.disposition).contains("filename*=UTF-8''").contains("_TC_").contains(".xlsx")
        val exported = CaseFileCodec.read(xlsx.bytes, null)
        assertThat(exported.map { it[CaseFileColumn.STEP] }).containsExactlyInAnyOrder("할머니한테 말을 건다", "열쇠 없이 문을 연다")
        val a = exported.single { it[CaseFileColumn.STEP] == "할머니한테 말을 건다" }
        assertThat(a[CaseFileColumn.NUMBER]).startsWith("TC-")
        assertThat(a[CaseFileColumn.ARRIVES_AT]).isEqualTo("광장")
        assertThat(a[CaseFileColumn.RESULT]).isEqualTo("미검증")
        assertThat(exported.single { it[CaseFileColumn.STEP] == "열쇠 없이 문을 연다" }[CaseFileColumn.KIND]).isEqualTo("예외")

        // 화면에 보이는 것만(B) CSV 로.
        val csv = download(world.token, "$base/exports", """{"format":"csv","gameBuildId":"${world.current}","testCaseIds":["${world.b}"]}""")
        assertThat(csv.disposition).contains(".csv")
        assertThat(String(csv.bytes, Charsets.UTF_8).lines().first()).describedAs("계약 칸(필요 · 남김)은 싣지 않는다")
            .isEqualTo("\uFEFFTC 번호,종류,화면,사전조건,조작,기대결과,도착 화면,결과")
        assertThat(CaseFileCodec.read(csv.bytes, "b.csv").map { it[CaseFileColumn.STEP] }).containsExactly("열쇠 없이 문을 연다")

        // 그대로 다시 가져오면 바뀌는 것이 없다.
        val same = post(world.token, "$base/imports/preview?gameBuildId=${world.current}&fileName=tc.xlsx", xlsx.bytes)
        assertThat(same["counts"]["same"].asInt()).isEqualTo(2)
        assertThat(same["counts"]["edit"].asInt() + same["counts"]["new"].asInt()).isZero()

        // 고친 파일: A 의 조작을 고치고, 1.0.0 에만 있던 C 를 이 버전에 넣고, 새 줄 둘, 못 넣는 줄 둘.
        val cNumber = numberOf(world, world.c)
        val edited = """
            번호,종류,화면,조작,기대결과,필요
            ${a[CaseFileColumn.NUMBER]},기능,광장,할머니한테 다시 말을 건다,의뢰를 받는다,의뢰 == 0
            $cNumber,,여관,저녁까지 쉰다,밤이 된다,
            ,,숲,덤불을 조사한다,고양이가 따라온다,
            ,예외,숲,고양이 없이 할머니한테 간다,보상을 주지 않는다,고양이 == 0
            TC-999,,숲,무엇,무엇,
            ${a[CaseFileColumn.NUMBER]},,광장,또 적은 A,무엇,
        """.trimIndent() + "\n"
        val preview = post(world.token, "$base/imports/preview?gameBuildId=${world.current}", edited.toByteArray())
        val byLine = preview["rows"].associateBy { it["line"].asInt() }
        assertThat(byLine.getValue(2)["status"].asText()).isEqualTo("EDIT")
        assertThat(byLine.getValue(2)["changes"].map { it.asText() }).containsExactly("조작")
        assertThat(byLine.getValue(3)["status"].asText()).isEqualTo("EDIT")
        assertThat(byLine.getValue(3)["changes"].map { it.asText() }).containsExactly(CaseFileService.IN_VERSION)
        assertThat(byLine.getValue(4)["status"].asText()).isEqualTo("NEW")
        assertThat(byLine.getValue(5)["kind"].asText()).isEqualTo("EXCEPTION")
        assertThat(byLine.getValue(6)["reason"].asText()).contains("TC-999")
        assertThat(byLine.getValue(7)["reason"].asText()).contains("2번째 줄")
        assertThat(preview["counts"].toString()).isEqualTo("""{"new":2,"edit":2,"same":0,"rejected":2}""")

        val result = post(world.token, "$base/imports?gameBuildId=${world.current}", edited.toByteArray())
        assertThat(result["created"]).hasSize(2)
        assertThat(result["updated"]).hasSize(2)
        assertThat(result["rejected"].map { it["line"].asInt() }).containsExactly(6, 7)
        assertThat(testCases.findById(world.a)!!.step).isEqualTo("할머니한테 다시 말을 건다")
        val aContract = contracts.list(world.projectId).cases.single { it.testCaseId == world.a.toString() }
        assertThat(aContract.needs).describedAs("파일에 계약 칸이 없어도 있던 계약은 그대로다").containsExactly("의뢰 == 0")
        assertThat(aContract.leaves).containsExactly("의뢰=1")
        val inCurrent = versions.cases(world.projectId, world.current, null, world.userId).items.map { it.id }
        assertThat(inCurrent).contains(world.c.toString()).containsAll(result["created"].map { it.asText() })
        assertThat(testCases.findById(result["created"][1].asText().toLong())!!.kind).isEqualTo("EXCEPTION")

        // 양식: 엑셀은 필터 · 고르기 목록이 든 빈 표.
        val template = WebClient.create("http://localhost:$port").get().uri("$base/imports/template?format=xlsx")
            .cookie("artel_access_token", world.token).retrieve().toEntity(ByteArray::class.java).block()!!
        assertThat(template.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION)).contains(".xlsx")
        assertThat(CaseFileCodec.read(template.body!!, null)).isEmpty()

        // "버전 없음" 에 넣으면 어느 버전에도 없다.
        val loose = post(world.token, "$base/imports?gameBuildId=unversioned", "화면,조작,기대결과\n광장,게시판을 본다,의뢰 목록이 보인다\n".toByteArray())
        val looseId = loose["created"].single().asText()
        assertThat(versions.unversioned(world.projectId, world.userId).items.map { it.id }).contains(looseId)

        // 칸이 빠진 파일은 무엇이 없는지 400 으로 말한다.
        val bad = exchange(world.token, "$base/imports/preview?gameBuildId=${world.current}", "화면,설명\n광장,무엇\n".toByteArray())
        assertThat(bad.first).isEqualTo(400)
        assertThat(bad.second.toString()).contains("조작 · 기대결과")
    }

    private data class World(
        val userId: Long, val token: String, val projectId: Long, val current: Long, val a: Long, val b: Long, val c: Long,
    )

    private suspend fun world(): World {
        val user = oauthUserService.upsert(OAuthIdentity("github", UUID.randomUUID().toString(), "files", "Files", null, null))
        val userId = user.userId.toLong()
        val now = Instant.now()
        val projectId = projectRepository.save(ProjectEntity(name = "파일 시험", genre = "RPG", createdAt = now, updatedAt = now)).id!!
        projectMemberRepository.save(ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now))
        val older = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.0.0", createdAt = now.minusSeconds(60), updatedAt = now)).id!!
        val current = gameBuilds.save(GameBuildEntity(projectId = projectId, version = "1.1.0", createdAt = now, updatedAt = now)).id!!
        val made = batch.save(projectId, userId, CaseBatchRequest(
            cases = listOf(
                CaseBatchItem(scene = "광장", step = "할머니한테 말을 건다", expectedValue = "의뢰를 받는다",
                    needs = listOf("의뢰 == 0"), leaves = listOf("의뢰=1"), arrivesAt = "광장"),
                CaseBatchItem(scene = "지하실", step = "열쇠 없이 문을 연다", expectedValue = "열리지 않는다", kind = "EXCEPTION"),
            ),
            gameBuildId = current.toString(),
        )).created
        val c = batch.save(projectId, userId, CaseBatchRequest(
            cases = listOf(CaseBatchItem(scene = "여관", step = "저녁까지 쉰다", expectedValue = "밤이 된다")),
            gameBuildId = older.toString(),
        )).created.single()
        return World(userId, jwtService.issue(user), projectId, current, made[0].toLong(), made[1].toLong(), c.toLong())
    }

    private suspend fun numberOf(world: World, testCaseId: Long): String {
        val bytes = download(world.token, "/api/projects/${world.projectId}/test-cases/exports", """{"format":"csv"}""").bytes
        return CaseFileCodec.read(bytes, null).single { it[CaseFileColumn.STEP] == testCases.findById(testCaseId)!!.step }[CaseFileColumn.NUMBER]
    }

    private class Download(val bytes: ByteArray, val disposition: String)

    private fun download(token: String, uri: String, json: String): Download {
        val entity = WebClient.create("http://localhost:$port").post().uri(uri)
            .cookie("artel_access_token", token).contentType(MediaType.APPLICATION_JSON).bodyValue(json)
            .retrieve().toEntity(ByteArray::class.java).block()!!
        return Download(entity.body!!, entity.headers.getFirst(HttpHeaders.CONTENT_DISPOSITION).orEmpty())
    }

    private fun post(token: String, uri: String, bytes: ByteArray): JsonNode {
        val (status, body) = exchange(token, uri, bytes)
        assertThat(status).describedAs(body.toString()).isEqualTo(200)
        return body
    }

    private fun exchange(token: String, uri: String, bytes: ByteArray): Pair<Int, JsonNode> =
        WebClient.create("http://localhost:$port").post().uri(uri)
            .cookie("artel_access_token", token).contentType(MediaType.APPLICATION_OCTET_STREAM).bodyValue(bytes)
            .exchangeToMono { response ->
                response.bodyToMono(String::class.java).defaultIfEmpty("null")
                    .map { response.statusCode().value() to objectMapper.readTree(it) }
            }.block()!!
}
