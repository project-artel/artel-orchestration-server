package kr.artel.orchestration.contentmap

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.ConflictException
import kr.artel.orchestration.contentmap.scan.ContentMapScanService
import kr.artel.orchestration.contentmap.scan.ScanState
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.sdk.service.SessionManager
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

    /** 남의 프로젝트 빌드는 프로젝트 id 를 맞춰 보내도 null 이다. */
    @Test
    fun `남의 프로젝트 빌드에는 스캔을 못 시킨다`(): Unit = runBlocking {
        val owner = newUser()
        val stranger = newUser()
        val projectId = newProject(owner)
        val buildId = newBuild(projectId)

        assertThat(scan.startScan(stranger, projectId, buildId)).isNull()
    }

    // ---------- 픽스처 ----------

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('console') RETURNING id")
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
