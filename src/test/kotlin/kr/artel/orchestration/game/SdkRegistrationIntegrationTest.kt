package kr.artel.orchestration.game

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.repository.AppUserRepository
import kr.artel.orchestration.auth.repository.OAuthIdentityRepository
import kr.artel.orchestration.auth.service.AuthenticatedUser
import kr.artel.orchestration.auth.service.JwtService
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.repository.ProjectDocumentRepository
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import kr.artel.orchestration.testcase.service.ManualCaseVersionService
import kr.artel.orchestration.testcase.dto.TestCaseVersionCasesRequest
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpHeaders
import org.springframework.http.HttpStatus
import org.springframework.http.MediaType
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException

/**
 * SDK 등록은 로그인해서 받은 SDK 토큰으로 통과한다. 인스턴스는 대시보드가 아니라 이 호출이
 * 만들고, (프로젝트, sdkUuid)가 그 정체성이다. 같은 호출이 게임 버전 보고까지 겸한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SdkRegistrationIntegrationTest {

    @LocalServerPort
    private val port: Int = 0

    @Autowired private lateinit var jwtService: JwtService
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var instanceRepository: GameInstanceRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var documentRepository: ProjectDocumentRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var appUserRepository: AppUserRepository
    @Autowired private lateinit var identityRepository: OAuthIdentityRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var versionRepository: TestCaseVersionRepository
    @Autowired private lateinit var manualCaseVersions: ManualCaseVersionService

    @BeforeEach
    fun clean(): Unit = runBlocking {
        instanceRepository.deleteAll()
        buildRepository.deleteAll()
        documentRepository.deleteAll()
        memberRepository.deleteAll()
        projectRepository.deleteAll()
        identityRepository.deleteAll()
        appUserRepository.deleteAll()
    }

    @Test
    fun `creates the instance on first registration and records the reported version`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val registered = register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3", instanceName = "내 맥북")

        assertThat(registered["projectId"].asText()).isEqualTo(projectId)
        assertThat(registered["instanceName"].asText()).isEqualTo("내 맥북")
        assertThat(registered["gameVersion"].asText()).isEqualTo("1.2.3")

        val listed = get(user.webToken, "/api/projects/$projectId/game-instances")["items"]
        assertThat(listed).hasSize(1)
        assertThat(listed[0]["id"].asText()).isEqualTo(registered["instanceId"].asText())

        val builds = get(user.webToken, "/api/projects/$projectId/game-builds")
        assertThat(builds["items"]).hasSize(1)
        assertThat(builds["items"][0]["version"].asText()).isEqualTo("1.2.3")
        assertThat(builds["items"][0]["label"].isNull).isTrue()
    }

    /**
     * 지도 없는 프로젝트(자연어 TC 만 쓰는)는 처음 보는 버전이 등록되면 앞 버전의 사용자 생성 TC 가 따라온다. 같은 버전의
     * 재등록은 아무것도 넣지 않는다 — 사람이 그 버전에서 일부러 뺀 케이스가 되살아나면 안 된다.
     */
    @Test
    fun `a new version carries the user-written cases of the previous version`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)
        val first = register(user.sdkToken, projectId, "sdk-uuid-1", "1.0.0")["gameBuildId"].asLong()
        val case = testCaseRepository.save(
            TestCaseEntity(projectId = projectId.toLong(), scene = "광장", step = "상점에 들어간다", expectedValue = "상점 화면이 뜬다"),
        )
        try {
            manualCaseVersions.attachNewCase(projectId.toLong(), first, case.id!!)

            val second = register(user.sdkToken, projectId, "sdk-uuid-1", "1.1.0")["gameBuildId"].asLong()
            assertThat(versionRepository.findByTestCaseIdAndGameBuildId(case.id!!, second)).isNotNull

            manualCaseVersions.removeFromVersion(projectId.toLong(), user.user.userId.toLong(), second, TestCaseVersionCasesRequest(listOf(case.id.toString())))
            register(user.sdkToken, projectId, "sdk-uuid-1", "1.1.0")
            assertThat(versionRepository.findByTestCaseIdAndGameBuildId(case.id!!, second)).isNull()
        } finally {
            testCaseRepository.deleteById(case.id!!)
        }
    }

    /** 같은 설치본이 다시 실행됐을 뿐인데 인스턴스가 늘어나면 목록이 금세 쓸모없어진다. */
    @Test
    fun `reuses the instance when the same sdk uuid registers again`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val first = register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")
        val second = register(user.sdkToken, projectId, "sdk-uuid-1", "1.3.0")

        assertThat(second["instanceId"].asText()).isEqualTo(first["instanceId"].asText())
        assertThat(instanceRepository.count()).isEqualTo(1L)
    }

    @Test
    fun `returns a stable conflict when a retired sdk uuid registers again`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)
        val registered = register(user.sdkToken, projectId, "sdk-uuid-retired", "1.2.3")
        val instance = instanceRepository.findById(registered["instanceId"].asLong())!!
        instanceRepository.save(instance.copy(deletedAt = java.time.Instant.now()))

        val error = errorOf {
            register(user.sdkToken, projectId, "sdk-uuid-retired", "1.2.3")
        }

        assertThat(error.statusCode).isEqualTo(HttpStatus.CONFLICT)
        assertThat(objectMapper.readTree(error.responseBodyAsString)["code"].asText())
            .isEqualTo("SDK_INSTANCE_RETIRED")
    }

    @Test
    fun `creates a separate instance for a different sdk uuid`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val first = register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")
        val second = register(user.sdkToken, projectId, "sdk-uuid-2", "1.2.3")

        assertThat(second["instanceId"].asText()).isNotEqualTo(first["instanceId"].asText())
        assertThat(instanceRepository.count()).isEqualTo(2L)
    }

    /** 대시보드에서 고친 이름이 게임을 실행할 때마다 되돌아가면 안 된다. */
    @Test
    fun `keeps the dashboard name when the sdk reports a different one`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)
        val instanceId = register(user.sdkToken, projectId, "sdk-uuid-1", "1.0.0", instanceName = "처음 이름")["instanceId"].asText()

        patch(user.webToken, "/api/projects/$projectId/game-instances/$instanceId", """{"name":"내가 고친 이름"}""")
        val again = register(user.sdkToken, projectId, "sdk-uuid-1", "1.0.0", instanceName = "처음 이름")

        assertThat(again["instanceName"].asText()).isEqualTo("내가 고친 이름")
    }

    @Test
    fun `reuses the same build when the version has not changed`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val first = register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")
        val second = register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")

        assertThat(second["gameBuildId"].asText()).isEqualTo(first["gameBuildId"].asText())
        assertThat(buildRepository.count()).isEqualTo(1L)
    }

    @Test
    fun `creates a second build when the reported version changes`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")
        register(user.sdkToken, projectId, "sdk-uuid-1", "1.3.0")

        assertThat(get(user.webToken, "/api/projects/$projectId/game-builds")["items"]).hasSize(2)
    }

    @Test
    fun `records the runtime that registered so the dashboard can show it`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val registered = register(user.sdkToken, projectId, "sdk-uuid-9", "1.0.0")

        val listed = get(user.webToken, "/api/projects/$projectId/game-instances")["items"][0]
        assertThat(listed["lastConnectedAt"].isNull).isFalse()

        val stored = instanceRepository.findById(registered["instanceId"].asText().toLong())!!
        assertThat(stored.sdkUuid).isEqualTo("sdk-uuid-9")
    }

    @Test
    fun `stores the reported scene scan on the build`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        register(
            user.sdkToken,
            projectId,
            "sdk-uuid-1",
            "1.2.3",
            sceneScan = """{"scenesInBuild":["Main","Level1"],"scannedScenes":[{"name":"Main"}]}"""
        )

        val build = buildRepository.findAll().first()
        val stored = objectMapper.readTree(build.sceneScan!!.asString())
        assertThat(stored["scenesInBuild"][0].asText()).isEqualTo("Main")
        assertThat(stored["scannedScenes"]).hasSize(1)
    }

    @Test
    fun `overwrites the scene scan when the same build registers again`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3", sceneScan = """{"scenesInBuild":["Main"]}""")
        register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3", sceneScan = """{"scenesInBuild":["Main","Level1"]}""")

        assertThat(buildRepository.count()).isEqualTo(1L)
        val stored = objectMapper.readTree(buildRepository.findAll().first().sceneScan!!.asString())
        assertThat(stored["scenesInBuild"]).hasSize(2)
    }

    @Test
    fun `keeps the previous scene scan when a registration omits it`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3", sceneScan = """{"scenesInBuild":["Main"]}""")
        register(user.sdkToken, projectId, "sdk-uuid-1", "1.2.3")

        val build = buildRepository.findAll().first()
        assertThat(build.sceneScan).isNotNull()
    }

    /** editor 는 development build 와 같은 버전을 보고한다. 같은 행이면 editor 가 그 build 의 scan 을 덮어쓴다. */
    @Test
    fun `keeps the editor and a development build with the same version in separate builds`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val development = register(
            user.sdkToken, projectId, "sdk-uuid-player", "1.2.3",
            sceneScan = """{"scenesInBuild":["Main"]}""", isEditor = false
        )
        val editor = register(
            user.sdkToken, projectId, "sdk-uuid-editor", "1.2.3",
            sceneScan = """{"scenesInBuild":["Main","Work"]}""", isEditor = true
        )

        assertThat(editor["gameBuildId"].asText()).isNotEqualTo(development["gameBuildId"].asText())
        assertThat(development["isEditor"].asBoolean()).isFalse()
        assertThat(editor["isEditor"].asBoolean()).isTrue()
        assertThat(buildRepository.count()).isEqualTo(2L)
        val developmentBuild = buildRepository.findById(development["gameBuildId"].asLong())!!
        assertThat(objectMapper.readTree(developmentBuild.sceneScan!!.asString())["scenesInBuild"]).hasSize(1)

        // 그 뒤 development build 가 다시 등록돼도 editor 행이 아니라 자기 행으로 간다.
        val again = register(user.sdkToken, projectId, "sdk-uuid-player", "1.2.3")
        assertThat(again["gameBuildId"].asText()).isEqualTo(development["gameBuildId"].asText())
    }

    /** editor 도 version 마다 한 행이다(ARTEL-1180). 버전을 올리지 않은 재등록은 그 행의 스캔만 덮는다. */
    @Test
    fun `keeps one editor build per version and overwrites the scan within a version`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val first = register(
            user.sdkToken, projectId, "sdk-uuid-editor", "1.3.0",
            sceneScan = """{"scenesInBuild":["Main"]}""", isEditor = true
        )
        // 스캔 없는 재등록은 앞 스캔을 남긴다.
        val again = register(user.sdkToken, projectId, "sdk-uuid-editor", "1.3.0", isEditor = true)
        assertThat(again["gameBuildId"].asText()).isEqualTo(first["gameBuildId"].asText())
        assertThat(objectMapper.readTree(buildRepository.findById(first["gameBuildId"].asLong())!!.sceneScan!!.asString())["scenesInBuild"])
            .hasSize(1)
        // 같은 버전에서 바뀐 작업 상태는 그 행의 스캔을 덮는다. 다른 editor 인스턴스도 같은 행이다.
        val other = register(
            user.sdkToken, projectId, "sdk-uuid-other-editor", "1.3.0",
            sceneScan = """{"scenesInBuild":["Main","Work"]}""", isEditor = true
        )
        assertThat(other["gameBuildId"].asText()).isEqualTo(first["gameBuildId"].asText())
        assertThat(objectMapper.readTree(buildRepository.findById(first["gameBuildId"].asLong())!!.sceneScan!!.asString())["scenesInBuild"])
            .hasSize(2)

        // 버전을 올리면 새 행이다. 앞 버전 행은 그대로 남는다.
        val bumped = register(
            user.sdkToken, projectId, "sdk-uuid-editor", "1.4.0",
            sceneScan = """{"scenesInBuild":["Main","Level1"]}""", isEditor = true
        )
        assertThat(bumped["gameBuildId"].asText()).isNotEqualTo(first["gameBuildId"].asText())
        assertThat(bumped["gameVersion"].asText()).isEqualTo("1.4.0")
        val builds = buildRepository.findAll().toList()
        assertThat(builds.map { it.version to it.isEditor }).containsExactlyInAnyOrder("1.3.0" to true, "1.4.0" to true)
        assertThat(instanceRepository.findAll().toList().first { it.sdkUuid == "sdk-uuid-editor" }.lastGameBuildId)
            .isEqualTo(bumped["gameBuildId"].asLong())
    }

    /** isEditor 를 모르는 구버전 SDK 는 development build 로 다룬다. */
    @Test
    fun `treats a registration without isEditor as a development build`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val editor = register(user.sdkToken, projectId, "sdk-uuid-editor", "1.2.3", isEditor = true)
        val legacy = register(user.sdkToken, projectId, "sdk-uuid-legacy", "1.2.3")
        val legacyAgain = register(user.sdkToken, projectId, "sdk-uuid-legacy", "1.2.3")

        assertThat(legacy["isEditor"].asBoolean()).isFalse()
        assertThat(legacy["gameBuildId"].asText()).isNotEqualTo(editor["gameBuildId"].asText())
        assertThat(legacyAgain["gameBuildId"].asText()).isEqualTo(legacy["gameBuildId"].asText())
        assertThat(buildRepository.count()).isEqualTo(2L)
    }

    /** editor 행도 그 version 의 버전이라 만든 순서대로 섞인다(ARTEL-1180). */
    @Test
    fun `lists builds and editor builds newest first`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        register(user.sdkToken, projectId, "sdk-uuid-player", "1.0.0")
        register(user.sdkToken, projectId, "sdk-uuid-editor", "1.1.0", isEditor = true)
        register(user.sdkToken, projectId, "sdk-uuid-player", "1.1.0")
        register(user.sdkToken, projectId, "sdk-uuid-player", "1.2.0")

        val items = get(user.webToken, "/api/projects/$projectId/game-builds")["items"]
        assertThat(items.map { it["version"].asText() to it["isEditor"].asBoolean() }).containsExactly(
            "1.2.0" to false, "1.1.0" to false, "1.1.0" to true, "1.0.0" to false
        )
    }

    @Test
    fun `refuses a project the user does not belong to`(): Unit = runBlocking {
        val owner = signIn()
        val projectId = createProject(owner.webToken)
        val stranger = signIn(providerUserId = "77", login = "stranger")

        val error = errorOf { register(stranger.sdkToken, projectId, "sdk-uuid-1", "1.0.0") }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.NOT_FOUND.value())
    }

    /** 브라우저 세션 토큰은 audience가 달라 SDK 경로를 통과하지 못한다. */
    @Test
    fun `refuses a browser session token`(): Unit = runBlocking {
        val user = signIn()
        val projectId = createProject(user.webToken)

        val error = errorOf { register(user.webToken, projectId, "sdk-uuid-1", "1.0.0") }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    @Test
    fun `refuses a request without a token`(): Unit = runBlocking {
        val error = errorOf {
            objectMapper.readTree(
                client().post()
                    .uri("/api/sdk/registrations")
                    .contentType(MediaType.APPLICATION_JSON)
                    .bodyValue("""{"projectId":"1","sdkUuid":"sdk-uuid-1","gameVersion":"1.0.0"}""")
                    .retrieve()
                    .bodyToMono(String::class.java)
                    .block()
            )
        }

        assertThat(error.statusCode.value()).isEqualTo(HttpStatus.UNAUTHORIZED.value())
    }

    private fun register(
        sdkToken: String,
        projectId: String,
        sdkUuid: String,
        gameVersion: String,
        instanceName: String? = null,
        sceneScan: String? = null,
        isEditor: Boolean? = null
    ) = objectMapper.readTree(
        client().post()
            .uri("/api/sdk/registrations")
            .header(HttpHeaders.AUTHORIZATION, "Bearer $sdkToken")
            .contentType(MediaType.APPLICATION_JSON)
            .bodyValue(
                buildString {
                    append("""{"projectId":"$projectId","sdkUuid":"$sdkUuid","gameVersion":"$gameVersion"""")
                    if (instanceName != null) append(""","instanceName":"$instanceName"""")
                    if (sceneScan != null) append(""","sceneScan":$sceneScan""")
                    if (isEditor != null) append(""","isEditor":$isEditor""")
                    append("}")
                }
            )
            .retrieve()
            .bodyToMono(String::class.java)
            .block()
    )

    private fun createProject(token: String): String =
        post(token, "/api/projects", """{"name":"SDK 등록 테스트","genre":"ACTION"}""")["id"].asText()

    /** 브라우저 세션 토큰과 SDK 토큰을 한 번에 만든다. 둘은 audience가 다르다. */
    private data class SignedInUser(val user: AuthenticatedUser, val webToken: String, val sdkToken: String)

    private suspend fun signIn(providerUserId: String = "42", login: String = "octocat"): SignedInUser {
        val user = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github",
                providerUserId = providerUserId,
                login = login,
                displayName = login,
                avatarUrl = null,
                email = "$login@example.com"
            )
        )
        return SignedInUser(user, jwtService.issue(user), jwtService.issueSdkToken(user.userId).token)
    }

    private fun get(token: String, uri: String) = objectMapper.readTree(
        client().get().uri(uri).cookie("artel_access_token", token)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun post(token: String, uri: String, body: String) = objectMapper.readTree(
        client().post().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun patch(token: String, uri: String, body: String) = objectMapper.readTree(
        client().patch().uri(uri).cookie("artel_access_token", token)
            .contentType(MediaType.APPLICATION_JSON).bodyValue(body)
            .retrieve().bodyToMono(String::class.java).block()
    )

    private fun client() = WebClient.create("http://localhost:$port")

    private fun errorOf(call: () -> Any?): WebClientResponseException =
        try {
            call()
            error("요청이 실패해야 하는데 성공했다")
        } catch (error: WebClientResponseException) {
            error
        }
}
