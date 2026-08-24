package kr.artel.orchestration.contentmap.ingest

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * `scene_edge` 를 적재기가 채우는가. **이 표는 이 이슈 전까지 늘 0행이었다** — V40 이 만들고
 * `SceneEdgeRepository` 가 읽을 준비까지 끝났는데 쓰는 코드가 없었다.
 *
 * 새 추출이 아니라 **한 걸음의 매핑**이다. `SceneManager.LoadScene("X")` 는 이미 근거 문서에
 * `kind='scene'` 효과로 들어와 있고, 적재기는 그것을 `capability_effect` 로 옮기고 있었다. 남은 것은
 * "그 효과를 든 기능이 앉은 씬 → 효과가 가리키는 씬 이름" 한 줄을 더 쓰는 일이다.
 *
 * 실측(2026-08-21, `wv-editor-latest.json`, schema 6 · editor):
 *
 * | 값 | 수 | 어디서 |
 * |---|---|---|
 * | 문서의 `kind='scene'` 효과 | 15 | `types[].effects[]`. `unplaced` 에는 0건이고 전부 `category='observable'` |
 * | 앉은 `scene_edge` 행 | **19** | 아래 |
 * | 서로 다른 (출발 씬 → 도착 씬) 쌍 | 13 | 같은 쌍을 서로 다른 기능이 낸다 |
 *
 * **15 와 19 가 다른 것이 정상이다.** 조인은 컨트롤 배선마다 · 스폰마다 후보를 내므로 한 레코드의
 * 효과가 여러 기능 행에 실린다 — 문서 효과 395건이 `capability_effect` 486행이 되는 것과 같은
 * 이유다. 실제로 `Combat.Enemies.Player::Death` 의 `GameOverScene` 효과 하나가 진입점 넷(적 근접
 * 공격 · 피격 · 적 투사체 · 주문)에서 각각 기능 행이 되어 간선 넷이 된다. 그 넷을 접으면 "무엇을 해서
 * 죽었나"가 사라지고, `scene_edge.capability_id` 가 든 뜻이 그것이다.
 *
 * 반대로 15 보다 적을 수도 있다. 한 기능이 같은 씬을 두 지점에서 부르면(`GameClearController::Update`
 * 가 `Map_scene` 을 `@72` 와 `@90` 에서) 효과는 둘이고 `uk_scene_edge` 는 한 행이다.
 *
 * 재적재와 런타임 규칙은 손으로 짠 작은 문서로 본다(뒤쪽 셋). 1.4MB 문서로는 "이 간선 하나가 어떻게
 * 되었나"를 단언에서 읽을 수 없다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentMapSceneEdgeIngestTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var edges: SceneEdgeRepository
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    private lateinit var goldenResult: IngestResult
    private var goldenMapId: Long = 0

    /** 1.4MB 를 테스트마다 다시 적재하지 않는다. 골든 단언 셋은 이 한 번의 결과를 각도만 바꿔 읽는다. */
    @BeforeAll
    fun `골든 문서를 한 번 적재한다`(): Unit = runBlocking {
        val map = newContentMap("scene-edge-golden")
        goldenMapId = map.id!!
        val bytes = File(GOLDEN_PATH).readBytes()
        goldenResult = ingest.ingest(register(goldenMapId, bytes))
    }

    // ---------- 1. 골든 문서 ----------

    /**
     * 골든 문서가 씬 전이 19개로 앉는다. **0 이 아니라는 것이 이 이슈의 전부다.**
     *
     * 19 가 어디서 오는지는 클래스 KDoc 의 표에 있다. 이 수가 줄면 옮기는 길에서 전이가 사라진
     * 것이고, 사라진 전이는 아무 데서도 오류를 내지 않는다 — 커버리지 구멍 목록이 그만큼 조용히
     * 짧아질 뿐이다.
     *
     * 정적 간선은 **런타임 칸을 하나도 들지 않은 채** 태어나야 한다. `verified_at IS NULL` 이 곧
     * "아직 못 가본 전이"이고 그것이 이 표의 존재 이유라, 적재기가 그 칸에 무엇이든 적으면 QA agent 는
     * 가본 적 없는 전이를 확인된 것으로 읽는다.
     */
    @Test
    fun `골든 문서가 정적 씬 전이 19개로 앉는다`(): Unit = runBlocking {
        assertThat(goldenResult.sceneEdges).isEqualTo(19)
        assertThat(edgeCountOf(goldenMapId)).isEqualTo(19)

        // 서로 다른 (출발 → 도착) 쌍은 13이다. 같은 쌍을 서로 다른 기능이 내는 것이 정상이고,
        // 여기가 19가 되면 `capability_id` 가 뜻을 잃은 것이다.
        assertThat(
            countOf(
                """
                SELECT count(*) FROM (
                    SELECT DISTINCT e.from_scene_id, e.to_scene_name
                    FROM scene_edge e JOIN scene s ON s.id = e.from_scene_id
                    WHERE s.content_map_id = :id
                ) p
                """,
                goldenMapId,
            )
        ).isEqualTo(13)

        assertThat(
            countOf(
                """
                SELECT count(*) FROM scene_edge e JOIN scene s ON s.id = e.from_scene_id
                WHERE s.content_map_id = :id
                  AND NOT (
                      e.source = 'static'
                      AND e.capability_id IS NOT NULL
                      AND e.verified_at IS NULL
                      AND e.observed_count = 0
                      AND e.first_observed_transition_id IS NULL
                      AND e.given_text IS NULL
                  )
                """,
                goldenMapId,
            )
        ).isZero()
    }

    /**
     * 이름이 이 지도의 씬과 맞으면 `to_scene_id` 가 **그 씬을 가리킨다.**
     *
     * 이름만 든 간선은 그래프가 아니다. TC 생성기가 "저 씬으로 갈 수 있다"에서 저 씬의 기능 목록으로
     * 넘어가려면 id 가 있어야 하고, 없으면 이름 조인을 각자 다시 하다가 같은 이름의 다른 지도를 집는다.
     *
     * 실측 문서는 도착 씬 일곱이 전부 순회된 씬이라 19/19 가 풀린다. 이름이 표에 없어 null 로 남는
     * 경우(빌드가 이름만 부르고 스캔하지 않은 씬)는 뒤쪽 작은 문서가 본다 — 그쪽이 그 상태를 만들 수
     * 있는 유일한 자리다.
     */
    @Test
    fun `도착 씬 이름이 지도에 있으면 그 씬 id 로 풀린다`(): Unit = runBlocking {
        assertThat(
            countOf(
                """
                SELECT count(*) FROM scene_edge e
                JOIN scene s ON s.id = e.from_scene_id
                LEFT JOIN scene t ON t.content_map_id = s.content_map_id AND t.name = e.to_scene_name
                WHERE s.content_map_id = :id AND e.to_scene_id IS DISTINCT FROM t.id
                """,
                goldenMapId,
            )
        ).isZero()

        assertThat(
            countOf(
                """
                SELECT count(*) FROM scene_edge e JOIN scene s ON s.id = e.from_scene_id
                WHERE s.content_map_id = :id AND e.to_scene_id IS NULL
                """,
                goldenMapId,
            )
        ).isZero()
    }

    /**
     * 근거가 말한 전이가 **그 기능에 매달린 채로** 나온다.
     *
     * 두 자리를 짚는다. 하나는 `Canvas/continue` 클릭 — `ContentMapIngestGoldenTest` 가 같은 자리의
     * `kind='scene'` · `target='Map_scene'` · `@47` 효과를 이미 못 박아 두었으므로, 여기서 그 효과가
     * 간선이 되었는지 보면 **효과 → 간선** 매핑 자체가 증명된다.
     *
     * 다른 하나는 `TurnBattleScene → GameOverScene` 넷이다. 문서의 `Player::Death` 효과는 하나인데
     * 진입점이 넷(적 근접 공격 · 피격 · 적 투사체 · 주문)이라 기능 행이 넷이고 간선도 넷이다. 이 수가
     * 1 이 되면 `capability_id` 가 접힌 것이고, "무엇을 해서 죽었나"가 표에서 사라진다.
     */
    @Test
    fun `근거의 씬 효과가 그 기능에 매달린 전이가 된다`(): Unit = runBlocking {
        val fromContinue = db
            .sql(
                """
                SELECT fs.name AS from_name, e.to_scene_name, ts.name AS to_name
                FROM scene_edge e
                JOIN scene fs ON fs.id = e.from_scene_id
                JOIN scene ts ON ts.id = e.to_scene_id
                JOIN capability c ON c.id = e.capability_id
                WHERE fs.content_map_id = :id AND c.control_path = 'Canvas/continue'
                ORDER BY e.to_scene_name
                """
            )
            .bind("id", goldenMapId)
            .map { row, _ ->
                listOf(
                    row.get("from_name", String::class.java),
                    row.get("to_scene_name", String::class.java),
                    row.get("to_name", String::class.java),
                )
            }
            .all()
            .collectList()
            .awaitSingle()

        assertThat(fromContinue).containsExactly(
            listOf("TitleScene", "Map_scene", "Map_scene"),
            listOf("TitleScene", "StoryScene", "StoryScene"),
        )

        assertThat(
            countOf(
                """
                SELECT count(*) FROM scene_edge e
                JOIN scene fs ON fs.id = e.from_scene_id
                JOIN scene ts ON ts.id = e.to_scene_id
                WHERE fs.content_map_id = :id
                  AND fs.name = 'TurnBattleScene' AND ts.name = 'GameOverScene'
                """,
                goldenMapId,
            )
        ).isEqualTo(4)

        // 간선을 낸 기능은 넷 다 다르다. 같은 기능이 넷이면 유니크가 애초에 거절했을 것이다.
        assertThat(
            countOf(
                """
                SELECT count(DISTINCT e.capability_id) FROM scene_edge e
                JOIN scene fs ON fs.id = e.from_scene_id
                JOIN scene ts ON ts.id = e.to_scene_id
                WHERE fs.content_map_id = :id
                  AND fs.name = 'TurnBattleScene' AND ts.name = 'GameOverScene'
                """,
                goldenMapId,
            )
        ).isEqualTo(4)
    }

    // ---------- 2. 재적재 ----------

    /**
     * 같은 문서를 두 번 적재해도 간선이 늘지 않고, **런타임이 벌어 온 칸이 되돌아가지 않는다.**
     *
     * 지웠다 넣기를 고르지 않은 이유가 여기 전부 있다. `verified_at` · `observed_count` ·
     * `first_observed_transition_id` 는 QA 런이 실제로 그 전이를 밟아 보고 남긴 것이고 다시 계산할 수
     * 없다. 재적재마다 0 으로 돌아가면 `verified_at IS NULL` 이 커버리지 구멍이라는 이 표의 존재
     * 이유가 매 스캔마다 거짓이 되고, QA agent 는 이미 밟은 전이를 영원히 다시 시도한다.
     * `scene.walked` 를 보존하는 것과 같은 규칙이다.
     *
     * 늘어나는 쪽도 같이 본다. `uk_scene_edge` 가 없었다면 스캔 수만큼 같은 전이가 쌓이고, 커버리지
     * 분모가 문서 재전송 횟수에 비례해 부푼다.
     */
    @Test
    fun `재적재가 간선을 늘리지도 런타임 칸을 되돌리지도 않는다`(): Unit = runBlocking {
        val map = newContentMap("scene-edge-reingest")
        val document = register(map.id!!, evidenceDocument().toByteArray())

        val first = ingest.ingest(document)
        assertThat(first.sceneEdges).isEqualTo(2)

        // QA 런이 이 전이를 밟았다고 찍어 둔다. 화면 전이까지 만드는 이유는 세 칸을 모두 채워야
        // "무엇 하나라도 되돌아가면 깨진다"가 성립하기 때문이다.
        val scene = scenes.findByContentMapIdAndName(map.id!!, "TitleScene")!!
        val transitionId = stampObservedTransition(scene.id!!)
        val walked = edgeIdOf(map.id!!, "MapScene")
        db.sql(
            """
            UPDATE scene_edge
            SET verified_at = :at, observed_count = 4, first_observed_transition_id = :transition
            WHERE id = :id
            """
        )
            .bind("at", VERIFIED_AT)
            .bind("transition", transitionId)
            .bind("id", walked)
            .fetch()
            .awaitRowsUpdated()

        val second = ingest.ingest(documents.findById(document.id!!)!!)

        assertThat(second.sceneEdges).isEqualTo(2)
        assertThat(edgeCountOf(map.id!!)).isEqualTo(2)

        val row = edges.findById(walked)!!
        assertThat(row.verifiedAt).isEqualTo(VERIFIED_AT)
        assertThat(row.observedCount).isEqualTo(4)
        assertThat(row.firstObservedTransitionId).isEqualTo(transitionId)
        // 출신도 그대로다. 재적재가 관측된 간선을 정적으로 되돌리면 "정적 분석이 놓친 전이"라는
        // 구분이 사라진다.
        assertThat(row.source).isEqualTo(EdgeSource.STATIC.wire)
    }

    /**
     * 이름만 있고 순회하지 못한 씬으로 가는 전이는 **이름으로 남는다.**
     *
     * `to_scene_id` 가 nullable 인 이유가 이것이다. 빌드는 스캔하지 않은 씬을 이름으로 부를 수 있고,
     * 그때 간선을 버리면 "저 씬에 가 본 적이 없다"는 사실 자체가 표에서 사라진다 — 가장 큰 커버리지
     * 구멍이 가장 먼저 지워지는 셈이다.
     */
    @Test
    fun `순회하지 못한 씬으로 가는 전이는 이름으로 남는다`(): Unit = runBlocking {
        val map = newContentMap("scene-edge-unwalked")
        ingest.ingest(register(map.id!!, evidenceDocument().toByteArray()))

        val unwalked = edges.findById(edgeIdOf(map.id!!, "OptionsScene"))!!
        assertThat(unwalked.toSceneName).isEqualTo("OptionsScene")
        assertThat(unwalked.toSceneId).isNull()
        // 그 이름의 씬 행이 생기지도 않았다. 문서가 씬을 순회했다고 말한 적이 없다.
        assertThat(scenes.findByContentMapIdAndName(map.id!!, "OptionsScene")).isNull()
    }

    // ---------- 3. 문서가 말을 바꿨을 때 ----------

    /**
     * 문서가 더 이상 말하지 않는 정적 간선은 내려가고, **`runtime` 과 이미 밟아 본 간선은 남는다.**
     *
     * 세 갈래가 한 재적재에서 갈린다:
     *
     * | 간선 | 재적재 뒤 | 왜 |
     * |---|---|---|
     * | `static`, 런타임 칸 비어 있음, 문서가 안 말함 | **지운다** | 코드에 없는 전이가 남으면 TC 가 일어나지 않는 이동을 기대한다 |
     * | `static`, `verified_at` 찍힘, 문서가 안 말함 | 남긴다 | QA 런이 실제로 밟았다. 문서가 놓친 것이지 없는 전이가 아니다 |
     * | `runtime` | 남긴다 | 정적 분석이 놓친 전이가 그것이고, 이 경로는 그것에 대해 아무 말도 하지 않는다 |
     *
     * 지우는 갈래가 `retireVanished` **앞에서** 도는 것이 중요하다. `hasRuntimeReferences` 가
     * `scene_edge` 를 참조로 세기 때문에, 지식 없는 정적 간선이 남아 있으면 사라진 기능이 영영 지워지지
     * 않고 매 재적재마다 `not-applicable` 로만 내려간다.
     */
    @Test
    fun `문서가 뺀 정적 간선만 내려가고 관측이 밟은 것과 runtime 은 남는다`(): Unit = runBlocking {
        val map = newContentMap("scene-edge-retire")
        ingest.ingest(register(map.id!!, evidenceDocument().toByteArray()))

        val scene = scenes.findByContentMapIdAndName(map.id!!, "TitleScene")!!
        val vanishing = edgeIdOf(map.id!!, "MapScene")

        // 정적 분석이 놓친 전이. QA 런만 아는 것이라 스캔이 손대면 안 된다.
        val runtimeEdge = edges.save(
            SceneEdgeEntity(
                fromSceneId = scene.id!!,
                toSceneName = "SecretScene",
                capabilityId = null,
                source = EdgeSource.RUNTIME.wire,
                verifiedAt = VERIFIED_AT,
                observedCount = 2,
            )
        )
        // 이미 밟아 본 정적 간선. 문서가 말을 바꿔도 밟았다는 사실은 남는다.
        val walked = edges.save(
            SceneEdgeEntity(
                fromSceneId = scene.id!!,
                toSceneName = "CreditsScene",
                capabilityId = null,
                source = EdgeSource.STATIC.wire,
                verifiedAt = VERIFIED_AT,
                observedCount = 1,
            )
        )

        val result = ingest.ingest(register(map.id!!, evidenceDocument(withSceneChange = false).toByteArray()))

        assertThat(result.sceneEdges).isEqualTo(1)
        assertThat(edges.findById(vanishing)).isNull()
        assertThat(edges.findById(runtimeEdge.id!!)).isNotNull()
        assertThat(edges.findById(walked.id!!)).isNotNull()
        // 문서가 계속 말하는 쪽은 그대로 있다 — 쓸어 내기가 이웃까지 가져가지 않는다.
        assertThat(edges.findById(edgeIdOf(map.id!!, "OptionsScene"))).isNotNull()

        assertThat(
            edges.findByFromSceneIdOrderByIdAsc(scene.id!!).toList().map { it.toSceneName },
        ).containsExactlyInAnyOrder("OptionsScene", "SecretScene", "CreditsScene")
    }

    // ---------- 문서 ----------

    /**
     * 씬 효과 둘을 내는 가장 작은 schema 6 문서.
     *
     * | 후보 | 씬 효과 | 도착 씬을 순회했나 |
     * |---|---|---|
     * | `Demo.TitleController::StartGame` (`Canvas/StartButton` 클릭) | `MapScene` | 아니오 — `scenes` 에 없다 |
     * | `Demo.TitleController::Update` (Escape) | `OptionsScene` | 아니오 |
     *
     * 둘 다 `scenes` 밖인 이유: 이 문서는 `TitleScene` 하나만 순회했다고 말한다. 그것이 실제 빌드에서
     * 흔한 모양이고(첫 씬만 열고 구운 문서), `to_scene_id` 가 nullable 인 이유 그대로다.
     *
     * [withSceneChange] 를 끄면 첫째의 씬 효과만 사라진다 — 기능은 그대로인데 전이만 없어진 것이라,
     * 쓸어 내기가 기능 삭제에 묻어가는 것이 아님을 가른다.
     */
    private fun evidenceDocument(withSceneChange: Boolean = true): String {
        val startGameEffect = if (withSceneChange) {
            """
            {
              "kind": "scene", "category": "observable", "target": "MapScene", "detail": null,
              "source": "System.Void Demo.TitleController::StartGame()", "offset": 12
            }
            """
        } else {
            """
            {
              "kind": "ui-value", "category": "observable", "target": "Canvas/Label.text",
              "detail": "loading", "source": "System.Void Demo.TitleController::StartGame()", "offset": 12
            }
            """
        }

        return """
            {
              "schema": 6,
              "capture": "editor",
              "capabilities": ["build-info-v1", "selector-v1", "visual-roles-v1"],
              "build": {
                "unity": "2022.3.62f3", "platform": "OSXEditor", "backend": "mono",
                "development": true, "sdk": "0.1.0", "evidence": "d4b31e4da9504b7d"
              },
              "scenes": ["TitleScene"],
              "types": {
                "Demo.TitleController": [
                  {
                    "owner": "Demo.TitleController",
                    "entry": "System.Void Demo.TitleController::StartGame()",
                    "entryId": "$START_GAME_ENTRY",
                    "source": "System.Void Demo.TitleController::StartGame()",
                    "methodId": "$START_GAME_ENTRY",
                    "recordKind": "candidate",
                    "triggerKind": "unity-event",
                    "confidence": "verified",
                    "calledBy": [],
                    "callPath": ["System.Void Demo.TitleController::StartGame()"],
                    "condition": {"kind": "always"},
                    "inputs": [],
                    "effects": [$startGameEffect],
                    "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
                  },
                  {
                    "owner": "Demo.TitleController",
                    "entry": "System.Void Demo.TitleController::Update()",
                    "entryId": "$OPTIONS_ENTRY",
                    "source": "System.Void Demo.TitleController::Update()",
                    "methodId": "$OPTIONS_ENTRY",
                    "recordKind": "candidate",
                    "triggerKind": "lifecycle",
                    "confidence": "derived",
                    "calledBy": [],
                    "callPath": ["System.Void Demo.TitleController::Update()"],
                    "condition": {"kind": "gesture", "input": "key:Escape (down)", "offset": 7},
                    "inputs": [
                      {"kind": "key", "control": "Escape", "phase": "down", "absent": false, "offset": 7}
                    ],
                    "effects": [
                      {
                        "kind": "scene", "category": "observable", "target": "OptionsScene", "detail": null,
                        "source": "System.Void Demo.TitleController::Update()", "offset": 11
                      }
                    ],
                    "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
                  }
                ]
              },
              "unplaced": {},
              "objects": [
                {
                  "path": "Canvas/StartButton",
                  "selector": "Canvas[1]/StartButton[1]",
                  "scene": "TitleScene",
                  "active": true,
                  "visuals": [],
                  "components": [
                    {
                      "type": "UnityEngine.UI.Button",
                      "calls": [
                        {
                          "event": "m_OnClick", "targetType": "Demo.TitleController",
                          "targetPath": "TitleController", "method": "StartGame"
                        }
                      ],
                      "refs": []
                    }
                  ]
                },
                {
                  "path": "TitleController",
                  "selector": "TitleController[1]",
                  "scene": "TitleScene",
                  "active": true,
                  "visuals": [],
                  "components": [{"type": "Demo.TitleController", "calls": [], "refs": []}]
                }
              ],
              "persistentObjects": [],
              "gaps": []
            }
        """
    }

    // ---------- 픽스처 ----------

    /** 게임 빌드는 프로젝트에 FK 로 매달려 있어 프로젝트부터 만든다. */
    private suspend fun newContentMap(label: String): ContentMapEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(
                name = "$label-${System.nanoTime()}",
                genre = "ACTION",
                createdAt = now,
                updatedAt = now,
            )
        )
        val build = gameBuilds.save(
            GameBuildEntity(
                projectId = project.id!!,
                version = "v${System.nanoTime()}",
                createdAt = now,
                updatedAt = now,
            )
        )
        return contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3",
                backend = "mono",
                development = true,
                sdkVersion = "0.1.0",
            )
        )
    }

    /**
     * 등록 경로가 남기는 것과 같은 상태를 만든다 — 스토리지에 바이트가 있고 DB 에는 포인터만 있다.
     *
     * `receivedAt` 을 직접 채우는 이유: 컬럼이 NOT NULL 이고 기본값은 INSERT 가 값을 안 실을 때만
     * 쓰이는데, Spring Data 는 null 을 그대로 싣는다.
     */
    private suspend fun register(contentMapId: Long, bytes: ByteArray): ContentMapDocumentEntity {
        val objectKey = "content-map/$contentMapId/${System.nanoTime()}.json"
        fakeStorage.put(objectKey, bytes)
        return documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = MessageDigest.getInstance("SHA-256")
                    .digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                byteSize = bytes.size.toLong(),
                receivedAt = Instant.now(),
            )
        )
    }

    /**
     * 같은 씬 안의 화면 둘과 그 사이 전이 하나를 만들고 전이 id 를 돌려준다.
     *
     * `scene_edge.first_observed_transition_id` 가 `screen_transition` 을 FK 로 가리켜, 그 칸을 채우려면
     * 화면부터 있어야 한다. 관측 경로가 아직 없어 SQL 로 직접 넣는다.
     */
    private suspend fun stampObservedTransition(sceneId: Long): Long {
        suspend fun newScreen(name: String): Long = db
            .sql("INSERT INTO screen (scene_id, name, discriminator) VALUES (:scene, :name, '[]'::jsonb) RETURNING id")
            .bind("scene", sceneId)
            .bind("name", name)
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()

        return db
            .sql(
                """
                INSERT INTO screen_transition (from_screen_id, to_screen_id, kind, crosses_scene, observed_count)
                VALUES (:from, :to, 'action', true, 1)
                RETURNING id
                """
            )
            .bind("from", newScreen("title"))
            .bind("to", newScreen("map"))
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()
    }

    private suspend fun edgeCountOf(contentMapId: Long): Int = countOf(
        "SELECT count(*) FROM scene_edge e JOIN scene s ON s.id = e.from_scene_id WHERE s.content_map_id = :id",
        contentMapId,
    )

    /** 도착 씬 이름으로 간선을 찾는다. 작은 문서에서는 이름이 곧 그 간선의 유일한 식별자다. */
    private suspend fun edgeIdOf(contentMapId: Long, toSceneName: String): Long = db
        .sql(
            """
            SELECT e.id FROM scene_edge e
            JOIN scene s ON s.id = e.from_scene_id
            WHERE s.content_map_id = :id AND e.to_scene_name = :name
            """
        )
        .bind("id", contentMapId)
        .bind("name", toSceneName)
        .map { row, _ -> (row.get(0) as Number).toLong() }
        .one()
        .awaitSingle()

    private suspend fun countOf(sql: String, contentMapId: Long): Int = db
        .sql(sql)
        .bind("id", contentMapId)
        .map { row, _ -> (row.get(0) as Number).toInt() }
        .one()
        .awaitSingle()

    private companion object {
        const val GOLDEN_PATH = "src/test/resources/contentmap/wv-editor-latest.json"

        const val START_GAME_ENTRY = "Assembly-CSharp|Demo.TitleController|StartGame|System.Void()"
        const val OPTIONS_ENTRY = "Assembly-CSharp|Demo.TitleController|Update|System.Void()"

        /** QA 런이 밟았다고 찍는 시각. 고정값이라 단언이 그대로 비교한다. */
        val VERIFIED_AT: Instant = Instant.parse("2026-08-20T03:04:05Z")
    }
}
