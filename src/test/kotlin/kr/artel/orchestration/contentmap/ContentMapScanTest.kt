package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.scan.ContentMapScanService
import kr.artel.orchestration.contentmap.scan.ScanPhase
import kr.artel.orchestration.contentmap.scan.ScanState
import kr.artel.orchestration.contentmap.scan.ScanStatus
import kr.artel.orchestration.contentmap.scan.ScanStatusRegistry
import kr.artel.orchestration.contentmap.scan.ScanTestCases
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.sdk.service.SessionManager
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.mockito.Mockito
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import reactor.test.StepVerifier
import java.time.Duration
import java.time.Instant

/**
 * 버튼 하나가 **붙어 있는 게임에 스캔을 시킨다.**
 *
 * 이 저장소에는 빌드에서 살아 있는 인스턴스로 가는 FK 경로가 없다 — 세션은 `gameInstanceId` 로
 * 묶이고 근거 문서는 `gameBuildId` 로 묶인다. 그래서 `game_instance.last_game_build_id` 를 거슬러
 * 올라가고, 붙어 있는지는 DB 가 아니라 `SessionManager` 가 안다. **그 두 단계가 다 맞아야** 명령이
 * 나가므로 여기서 함께 짚는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapScanTest {

    @Autowired private lateinit var scan: ContentMapScanService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository
    @Autowired private lateinit var statuses: ScanStatusRegistry

    private val objectMapper = ObjectMapper()

    /**
     * 붙어 있는 인스턴스에 `scan_evidence` 가 나가고, 응답이 **어느 인스턴스인지** 말한다.
     *
     * 응답이 인스턴스를 말하지 않으면 같은 빌드를 두 대에서 돌리는 흔한 경우에 사람은 자기가 보고
     * 있는 게임이 명령을 받았는지 알 수 없다.
     *
     * 액션 이름을 문자열로 단언하는 이유: 이것은 ARTEL-491(SDK) 과 맞춘 계약이고, 여기서 바뀌면
     * 상대편은 컴파일 오류 없이 조용히 못 알아듣는다.
     */
    @Test
    fun `붙어 있는 인스턴스에 scan_evidence 가 나간다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId, name = "Editor - MacBook")

        val outbound = requireNotNull(sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java)))

        val status = scan.startScan(userId, projectId, buildId)

        assertThat(status).isNotNull()
        assertThat(status!!.gameInstanceId).isEqualTo(instanceId)
        assertThat(status.gameInstanceName).isEqualTo("Editor - MacBook")
        // 202 는 "받았다"이지 "끝났다"가 아니다.
        assertThat(status.state).isEqualTo(ScanState.REQUESTED)
        assertThat(status.finishedAt).isNull()

        StepVerifier.create(outbound)
            .assertNext { json ->
                val sent = objectMapper.readTree(json)
                assertThat(sent["type"].asText()).isEqualTo("ACTION")
                val action = sent["actions"][0]
                assertThat(action["method"].asText()).isEqualTo("scan_evidence")
                // 파라미터는 없다 — SDK 가 자기 gameBuildId 를 안다.
                assertThat(action["params"]).isEmpty()
            }
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /**
     * 인스턴스는 있는데 **안 붙어 있으면 409** 다. 404 가 아니다.
     *
     * 이 구분이 이 엔드포인트의 요점이다. 둘 다 404 로 뭉개면 화면은 "빌드가 없다"와 "게임이 안 켜져
     * 있다"를 가를 수 없어, 버튼을 비활성으로 두면서 그 이유를 말할 수 없다. QA 가 이미 같은 자리에서
     * 같은 선택을 했다(`QaTryService` 의 "Game instance SDK is not connected").
     */
    @Test
    fun `게임이 안 붙어 있으면 409 다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        newInstance(projectId, buildId)   // 행은 있지만 세션이 없다

        assertThatThrownBy { runBlocking { scan.startScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)
            .hasMessageContaining("붙어 있지 않습니다")
    }

    /** 이 빌드를 물고 있는 인스턴스가 아예 없어도 409 다 — 빌드는 존재하기 때문이다. */
    @Test
    fun `빌드를 물고 있는 인스턴스가 없어도 409 다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)

        assertThatThrownBy { runBlocking { scan.startScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)
    }

    /**
     * 경로의 `projectId` 가 그 빌드의 것과 다르면 null(→ 404).
     *
     * **사용자는 두 프로젝트 모두의 멤버다** — 즉 권한이 아니라 **경로가 어긋난 것**을 잡는지 본다.
     * 권한으로만 막으면 한 사람이 여러 프로젝트에 속한 흔한 경우에 이 검사가 통째로 무력해지고,
     * `projectId` 는 장식이 된다.
     *
     * 세션까지 붙여 두는 것이 요점이다. 접근 검사가 인스턴스 고르기보다 **먼저** 오지 않으면,
     * 남의 빌드에 스캔 명령이 실제로 나간 뒤에 404 를 답하게 된다.
     */
    @Test
    fun `경로의 프로젝트가 다르면 스캔도 안 된다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val buildId = newBuild(mine)
        val instanceId = newInstance(mine, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        assertThat(scan.startScan(userId, other, buildId)).isNull()

        // 정상 경로는 막히지 않는다 — 검사를 더하면서 버튼 자체를 죽이면 안 된다.
        assertThat(scan.startScan(userId, mine, buildId)).isNotNull()

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /**
     * 스캔하고 TC 생성(ARTEL-1040)은 TC 를 못 만들 build 면 **스캔을 보내기 전에** 409 다. 게임이 스캔을 마친 뒤에
     * 막히면 그 수고가 버려진다.
     */
    @Test
    fun `이미 TC 를 만든 build 에는 스캔하고 TC 생성을 시킬 수 없다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        val map = contentMaps.save(ContentMapEntity(gameBuildId = buildId))
        val testCase = testCases.save(TestCaseEntity(projectId = projectId, scene = "S", step = "지도", expectedValue = "x"))
        versions.save(TestCaseVersionEntity.snapshotOf(testCase, buildId).copy(contentMapId = map.id))

        assertThatThrownBy { runBlocking { scan.startScan(userId, projectId, buildId, ScanTestCases(carryOver = true)) } }
            .isInstanceOf(ConflictException::class.java)
            .extracting("code").isEqualTo("TEST_CASES_ALREADY_GENERATED")
        // 그냥 스캔은 막지 않는다.
        assertThat(scan.startScan(userId, projectId, buildId)).isNotNull()

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /**
     * 같은 빌드에 스캔이 돌고 있으면 두 번째 요청은 **게임에 나가지 않고** 409 다(ARTEL-1178).
     *
     * 나가는 줄을 함께 세는 것이 요점이다. 409 만 보면 "답은 거절인데 액션은 나갔다" 를 놓친다 — SDK 는 받은 대로
     * 처음부터 다시 돈다.
     */
    @Test
    fun `같은 빌드에 스캔이 돌고 있으면 두 번째 요청은 409 다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        val outbound = requireNotNull(sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java)))

        assertThat(scan.startScan(userId, projectId, buildId)).isNotNull()
        assertThatThrownBy { runBlocking { scan.startScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)
            .extracting("code").isEqualTo(ContentMapScanService.SCAN_IN_PROGRESS)

        StepVerifier.create(outbound)
            .expectNextCount(1)
            .expectNoEvent(Duration.ofMillis(300))
            .thenCancel()
            .verify(Duration.ofSeconds(5))

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 거의 같은 때 들어온 요청 여럿 중 하나만 통과한다. 보기와 넣기가 갈라져 있으면 여럿이 통과한다. */
    @Test
    fun `동시에 여러 번 눌러도 한 건만 나간다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        val outcomes = (1..8).map {
            async(Dispatchers.Default) { runCatching { scan.startScan(userId, projectId, buildId) } }
        }.awaitAll()

        assertThat(outcomes.count { it.isSuccess }).isEqualTo(1)
        assertThat(outcomes.mapNotNull { (it.exceptionOrNull() as? ConflictException)?.code }.toSet())
            .containsExactly(ContentMapScanService.SCAN_IN_PROGRESS)

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 앞 스캔을 받은 게임이 끊겼으면 결과는 영영 안 온다. 그 스캔이 버튼을 막으면 안 된다. */
    @Test
    fun `앞 스캔을 받은 게임이 끊겼으면 다시 시킬 수 있다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val gone = newInstance(projectId, buildId, name = "끊긴 게임")
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        statuses.put(requested(buildId, gone, Instant.now()))

        val status = scan.startScan(userId, projectId, buildId)

        assertThat(status!!.gameInstanceId).isEqualTo(instanceId)
        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 게임이 붙어 있어도 [ContentMapScanService.STALE_AFTER] 동안 소식이 없던 스캔은 막지 않는다. */
    @Test
    fun `오래 소식이 없는 스캔은 다음 요청을 막지 않는다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        val longAgo = Instant.now().minus(ContentMapScanService.STALE_AFTER).minusSeconds(1)
        statuses.put(requested(buildId, instanceId, longAgo))

        assertThat(scan.startScan(userId, projectId, buildId)).isNotNull()

        // 소식이 막 있었던 스캔은 여전히 막는다.
        statuses.put(requested(buildId, instanceId, Instant.now()))
        assertThatThrownBy { runBlocking { scan.startScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 남의 프로젝트 빌드는 프로젝트 id 를 맞춰 보내도 null 이다. */
    @Test
    fun `남의 프로젝트 빌드에는 스캔을 못 시킨다`(): Unit = runBlocking {
        val owner = newUser()
        val stranger = newUser()
        val projectId = newProject(owner)
        val buildId = newBuild(projectId)

        assertThat(scan.startScan(stranger, projectId, buildId)).isNull()
    }

    /** 게임이 쥔 단계에서는 끊을 수 있고, 그 스캔은 취소로 닫힌다. */
    @Test
    fun `도는 스캔을 끊으면 취소로 닫힌다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        assertThat(scan.startScan(userId, projectId, buildId)).isNotNull()

        val cancelled = scan.cancelScan(userId, projectId, buildId)
        assertThat(cancelled).isNotNull()
        assertThat(cancelled!!.state).isEqualTo(ScanState.FAILED)
        assertThat(cancelled.cancelled).isTrue()
        assertThat(cancelled.error).contains("취소")

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 끊고 나면 다음 요청이 풀린다. 그것이 취소를 만든 이유다. */
    @Test
    fun `끊은 뒤에는 같은 빌드에 다시 시킬 수 있다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        scan.startScan(userId, projectId, buildId)
        scan.cancelScan(userId, projectId, buildId)

        assertThat(scan.startScan(userId, projectId, buildId)).isNotNull()

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 서버가 문서를 읽는 중에는 끊을 손잡이가 없다. 그 사실을 409 로 말한다. */
    @Test
    fun `서버가 읽는 단계에서는 끊을 수 없다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        scan.startScan(userId, projectId, buildId)
        statuses.advance(buildId) { it.copy(phase = ScanPhase.INGESTING) }

        assertThatThrownBy { runBlocking { scan.cancelScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)
            .extracting("code").isEqualTo(ContentMapScanService.SCAN_NOT_CANCELLABLE)

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /** 돌고 있는 것이 없는데 끊으라고 하면 화면이 낡은 것이다. 조용히 넘기지 않는다. */
    @Test
    fun `돌고 있는 스캔이 없으면 끊기도 409 다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)

        assertThatThrownBy { runBlocking { scan.cancelScan(userId, projectId, buildId) } }
            .isInstanceOf(ConflictException::class.java)
            .extracting("code").isEqualTo(ContentMapScanService.SCAN_NOT_RUNNING)
    }

    /** 남의 프로젝트 빌드는 끊지도 못한다. 404 가 되도록 null 이다. */
    @Test
    fun `남의 프로젝트 빌드는 끊지 못한다`(): Unit = runBlocking {
        val owner = newUser()
        val projectId = newProject(owner)
        val buildId = newBuild(projectId)

        assertThat(scan.cancelScan(newUser(), projectId, buildId)).isNull()
    }

    /**
     * 끊은 스캔이 올려 둔 접수증은 대기에서 빠지고, 앞선 스캔이 남긴 것은 그대로 있다.
     *
     * 남겨 두면 다음 스캔의 적재가 반쪽 순회의 문서까지 같이 앉힌다. 그렇다고 통째로 지우면 앞선 스캔에서 깨져
     * 다시 시도하려고 남아 있는 문서까지 날아간다.
     */
    @Test
    fun `끊으면 이 스캔이 올린 문서만 대기에서 뺀다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))
        val map = contentMaps.save(ContentMapEntity(gameBuildId = buildId))

        // 앞선 스캔이 남긴 것. 요청보다 전에 받았다.
        val older = documents.save(document(map.id!!, Instant.now().minusSeconds(600)))

        val started = requireNotNull(scan.startScan(userId, projectId, buildId))
        // 이 스캔이 올린 것.
        val mine = documents.save(document(map.id!!, started.requestedAt.plusSeconds(1)))

        scan.cancelScan(userId, projectId, buildId)

        assertThat(documents.findById(mine.id!!)).isNull()
        assertThat(documents.findById(older.id!!)).isNotNull()

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    // ---------- 픽스처 ----------

    private fun document(contentMapId: Long, receivedAt: Instant) = ContentMapDocumentEntity(
        contentMapId = contentMapId,
        objectKey = "evidence/${System.nanoTime()}.json",
        contentHash = "%064x".format(System.nanoTime()),
        byteSize = 10,
        receivedAt = receivedAt,
    )

    private fun requested(gameBuildId: Long, gameInstanceId: Long, at: Instant) = ScanStatus(
        gameBuildId = gameBuildId,
        gameInstanceId = gameInstanceId,
        gameInstanceName = "instance",
        state = ScanState.REQUESTED,
        requestedAt = at,
    )

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "scan-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        return project.id
    }

    private suspend fun newBuild(projectId: Long): Long {
        val now = Instant.now()
        return gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        ).id!!
    }

    private suspend fun newInstance(projectId: Long, gameBuildId: Long, name: String = "instance"): Long {
        val now = Instant.now()
        return gameInstances.save(
            GameInstanceEntity(
                projectId = projectId,
                name = name,
                platform = "UNITY",
                lastGameBuildId = gameBuildId,
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        ).id!!
    }
}
