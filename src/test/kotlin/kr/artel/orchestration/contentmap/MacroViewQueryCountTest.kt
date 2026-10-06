package kr.artel.orchestration.contentmap

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.MacroRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenMacroRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.service.MacroViewService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.inOrder
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * **macro 수가 늘어도 목록 질의 수는 그대로다**(ARTEL-943). `ContentMapViewQueryCountTest` 가 선례다.
 *
 * 이 테스트가 없으면 "macro 마다 한 번씩" 회귀가 조용히 들어온다. `MacroViewService.summaryOf` 가
 * 관계를 묶어 받는 대신 `screenMacros` 를 직접 부르도록 바뀌어도, macro 가 하나뿐인 픽스처에서는
 * 두 구현이 똑같이 동작한다. 느려지는 것은 빌드 하나에 macro 가 수십 개 쌓인 뒤다.
 *
 * 리포지토리 호출 횟수로 본다. 이 두 질의는 리포지토리 메서드 하나가 SQL 하나라 세는 값이 같다.
 *
 * `times(1)` 로 못 박는 이유: "한 번 이하" 로 느슨하게 잡으면 macro 마다 도는 구현이 그대로
 * 통과한다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class MacroViewQueryCountTest {

    @Autowired private lateinit var view: MacroViewService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var screens: ScreenRepository
    @Autowired private lateinit var db: DatabaseClient

    @SpyBean private lateinit var macros: MacroRepository
    @SpyBean private lateinit var screenMacros: ScreenMacroRepository

    @Test
    fun `macro 가 하나든 여럿이든 질의 수가 같다`(): Unit = runBlocking {
        listAndVerifyQueries(macroCount = 1)
        listAndVerifyQueries(macroCount = 12)
    }

    /**
     * macro [macroCount] 개를 세우고 저마다 `screen` 둘에 달아 목록을 한 번 읽는다.
     *
     * `screen` 을 둘씩 다는 것은 관계 행이 macro 보다 많은 상태를 만들려는 것이다. 관계가 macro 당
     * 하나면 묶어 받는 구현과 하나씩 부르는 구현의 호출 수가 더 비슷해 보인다.
     */
    private suspend fun listAndVerifyQueries(macroCount: Int) {
        val userId = newUser()
        val projectId = newProject(userId)
        val gameBuildId = newBuild(projectId)
        val contentMapId = contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = Capture.PLAYER.wire,
                evidenceDigest = "macro-count-${System.nanoTime()}",
            )
        )!!.id!!
        val sceneId = scenes.save(SceneEntity(contentMapId = contentMapId, name = "Scene")).id!!
        // 한 `scene` 안에서 `screen` 을 가르는 것은 `discriminator` 다(V56 의 uk_screen_discriminator).
        val screenIds = (0 until 2).map { index ->
            screens.save(
                ScreenEntity(
                    sceneId = sceneId,
                    name = "screen $index",
                    discriminator = Json.of("""[{"selector":"Canvas/screen[$index]","active":true}]"""),
                )
            ).id!!
        }
        repeat(macroCount) { index ->
            val macroId = macros.upsertByName(
                contentMapId = contentMapId,
                name = "macro_$index",
                source = "def macro_$index(): pass",
                definitionJson = Json.of("""{"defs":[{"name":"macro_$index","parameters":[]}]}"""),
                parameterNames = Json.of("[]"),
            ).id
            screenIds.forEach { screenMacros.link(it, macroId) }
        }

        clearInvocations(macros, screenMacros)

        val items = view.list(userId, projectId, gameBuildId)!!.items
        assertThat(items).hasSize(macroCount)
        assertThat(items).allSatisfy { assertThat(it.screens).hasSize(2) }

        verify(macros, times(1)).findSummariesByContentMapId(contentMapId)
        verify(screenMacros, times(1)).findScreenRowsByContentMapId(contentMapId)

        // **macro 를 먼저 읽어야 한다.** 둘이 한 트랜잭션이 아니라, 관계를 먼저 읽으면 그 사이에
        // 등록된 macro 가 `screens: []` 로 나간다 — 그 빈 배열은 "아직 어디서 쓸지 모른다" 라는
        // 주장이라 화면이 사실이 아닌 것을 그린다. 거꾸로면 그 macro 가 목록에 안 설 뿐이고
        // 다음 새로고침에 제대로 선다. 순서를 되돌리면 이 줄이 깨진다.
        inOrder(macros, screenMacros).apply {
            verify(macros).findSummariesByContentMapId(contentMapId)
            verify(screenMacros).findScreenRowsByContentMapId(contentMapId)
        }
    }

    private suspend fun newUser(): Long =
        db.sql(
            "INSERT INTO app_user (display_name, nickname, user_tag) " +
                "VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id"
        )
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "macro-count-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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
