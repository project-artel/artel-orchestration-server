package kr.artel.orchestration.scenecontext

import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.config.InternalApiServer
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeAnchorRepository
import kr.artel.orchestration.knowledge.repository.KnowledgeRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.scenecontext.dto.SceneContextResponse
import kr.artel.orchestration.scenecontext.service.SceneContextService
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.web.server.LocalServerPort
import org.springframework.http.HttpStatus
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.function.client.WebClient
import org.springframework.web.reactive.function.client.WebClientResponseException
import reactor.core.publisher.Mono
import java.time.Duration
import java.time.Instant

/**
 * QA agent 가 런 시작에 한 번 받아 가는 씬별 맥락(ARTEL-611)의 계약 테스트.
 *
 * **HTTP 로 두드린다.** 이 응답의 계약은 agent-server(ARTEL-612)가 지금 이 순간 코딩하고 있는
 * 대상이라, 검증해야 하는 것이 서비스의 반환 객체가 아니라 **실제로 나가는 JSON** 이다. 특히
 * "본문(`description`)이 payload 에 없다"는 직렬화된 문자열로만 확인할 수 있다 — DTO 에 필드가
 * 없다는 사실은 다음 사람이 필드를 더하는 것을 막지 못한다.
 *
 * 실제 소켓을 쓰는 김에 **내부 포트에서만** 두드린다. 이 경로가 공개 포트에 뜨지 않는다는 것은
 * `InternalApiPortIntegrationTest` 가 접두사 단위로 이미 지키므로 여기서 다시 보지 않는다.
 */
