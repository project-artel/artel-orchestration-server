package kr.artel.orchestration.scenecontext

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.scenecontext.repository.AnchoredKnowledgeRepository
import kr.artel.orchestration.scenecontext.service.SceneContextService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.mockito.Mockito.clearInvocations
import org.mockito.Mockito.times
import org.mockito.Mockito.verify
import org.mockito.Mockito.verifyNoMoreInteractions
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.mock.mockito.SpyBean
import org.springframework.test.context.ActiveProfiles

/**
 * **씬 수와 지식 수가 늘어도 질의 수는 그대로다**(ARTEL-611).
 *
 * 이 테스트가 없으면 "씬마다 한 번씩" 회귀가 조용히 들어온다. 씬이 하나뿐인 픽스처에서는 두
 * 구현이 똑같이 동작하고, 느려지는 것은 씬이 수백 개인 실제 게임에서다 — 그 시점에는 이미
 * 런 시작마다 그 비용을 내고 있다.
 *
 * 리포지토리 호출 횟수로 본다. 실제 SQL 실행을 세려면 R2DBC 프록시를 끼워야 하는데, 이
 * 서비스의 질의는 전부 리포지토리 메서드 하나가 SQL 하나라 세는 값이 같다.
 *
 * 세지 않는 것: `GameBuildRepository.findById` 와 `QaTryRepository.findById` 는 suspend 라
 * Mockito 검증이 코루틴 컨티뉴에이션을 끼고 돌아 읽기가 나빠진다. 둘 다 씬·지식 수와 무관한
 * 단건 조회라 이 테스트가 잡으려는 회귀와 관계가 없다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.NONE)
class SceneContextQueryCountTest {

    @Autowired private lateinit var sceneContext: SceneContextService
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var knowledge: KnowledgeRepository
    @Autowired private lateinit var anchors: KnowledgeAnchorRepository

    @SpyBean private lateinit var contentMaps: ContentMapRepository
    @SpyBean private lateinit var scenes: SceneRepository
    @SpyBean private lateinit var anchoredKnowledge: AnchoredKnowledgeRepository

    private lateinit var fixture: SceneContextFixture

    @BeforeEach
    fun setUp(): Unit = runBlocking {
        anchors.deleteAll()
        knowledge.deleteAll()
        fixture = SceneContextFixture(projects, gameBuilds, contentMaps, scenes, capabilities, knowledge, anchors)
    }

    @Test
    fun `씬이 하나든 여럿이든 질의 수가 같다`(): Unit = runBlocking {
        readAndVerifyQueries(sceneCount = 1)
        readAndVerifyQueries(sceneCount = 6)
    }

    /**
     * 씬 [sceneCount] 개, 씬마다 기능 하나와 앵커 지식 하나를 세우고 한 번 읽는다.
     *
     * 그리고 씬 수와 무관해야 하는 네 조회가 **각각 정확히 한 번**만 돌았는지 본다: 지도 고르기 ·
     * 씬 목록 · capability 전량 · 앵커.
     */
    private suspend fun readAndVerifyQueries(sceneCount: Int) {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val mapId = fixture.newContentMap(buildId)
        repeat(sceneCount) { index ->
            val sceneName = "Scene$index"
            val sceneId = fixture.newScene(mapId, sceneName)
            fixture.newCapability(mapId, sceneId, summary = "기능 $index")
            fixture.newKnowledge(projectId, "사실 $index", "본문", sceneName = sceneName)
        }

        clearInvocations(contentMaps, scenes, anchoredKnowledge)

        val response = sceneContext.read(projectId, buildId, qaTryId = null)!!
        assertThat(response.scenes).hasSize(sceneCount)

        // 각각 정확히 한 번. "한 번 이하"로 느슨하게 잡으면 씬마다 도는 구현이 그대로 통과한다.
        verify(contentMaps, times(1)).findByGameBuildId(buildId)
        verify(contentMaps, times(1)).findCapabilityRows(mapId)
        verify(scenes, times(1)).findByContentMapIdOrderByNameAsc(mapId)
        verify(anchoredKnowledge, times(1)).findAnchoredKnowledge(projectId, null)
        verifyNoMoreQueries()
    }

    /**
     * 위에서 센 넷 말고는 아무것도 부르지 않았음을 못박는다. 이것이 없으면 씬마다 도는 새 질의가
     * 다른 메서드로 들어올 때 통과한다 — 예컨대 `findCapabilityRowsByScene` 은 씬 이름을 받으므로
     * 정확히 그 회귀의 모양이다.
     */
    private fun verifyNoMoreQueries() {
        verifyNoMoreInteractions(contentMaps, scenes, anchoredKnowledge)
    }
}
