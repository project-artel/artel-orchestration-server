package kr.artel.orchestration.contentmap

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * 브라우저 경로는 `/projects/{projectId}/` 를 지나온다. **그 값을 실제로 검사하는가**를 본다.
 *
 * 검사하지 않으면 projectId 는 장식이 된다 — 아무 프로젝트 id 나 끼워 넣어도 통과하고, 그 화면이
 * 남의 프로젝트 빌드를 자기 것처럼 보여 준다. 경로에 있는 값이 아무것도 막지 않는다는 사실은
 * 화면에서는 절대 드러나지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ProjectContentMapAccessTest {

    @Autowired private lateinit var view: ContentMapViewService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var db: DatabaseClient

    /**
     * 조회가 경로의 projectId 를 검사한다. **읽기라고 느슨하게 두면 그것이 제일 싼 유출 경로다.**
     *
     * 조회 하나가 열려 있으면 남의 프로젝트 빌드의 씬 이름·기능 요약·조건 문장이 그대로 나간다 —
     * 게임의 내용 자체다. 부재와 권한 없음을 같은 null 로 답해, id 를 훑어 남의 빌드가 존재한다는
     * 사실조차 알아낼 수 없게 한다.
     *
     * 사용자는 두 프로젝트 모두의 멤버다 — 즉 **권한이 아니라 경로가 어긋난 것**을 잡는지 본다.
     * 권한으로만 막으면, 한 사람이 여러 프로젝트에 속한 흔한 경우에 이 검사가 통째로 무력해진다.
     *
     * `mine` 쪽이 null 이 아닌 것을 함께 보는 이유: 검사를 더하면서 정상 경로까지 막으면 화면이
     * 통째로 빈다. 이 빌드에는 아직 문서가 없으므로 응답은 **비어 있되 존재한다** — 그 구분이
     * 이 API 의 요점이다.
     */
    @Test
    fun `경로의 프로젝트가 다르면 조회도 안 된다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val build = newBuild(mine)

        assertThat(view.read(userId, other, build)).isNull()

        val allowed = view.read(userId, mine, build)
        assertThat(allowed).isNotNull()
        // 접근은 되는데 올린 문서가 없다. 404 가 아니라 빈 지도다.
        assertThat(allowed!!.contentMap).isNull()
    }

    // ---------- 픽스처 ----------

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
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
