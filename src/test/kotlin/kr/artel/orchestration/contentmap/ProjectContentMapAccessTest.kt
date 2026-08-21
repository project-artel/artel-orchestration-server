package kr.artel.orchestration.contentmap

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.dto.EvidenceUploadTicketRequest
import kr.artel.orchestration.contentmap.dto.RegisterEvidenceDocumentRequest
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.service.EvidenceDocumentService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 브라우저 경로는 `/projects/{projectId}/` 를 지나온다. **그 값을 실제로 검사하는가**를 본다.
 *
 * 검사하지 않으면 projectId 는 장식이 된다 — 아무 프로젝트 id 나 끼워 넣어도 통과하고, 그 화면이
 * 남의 프로젝트 빌드를 자기 것처럼 보여 준다. 경로에 있는 값이 아무것도 막지 않는다는 사실은
 * 화면에서는 절대 드러나지 않는다.
 *
 * SDK 경로는 projectId 를 모른다(빌드 id 하나로 온다). 그쪽이 계속 통과하는 것도 함께 지킨다 —
 * 검사를 더하면서 기존 경로를 조용히 막으면 SDK 가 등록을 못 한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ProjectContentMapAccessTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: EvidenceDocumentService
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /**
     * 경로의 projectId 가 빌드의 것과 다르면 티켓을 내주지 않는다.
     *
     * 사용자는 두 프로젝트 모두의 멤버다 — 즉 **권한이 아니라 경로가 어긋난 것**을 잡는지 본다.
     * 권한으로만 막으면, 한 사람이 여러 프로젝트에 속한 흔한 경우에 이 검사가 통째로 무력해진다.
     */
    @Test
    fun `경로의 프로젝트가 빌드의 것과 다르면 티켓을 안 준다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val build = newBuild(mine)

        val wrong = service.createUploadTicket(userId, build, EvidenceUploadTicketRequest(10), projectId = other)
        assertThat(wrong).isNull()

        val right = service.createUploadTicket(userId, build, EvidenceUploadTicketRequest(10), projectId = mine)
        assertThat(right).isNotNull()
    }

    /** 등록도 같은 검사를 지난다. 티켓만 막고 등록을 열어 두면 키를 아는 사람이 그대로 통과한다. */
    @Test
    fun `경로의 프로젝트가 다르면 등록도 안 받는다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val build = newBuild(mine)
        val ticket = service.createUploadTicket(userId, build, EvidenceUploadTicketRequest(64), projectId = mine)!!
        fakeStorage.put(ticket.objectKey, evidenceDocument())

        val wrong = service.register(userId, build, RegisterEvidenceDocumentRequest(ticket.objectKey), projectId = other)
        assertThat(wrong).isNull()

        val right = service.register(userId, build, RegisterEvidenceDocumentRequest(ticket.objectKey), projectId = mine)
        assertThat(right).isNotNull()
    }

    /**
     * SDK 경로(projectId 없음)는 그대로 통과한다.
     *
     * 새 검사를 더하면서 기본값을 잘못 잡으면 SDK 등록이 조용히 막힌다 — 그쪽은 프로젝트 id 를
     * 보내지 않으므로 null 이 정상이다.
     */
    @Test
    fun `SDK 경로는 프로젝트 없이도 통과한다`(): Unit = runBlocking {
        val userId = newUser()
        val project = newProject(userId)
        val build = newBuild(project)

        val ticket = service.createUploadTicket(userId, build, EvidenceUploadTicketRequest(10), projectId = null)

        assertThat(ticket).isNotNull()
    }

    /**
     * 적재도 같은 검사를 지난다. **컨트롤러가 아니라 서비스가** 검사하는 자리라, 적재를 시작하는
     * 유일한 문을 지나는 누구도 검사를 빠뜨릴 수 없다.
     *
     * 이 단언이 없으면 `ingestBuild` 에서 검사를 지워도 테스트가 전부 통과한다 — 그러면 인증된
     * 사용자가 아무 빌드 id 나 넣어 남의 지도를 적재할 수 있다.
     */
    @Test
    fun `경로의 프로젝트가 다르면 적재도 안 된다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val build = newBuild(mine)

        assertThat(ingest.ingestBuild(userId, other, build)).isNull()
        assertThat(ingest.ingestBuild(userId, mine, build)).isNotNull()
    }

    // ---------- 픽스처 ----------

    /** 앞부분만 읽어도 헤더가 나오는 최소 문서. 이 테스트는 헤더 내용이 아니라 접근만 본다. */
    private fun evidenceDocument(): ByteArray = """
        {
          "schema": 6,
          "capture": "editor",
          "capabilities": ["build-info-v1"],
          "build": {"unity":"2022.3.62f3","platform":"OSXEditor","backend":"mono",
                    "development":true,"sdk":"0.1.0","evidence":"d4b31e4da9504b7d"},
          "scenes": ["TitleScene"],
          "types": {},
          "objects": []
        }
    """.trimIndent().toByteArray()

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('console') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "console-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
}
