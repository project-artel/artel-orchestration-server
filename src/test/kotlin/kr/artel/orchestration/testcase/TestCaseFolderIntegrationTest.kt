package kr.artel.orchestration.testcase

import com.fasterxml.jackson.databind.JsonNode
import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseFolderRepository
import kr.artel.orchestration.testcase.repository.TestCaseRepository
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
import java.time.Instant
import java.util.UUID

/**
 * test case folder API 와 `/test-cases/move` 통합 테스트(ARTEL-969). HTTP 로 불러 상태 코드와 본문
 * 모양을 같이 확인한다 — home 이 이 모양을 그대로 쓴다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class TestCaseFolderIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var projectMemberRepository: ProjectMemberRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var folderRepository: TestCaseFolderRepository
    @Autowired private lateinit var db: DatabaseClient

    @Test
    fun `만든 folder 가 목록에 문자열 id 와 null 칸으로 나온다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)

        val root = createFolder(token, projectId, "로그인", null).body
        val child = createFolder(token, projectId, "실패", root["id"].asText()).body

        assertThat(root["id"].isTextual).isTrue()
        assertThat(root["parentId"].isNull).isTrue()
        assertThat(root["contentMapId"].isNull).isTrue()
        assertThat(root["gameBuildId"].isNull).isTrue()
        assertThat(child["parentId"].asText()).isEqualTo(root["id"].asText())

        val items = call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-folders").body["items"]
        assertThat(items.map { it["name"].asText() }).containsExactly("로그인", "실패")
        assertThat(items.first().fieldNames().asSequence().toSet())
            .containsExactlyInAnyOrder("id", "parentId", "name", "contentMapId", "gameBuildId")
    }

    @Test
    fun `content map 에 매단 folder 는 contentMapId 와 gameBuildId 를 낸다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val gameBuildId = insertId(
            "INSERT INTO game_build (project_id, version) VALUES ($projectId, '1.0.0') RETURNING id"
        )
        val contentMapId = insertId(
            """
            INSERT INTO content_map (game_build_id, schema_version, capture, evidence_digest)
            VALUES ($gameBuildId, 1, 'editor', 'digest') RETURNING id
            """
        )
        insertId(
            """
            INSERT INTO test_case_folder (project_id, name, content_map_id)
            VALUES ($projectId, '1.0.0 content map', $contentMapId) RETURNING id
            """
        )

        val item = call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-folders").body["items"].single()

        assertThat(item["contentMapId"].asText()).isEqualTo(contentMapId.toString())
        assertThat(item["gameBuildId"].asText()).isEqualTo(gameBuildId.toString())
    }

    @Test
    fun `같은 부모 아래 같은 이름은 409 이고 다른 부모 아래는 받는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val first = createFolder(token, projectId, "전투", null).body["id"].asText()
        val second = createFolder(token, projectId, "상점", null).body["id"].asText()
        createFolder(token, projectId, "보스", first)

        assertThat(createFolder(token, projectId, "전투", null).status).isEqualTo(409)
        assertThat(createFolder(token, projectId, " 전투 ", null).status).isEqualTo(409)
        assertThat(createFolder(token, projectId, "보스", first).status).isEqualTo(409)
        assertThat(createFolder(token, projectId, "보스", second).status).isEqualTo(201)
        // 대소문자는 구분한다.
        assertThat(createFolder(token, projectId, "Boss", first).status).isEqualTo(201)
        assertThat(createFolder(token, projectId, "boss", first).status).isEqualTo(201)
    }

    @Test
    fun `하위 folder 를 최상위로 옮길 때 최상위에 같은 이름이 있으면 409 다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val parent = createFolder(token, projectId, "전투", null).body["id"].asText()
        createFolder(token, projectId, "보스", null)
        val child = createFolder(token, projectId, "보스", parent).body["id"].asText()

        assertThat(updateFolder(token, projectId, child, "보스", null).status).isEqualTo(409)
        // 자기 이름을 그대로 두고 같은 자리에 다시 저장하는 것은 중복이 아니다.
        assertThat(updateFolder(token, projectId, child, "보스", parent).body["name"].asText()).isEqualTo("보스")
    }

    @Test
    fun `자기 자신이나 하위 folder 아래로 옮기면 400 이고 형제 아래로는 옮긴다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val grandparent = createFolder(token, projectId, "A", null).body["id"].asText()
        val parent = createFolder(token, projectId, "B", grandparent).body["id"].asText()
        val grandchild = createFolder(token, projectId, "C", parent).body["id"].asText()
        val sibling = createFolder(token, projectId, "D", null).body["id"].asText()

        assertThat(updateFolder(token, projectId, grandparent, "A", grandparent).status).isEqualTo(400)
        assertThat(updateFolder(token, projectId, grandparent, "A", grandchild).status).isEqualTo(400)

        val moved = updateFolder(token, projectId, grandparent, "A2", sibling).body
        assertThat(moved["parentId"].asText()).isEqualTo(sibling)
        assertThat(moved["name"].asText()).isEqualTo("A2")
    }

    @Test
    fun `folder 를 지우면 하위 folder 까지 사라지고 test case 는 folder 없음으로 남는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val top = createFolder(token, projectId, "A", null).body["id"].asText()
        val middle = createFolder(token, projectId, "B", top).body["id"].asText()
        val bottom = createFolder(token, projectId, "C", middle).body["id"].asText()
        val caseIds = listOf(top, middle, bottom).map { folderId -> newCase(projectId, folderId.toLong()) }

        assertThat(call(token, HttpMethod.DELETE, "/api/projects/$projectId/test-case-folders/$top").status)
            .isEqualTo(204)

        assertThat(call(token, HttpMethod.GET, "/api/projects/$projectId/test-case-folders").body["items"]).isEmpty()
        caseIds.forEach { caseId ->
            val case = testCaseRepository.findById(caseId)
            assertThat(case).isNotNull
            assertThat(case!!.folderId).isNull()
        }
    }

    @Test
    fun `move 는 이 프로젝트 test case 만 옮기고 null 은 folder 없음이다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val otherProjectId = newProject(signIn().first)
        val folderId = createFolder(token, projectId, "전투", null).body["id"].asText()
        val first = newCase(projectId, null)
        val second = newCase(projectId, null)
        val foreign = newCase(otherProjectId, null)

        val moved = move(token, projectId, """{"caseIds":["$first","$second","$second","$foreign","999999999"],"folderId":"$folderId"}""").body

        assertThat(moved["moved"].asInt()).isEqualTo(2)
        assertThat(testCaseRepository.findById(first)!!.folderId).isEqualTo(folderId.toLong())
        assertThat(testCaseRepository.findById(foreign)!!.folderId).isNull()

        val back = move(token, projectId, """{"caseIds":["$first"],"folderId":null}""").body
        assertThat(back["moved"].asInt()).isEqualTo(1)
        assertThat(testCaseRepository.findById(first)!!.folderId).isNull()

        assertThat(move(token, projectId, """{"caseIds":[],"folderId":null}""").status).isEqualTo(400)
        assertThat(move(token, projectId, """{"caseIds":["$first"],"folderId":"abc"}""").status).isEqualTo(400)
    }

    @Test
    fun `다른 프로젝트 folder 는 404 다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val (otherUserId, otherToken) = signIn()
        val otherProjectId = newProject(otherUserId)
        val foreignFolder = createFolder(otherToken, otherProjectId, "남의 것", null).body["id"].asText()
        val caseId = newCase(projectId, null)

        assertThat(move(token, projectId, """{"caseIds":["$caseId"],"folderId":"$foreignFolder"}""").status)
            .isEqualTo(404)
        assertThat(createFolder(token, projectId, "하위", foreignFolder).status).isEqualTo(404)
        assertThat(updateFolder(token, projectId, foreignFolder, "바꿈", null).status).isEqualTo(404)
        assertThat(call(token, HttpMethod.DELETE, "/api/projects/$projectId/test-case-folders/$foreignFolder").status)
            .isEqualTo(404)
        assertThat(createCase(token, projectId, foreignFolder).status).isEqualTo(404)
        assertThat(folderRepository.existsById(foreignFolder.toLong())).isTrue()
    }

    @Test
    fun `비멤버는 folder API 와 move 모두 404 다`(): Unit = runBlocking {
        val (ownerId, ownerToken) = signIn()
        val projectId = newProject(ownerId)
        val folderId = createFolder(ownerToken, projectId, "전투", null).body["id"].asText()
        val caseId = newCase(projectId, null)
        val (_, outsiderToken) = signIn()

        assertThat(call(outsiderToken, HttpMethod.GET, "/api/projects/$projectId/test-case-folders").status).isEqualTo(404)
        assertThat(createFolder(outsiderToken, projectId, "새", null).status).isEqualTo(404)
        assertThat(updateFolder(outsiderToken, projectId, folderId, "새", null).status).isEqualTo(404)
        assertThat(call(outsiderToken, HttpMethod.DELETE, "/api/projects/$projectId/test-case-folders/$folderId").status)
            .isEqualTo(404)
        assertThat(move(outsiderToken, projectId, """{"caseIds":["$caseId"],"folderId":null}""").status).isEqualTo(404)
        assertThat(folderRepository.existsById(folderId.toLong())).isTrue()
    }

    @Test
    fun `test case 생성 요청의 folderId 가 응답과 목록에 나오고 수정해도 남는다`(): Unit = runBlocking {
        val (userId, token) = signIn()
        val projectId = newProject(userId)
        val folderId = createFolder(token, projectId, "전투", null).body["id"].asText()

        val created = createCase(token, projectId, folderId).body
        assertThat(created["folderId"].asText()).isEqualTo(folderId)

        val withoutFolder = createCase(token, projectId, "").body
        assertThat(withoutFolder["folderId"].isNull).isTrue()
        assertThat(createCase(token, projectId, "abc").status).isEqualTo(400)

        val caseId = created["id"].asText()
        call(token, HttpMethod.PUT, "/api/projects/$projectId/test-cases/$caseId", """{"step":"바뀐 행동"}""")
        val listed = call(token, HttpMethod.GET, "/api/projects/$projectId/test-cases").body["items"]
            .single { it["id"].asText() == caseId }
        assertThat(listed["step"].asText()).isEqualTo("바뀐 행동")
        assertThat(listed["folderId"].asText()).isEqualTo(folderId)
    }

    private suspend fun signIn(): Pair<Long, String> {
        val providerUserId = UUID.randomUUID().toString()
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = "folder-$providerUserId",
                displayName = "Folder",
                avatarUrl = null,
                email = null,
            )
        )!!
        return user.userId.toLong() to jwtService.issue(user)
    }

    private suspend fun newProject(memberId: Long): Long {
        val now = Instant.now()
        val projectId = projectRepository.save(
            ProjectEntity(name = "folders", genre = "ACTION", createdAt = now, updatedAt = now)
        ).id!!
        projectMemberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = memberId, role = "OWNER", createdAt = now)
        )
        return projectId
    }

    private suspend fun newCase(projectId: Long, folderId: Long?): Long =
        testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId, scene = "Title", step = "시작", expectedValue = "넘어간다", folderId = folderId,
            )
        ).id!!

    private fun insertId(sql: String): Long =
        db.sql(sql).map { row, _ -> (row.get(0) as Number).toLong() }.one().block()!!

    private fun createFolder(token: String, projectId: Long, name: String, parentId: String?): Reply =
        call(token, HttpMethod.POST, "/api/projects/$projectId/test-case-folders", folderBody(name, parentId))

    private fun updateFolder(token: String, projectId: Long, folderId: String, name: String, parentId: String?): Reply =
        call(token, HttpMethod.PUT, "/api/projects/$projectId/test-case-folders/$folderId", folderBody(name, parentId))

    private fun folderBody(name: String, parentId: String?): String =
        objectMapper.writeValueAsString(mapOf("name" to name, "parentId" to parentId))

    private fun move(token: String, projectId: Long, body: String): Reply =
        call(token, HttpMethod.POST, "/api/projects/$projectId/test-cases/move", body)

    private fun createCase(token: String, projectId: Long, folderId: String): Reply =
        call(
            token, HttpMethod.POST, "/api/projects/$projectId/test-cases",
            objectMapper.writeValueAsString(
                mapOf("scene" to "Title", "step" to "시작", "expectedValue" to "넘어간다", "folderId" to folderId)
            ),
        )

    /** 실패 상태도 예외 없이 돌려받는다. 상태 코드가 이 API 계약의 절반이다. */
    private fun call(token: String, method: HttpMethod, uri: String, body: String? = null): Reply {
        val request = WebClient.create("http://localhost:$port").method(method).uri(uri)
            .cookie("artel_access_token", token)
        val withBody = if (body == null) request else request.contentType(MediaType.APPLICATION_JSON).bodyValue(body)
        return withBody.exchangeToMono { response ->
            response.bodyToMono(String::class.java).defaultIfEmpty("null")
                .map { text -> Reply(response.statusCode().value(), objectMapper.readTree(text)) }
        }.block()!!
    }

    private data class Reply(val status: Int, val body: JsonNode)
}
