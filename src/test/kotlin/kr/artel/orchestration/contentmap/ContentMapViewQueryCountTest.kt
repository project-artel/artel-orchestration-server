package kr.artel.orchestration.contentmap

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.Applicability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.ScreenEntity
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.repository.ScreenCapabilityRepository
import kr.artel.orchestration.contentmap.repository.ScreenRepository
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import java.time.Instant

/**
 * **`screen` 수가 늘어도 조회 질의 수는 그대로다** (ARTEL-658).
 *
 * 이 테스트가 없으면 "`screen` 마다 한 번씩" 회귀가 조용히 들어온다. `screen` 이 하나뿐인 픽스처
 * 에서는 두 구현이 똑같이 동작하고, 느려지는 것은 한 `scene` 이 `screen` 수십 개를 담는 실제
 * 지도에서다 — 실측 `TurnBattleScene` 이 `screen` 29 개이고, 그 시점에는 이미 조회마다 그 비용을
 * 내고 있다.
 *
 * 리포지토리 호출 횟수로 본다. 실제 SQL 실행을 세려면 R2DBC 프록시를 끼워야 하는데, 이 두 질의는
 * 리포지토리 메서드 하나가 SQL 하나라 세는 값이 같다 — `SceneContextQueryCountTest` 와 같은 방식이다.
 *
 * `verifyNoMoreInteractions` 을 쓰지 않는 이유: `ScreenRepository` 와 `ScreenCapabilityRepository` 는
 * 조회 경로에서 각각 메서드 하나만 쓰므로 `times(1)` 이 곧 "`screen` 수와 무관"이다. 다른
 * 리포지토리까지 함께 얼리면 이 테스트가 조회 응답의 다른 섹션이 늘 때마다 관계없이 깨진다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class ContentMapViewQueryCountTest {

    @Autowired private lateinit var view: ContentMapViewService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var db: DatabaseClient

    @SpyBean private lateinit var screens: ScreenRepository
    @SpyBean private lateinit var screenCapabilities: ScreenCapabilityRepository

    @Test
    fun `screen 이 하나든 여럿이든 질의 수가 같다`(): Unit = runBlocking {
        readAndVerifyQueries(screenCount = 1)
        readAndVerifyQueries(screenCount = 12)
    }

    /**
     * `scene` 하나에 `screen` [screenCount] 개를 세우고, `screen` 마다 `capability` 를 하나씩 묶어
     * 한 번 읽는다.
     *
     * 그리고 `screen` 수와 무관해야 하는 두 조회가 **각각 정확히 한 번**만 돌았는지 본다. "한 번
     * 이하"로 느슨하게 잡으면 `screen` 마다 도는 구현이 그대로 통과한다.
     */
    private suspend fun readAndVerifyQueries(screenCount: Int) {
        val userId = newUser()
        val projectId = newProject(userId)
        val gameBuildId = newBuild(projectId)
        val contentMapId = newContentMap(gameBuildId)
        val sceneId = scenes.save(SceneEntity(contentMapId = contentMapId, name = "Scene")).id!!
        repeat(screenCount) { index ->
            val screenId = screens.save(
                ScreenEntity(
                    sceneId = sceneId,
                    name = "screen $index",
                    // 한 `scene` 안에서 `screen` 을 가르는 것은 `discriminator` 다
                    // (V56 의 uk_screen_discriminator). 값을 돌려쓰면 두 `screen` 이 같은 `screen`
                    // 이 되어 INSERT 가 거절된다
                    discriminator = Json.of("""[{"selector":"Canvas/screen[$index]","active":true}]"""),
                )
            ).id!!
            val capabilityId = newCapability(contentMapId, sceneId, index)
            screenCapabilities.observe(screenId, capabilityId, firedIncrement = 1)
        }

        clearInvocations(screens, screenCapabilities)

        val response = view.read(userId, projectId, gameBuildId)!!
        assertThat(response.scenes.single().screens).hasSize(screenCount)
        assertThat(response.scenes.single().screens).allSatisfy {
            assertThat(it.capabilities).hasSize(1)
        }

        verify(screens, times(1)).findByContentMapId(contentMapId)
        verify(screenCapabilities, times(1)).findByContentMapId(contentMapId)
    }

    private suspend fun newCapability(contentMapId: Long, sceneId: Long, index: Int): Long =
        capabilities.save(
            CapabilityEntity(
                sceneId = sceneId,
                contentMapId = contentMapId,
                capabilityKey = "key-$sceneId-$index",
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "capability $index",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
                applicability = Applicability.APPLIES.wire,
            )
        ).id!!

    private suspend fun newContentMap(gameBuildId: Long): Long =
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidenceDigest = "query-count-${System.nanoTime()}",
            )
        ).id!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(
                name = "screen-query-count-${System.nanoTime()}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now,
            )
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

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()
}