@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SceneContextIntegrationTest {

    @Autowired private lateinit var sceneContext: SceneContextService
    @Autowired private lateinit var internalApiServer: InternalApiServer
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var knowledge: KnowledgeRepository
    @Autowired private lateinit var anchors: KnowledgeAnchorRepository
    @Autowired private lateinit var db: DatabaseClient

    @LocalServerPort private val publicPort: Int = 0

    private lateinit var fixture: SceneContextFixture

    /**
     * `anchor` 는 knowledge 를 FK 없이 논리참조하므로(V55) knowledge 를 지워도 따라 사라지지 않는다.
     * 남기면 다음 테스트의 프로젝트 조회에 섞인다.
     */
    @BeforeEach
    fun setUp(): Unit = runBlocking {
        anchors.deleteAll()
        knowledge.deleteAll()
        fixture = SceneContextFixture(projects, gameBuilds, contentMaps, scenes, capabilities, knowledge, anchors)
    }

    // ------------------------------------------------------------------ 두 반쪽

    /**
     * 응답의 본론. 씬 이름 하나 아래에 **무엇을 할 수 있는지**와 **여기서만 참인 것**이 같이 온다.
     *
     * 지금까지 agent 는 이 둘을 각각 도구 호출로 물어야 했고, 물을 생각을 해야 물을 수 있었다.
     */
    @Test
    fun `capability 와 anchor 지식이 같은 씬 아래 붙는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val mapId = fixture.newContentMap(buildId, Capture.PLAYER)
        val combat = fixture.newScene(mapId, "Combat", summary = "전투 화면")
        fixture.newCapability(
            mapId, combat,
            summary = "적을 공격한다",
            givenText = "적이 살아 있을 때",
            controlPath = "Canvas/AttackButton",
            controlLabel = "공격",
            controlSelector = "Canvas/Panel[0]/Button[2]",
        )
        fixture.newKnowledge(projectId, "전투 중 ESC 는 아무것도 하지 않는다", "본문", sceneName = "Combat")

        val response = get(projectId, buildId)

        assertThat(response.contentMapId).isEqualTo(mapId.toString())
        assertThat(response.capture).isEqualTo("player")
        val scene = response.scenes.single()
        assertThat(scene.sceneName).isEqualTo("Combat")
        assertThat(scene.knownToContentMap).isTrue()
        assertThat(scene.sceneSummary).isEqualTo("전투 화면")

        val capability = scene.capabilities.single()
        assertThat(capability.summary).isEqualTo("적을 공격한다")
        assertThat(capability.givenText).isEqualTo("적이 살아 있을 때")
        assertThat(capability.interaction).isEqualTo("click")
        assertThat(capability.controlPath).isEqualTo("Canvas/AttackButton")
        assertThat(capability.controlLabel).isEqualTo("공격")
        assertThat(capability.status).isEqualTo("runnable")
        assertThat(capability.verification).isEqualTo("unverified")
        // 형제 인덱스가 박힌 경로라 런마다 흔들린다. 이름에 Hint 를 박아 조준 키로 오해받지 않게 한다.
        assertThat(capability.controlSelectorHint).isEqualTo("Canvas/Panel[0]/Button[2]")

        assertThat(scene.knowledge.single().summary).isEqualTo("전투 중 ESC 는 아무것도 하지 않는다")
    }

    /**
     * **`anchor` 에만 있는 씬을 떨어뜨리지 않는다.**
     *
     * `anchor` 는 content map 과 대조하지 않고 저장되므로(ARTEL-591, V55), 지도가 들어 본 적 없는 씬
     * 이름을 든 `anchor` 가 정상적으로 존재한다. 씬 이름이 유일한 조인 키라 안쪽 조인으로 짜면 그런
     * `anchor` 는 소리 없이 사라지고, agent 는 이미 배운 사실을 잃는다.
     */
    @Test
    fun `anchor 에만 있는 씬도 capability 없이 나온다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val mapId = fixture.newContentMap(buildId)
        val combat = fixture.newScene(mapId, "Combat")
        fixture.newCapability(mapId, combat, summary = "적을 공격한다")
        fixture.newKnowledge(projectId, "상점은 밤에 닫는다", "본문", sceneName = "ShopNotInMap")

        val response = get(projectId, buildId)

        val shop = response.scenes.single { it.sceneName == "ShopNotInMap" }
        assertThat(shop.knownToContentMap).isFalse()
        assertThat(shop.capabilities).isEmpty()
        assertThat(shop.sceneSummary).isNull()
        assertThat(shop.knowledge.single().summary).isEqualTo("상점은 밤에 닫는다")
    }

    /**
     * 지도에는 있는데 할 수 있는 것이 하나도 없는 씬도 남긴다.
     *
     * `walked=false` 인 씬은 비어 있는 것이 정상이고(V40), 목록에서 사라지면 agent 는 "지도에
     * 있지만 아직 안 걸어 본 씬"과 "지도가 모르는 씬"을 구분할 수 없다.
     */
    @Test
    fun `기능이 없는 지도의 씬도 남는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val mapId = fixture.newContentMap(buildId)
        fixture.newScene(mapId, "EmptyScene")

        val scene = get(projectId, buildId).scenes.single()

        assertThat(scene.sceneName).isEqualTo("EmptyScene")
        assertThat(scene.knownToContentMap).isTrue()
        assertThat(scene.capabilities).isEmpty()
        assertThat(scene.knowledge).isEmpty()
    }

    // ------------------------------------------------------------------ 거르기

    /**
     * `not-a-step` 은 나가지 않는다. `v_content_map_capability` 가 이미 걸러 낸 것을 여기서 되살리지
     * 않는다는 뜻이다 — 그 뷰가 TC 생성기가 읽는 유일한 창구이고, agent 도 같은 기준을 봐야
     * "TC 에는 없는데 agent 는 하려 드는" 스텝이 생기지 않는다.
     */
    @Test
    fun `not-a-step 은 나가지 않는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val mapId = fixture.newContentMap(buildId)
        val scene = fixture.newScene(mapId, "Town")
        fixture.newCapability(mapId, scene, summary = "누를 수 있다")
        fixture.newCapability(
            mapId, scene,
            summary = "조작이 아니다",
            interaction = "none",
            actionability = "not-a-step",
        )

        val capabilities = get(projectId, buildId).scenes.single().capabilities

        assertThat(capabilities.map { it.summary }).containsExactly("누를 수 있다")
    }

    /**
     * **본문이 payload 에 없다.** DTO 를 보는 것으로는 부족해 직렬화된 문자열을 본다 — 다음 사람이
     * `description` 을 더하는 순간 이 테스트가 빨개져야 한다.
     *
     * 이 블록은 매 모델 호출마다 다시 그려진다. 본문을 실으면 그 비용을 런 내내 매 턴 다시 낸다.
     */
    @Test
    fun `지식 본문은 payload 에 실리지 않는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        fixture.newKnowledge(
            projectId,
            summary = "상점은 밤에 닫는다",
            description = "이 문장이 응답에 나오면 안 된다",
            sceneName = "Shop",
        )

        val body = rawBody(projectId, buildId)

        assertThat(body).contains("상점은 밤에 닫는다")
        assertThat(body).doesNotContain("이 문장이 응답에 나오면 안 된다")
        assertThat(body).doesNotContain("description")
    }

    /**
     * **`anchor` 가 없는 지식은 이 응답에 없다.** 그것이 지식창고의 대부분이고 게임 전체의 사실이라,
     * 담기 시작하면 "씬별"이라는 말이 뜻을 잃고 프롬프트가 지식창고 전체를 지고 다니게 된다.
     */
    @Test
    fun `anchor 없는 지식은 담기지 않는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        fixture.newKnowledge(projectId, "낙하 데미지는 5m 부터", "본문")
        fixture.newKnowledge(projectId, "상점은 밤에 닫는다", "본문", sceneName = "Shop")

        val response = get(projectId, buildId)

        assertThat(response.scenes.flatMap { it.knowledge }.map { it.summary })
            .containsExactly("상점은 밤에 닫는다")
    }

    /**
     * 스코프에 가려진 지식은 `anchor` 째 빠진다.
     *
     * 이 조회가 [kr.artel.orchestration.knowledge.entity.KnowledgeScopeSql].VISIBLE 을 지나는지를
     * 보는 자리다. 빠뜨리면 실험 런이 자기가 고친 항목의 **원본과 수정본을 둘 다** 프롬프트에
     * 싣는데, 둘 다 그럴듯해서 실험이 끝날 때까지 아무도 못 알아챈다.
     */
    @Test
    fun `스코프에 가려진 지식은 anchor 째 빠진다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val scopeId = 77_611L
        val baseline = fixture.newKnowledge(projectId, "baseline 사실", "본문", sceneName = "Combat")
        val shadow = fixture.newKnowledge(
            projectId, "스코프가 고친 사실", "본문",
            sceneName = "Combat", scopeId = scopeId, shadowsId = baseline,
        )
        val qaTryId = newQaTry(projectId, scopeId)

        // 운영 런은 baseline 만 본다. 스코프 행은 남의 것이라 아예 보이지 않는다.
        assertThat(sceneContext.read(projectId, buildId, qaTryId = null)!!.scenes.flatMap { it.knowledge })
            .extracting<String> { it.knowledgeId }
            .containsExactly(baseline.toString())

        // 스코프 런은 자기 수정본만 본다. 가려진 baseline 이 함께 나오면 같은 사실이 두 줄이 된다.
        assertThat(sceneContext.read(projectId, buildId, qaTryId)!!.scenes.flatMap { it.knowledge })
            .extracting<String> { it.knowledgeId }
            .containsExactly(shadow.toString())
    }

    /** 소프트삭제된 지식의 `anchor` 만 남아 나오면 agent 가 이미 지운 사실을 계속 참으로 읽는다. */
    @Test
    fun `지워진 지식의 anchor 는 나오지 않는다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        val id = fixture.newKnowledge(projectId, "이제 틀린 사실", "본문", sceneName = "Combat")
        knowledge.save(knowledge.findById(id)!!.copy(deletedAt = Instant.now()))

        assertThat(get(projectId, buildId).scenes).isEmpty()
    }

    /** 다른 프로젝트의 `anchor` 가 같은 씬 이름을 써도 섞이지 않는다. 씬 이름은 게임이 부르는 대로다. */
    @Test
    fun `다른 프로젝트의 anchor 는 섞이지 않는다`(): Unit = runBlocking {
        val mine = fixture.newProject()
        val other = fixture.newProject()
        val buildId = fixture.newBuild(mine)
        fixture.newKnowledge(mine, "내 사실", "본문", sceneName = "Combat")
        fixture.newKnowledge(other, "남의 사실", "본문", sceneName = "Combat")

        assertThat(get(mine, buildId).scenes.single().knowledge.map { it.summary })
            .containsExactly("내 사실")
    }

    // ------------------------------------------------------------------ 경계

    /**
     * `evidence` 를 한 번도 올리지 않은 빌드는 **404 가 아니다.** 빌드는 존재하고, 없는 것은 아직 아무도
     * 올리지 않은 문서다. 그때도 `anchor` 지식은 답한다 — `anchor` 는 프로젝트에 매달린 사실이라 빌드에
     * 지도가 있는지와 수명이 다르다.
     */
    @Test
    fun `지도가 없는 빌드는 200 이고 anchor 만 나온다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)
        fixture.newKnowledge(projectId, "상점은 밤에 닫는다", "본문", sceneName = "Shop")

        val response = get(projectId, buildId)

        assertThat(response.contentMapId).isNull()
        assertThat(response.capture).isNull()
        assertThat(response.scenes.single().sceneName).isEqualTo("Shop")
        assertThat(response.scenes.single().knownToContentMap).isFalse()
    }

    /** 지도도 `anchor` 도 없으면 빈 응답이다. 그것도 200 이다. */
    @Test
    fun `아무것도 없는 빌드는 빈 200 이다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)

        val response = get(projectId, buildId)

        assertThat(response.contentMapId).isNull()
        assertThat(response.scenes).isEmpty()
    }

    /**
     * 경로의 `projectId` 를 **실제로 검사한다.** 무인증 내부 경로라는 사실은 검사를 생략할 이유가
     * 되지 못한다 — 검사하지 않으면 그 값은 장식이고, 다음 호출자는 장식을 보증으로 읽는다.
     */
    @Test
    fun `경로의 프로젝트가 다르면 404 다`(): Unit = runBlocking {
        val mine = fixture.newProject()
        val other = fixture.newProject()
        val buildId = fixture.newBuild(mine)

        assertThat(status(other, buildId)).isEqualTo(HttpStatus.NOT_FOUND)
        assertThat(status(mine, buildId)).isEqualTo(HttpStatus.OK)
    }

    @Test
    fun `없는 빌드는 404 다`(): Unit = runBlocking {
        assertThat(status(fixture.newProject(), gameBuildId = 987_654_321L)).isEqualTo(HttpStatus.NOT_FOUND)
    }

    /**
     * 없는 `qaTryId` 는 조용히 운영 스코프로 떨어지지 않는다. 떨어지면 실험 런이 운영 지식을 읽고도
     * 아무도 못 알아채고, 그 런의 결과는 그럴듯하다.
     */
    @Test
    fun `없는 QA 런 id 는 404 다`(): Unit = runBlocking {
        val projectId = fixture.newProject()
        val buildId = fixture.newBuild(projectId)

        assertThatThrownBy { runBlocking { sceneContext.read(projectId, buildId, qaTryId = 987_654_321L) } }
            .isInstanceOf(NotFoundException::class.java)
    }

    // ---------- 호출 ----------

    private fun internalClient() = WebClient.create("http://localhost:${internalApiServer.port}")

    private fun path(projectId: Long, gameBuildId: Long) =
        "/internal/projects/$projectId/game-builds/$gameBuildId/scene-context"

    private fun get(projectId: Long, gameBuildId: Long): SceneContextResponse =
        internalClient().get().uri(path(projectId, gameBuildId))
            .retrieve()
            .bodyToMono(SceneContextResponse::class.java)
            .block(TIMEOUT)!!

    private fun rawBody(projectId: Long, gameBuildId: Long): String =
        internalClient().get().uri(path(projectId, gameBuildId))
            .retrieve()
            .bodyToMono(String::class.java)
            .block(TIMEOUT)!!

    private fun status(projectId: Long, gameBuildId: Long): HttpStatus =
        internalClient().get().uri(path(projectId, gameBuildId))
            .exchangeToMono { Mono.just(it.statusCode()) }
            .onErrorResume(WebClientResponseException::class.java) { Mono.just(it.statusCode) }
            .block(TIMEOUT) as HttpStatus

    /**
     * 스코프를 든 QA 런 한 줄. `qa_try` 는 시나리오·인스턴스·사용자를 전부 참조하므로 엔티티로
     * 세우면 이 테스트가 QA 도메인 전체를 끌고 온다 — 이 조회가 읽는 칸은
     * `knowledge_scope_id` 하나뿐이라 SQL 로 최소한만 세운다.
     */
    private suspend fun newQaTry(projectId: Long, scopeId: Long): Long {
        val userId = insertId("INSERT INTO app_user (display_name) VALUES ('agent') RETURNING id")
        val scenarioId = insertId(
            """
            INSERT INTO test_scenario (project_id, title)
            VALUES ($projectId, 'scene-context') RETURNING id
            """
        )
        val instanceId = insertId(
            """
            INSERT INTO game_instance (project_id, name, platform)
            VALUES ($projectId, 'scene-context', 'Editor') RETURNING id
            """
        )
        return insertId(
            """
            INSERT INTO qa_try (test_scenario_id, game_instance_id, started_by, status,
                                knowledge_scope_id, started_at)
            VALUES ($scenarioId, $instanceId, $userId, 'RUNNING', $scopeId, CURRENT_TIMESTAMP)
            RETURNING id
            """
        )
    }

    private suspend fun insertId(sql: String): Long =
        db.sql(sql).map { row, _ -> (row.get(0) as Number).toLong() }.one().block()!!

    companion object {
        private val TIMEOUT: Duration = Duration.ofSeconds(20)
    }
}
