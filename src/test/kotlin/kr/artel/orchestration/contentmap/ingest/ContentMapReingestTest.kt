package kr.artel.orchestration.contentmap.ingest

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.reactor.awaitSingleOrNull
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Actionability
import kr.artel.orchestration.contentmap.entity.Applicability
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityOrigin
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.Interaction
import kr.artel.orchestration.contentmap.entity.Observability
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.entity.SceneOrigin
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.entity.VerificationState
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
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
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.r2dbc.core.awaitRowsUpdated
import org.springframework.test.context.ActiveProfiles
import java.security.MessageDigest
import java.time.Instant

/**
 * **재적재**를 검증한다 — 같은 게임의 근거 문서가 두 번째로 올라왔을 때 적재기가 무엇을 남기고
 * 무엇을 지우는가.
 *
 * 첫 적재는 빈 표에 쓰는 일이라 틀려도 눈에 띈다. 재적재는 **이미 쌓인 것 위에** 쓰는 일이고,
 * 여기서 틀리면 조용히 지식이 사라진다. SDK 는 게임 실행마다 문서를 굽고 코드는 매일 바뀌므로,
 * 이 경로는 예외가 아니라 정상 상태다. 그래서 아래 다섯 가지만 고른다:
 *
 * 1. 같은 문서를 두 번 적재해도 기능 집합이 그대로인가(멱등)
 * 2. `observed` · `inferred` 출신이 살아남는가 — QA 가 배운 것은 스캔의 파생물이 아니다
 * 3. 사라진 기능에 런타임 참조가 매달려 있으면 지우지 않고 내리는가
 * 4. 참조가 없으면 지우는가 — 재계산 가능한 파생물을 남기면 표가 거짓말을 한다
 * 5. 확인(`verification`)이 근거가 실제로 달라졌을 때만 되돌아가는가
 *
 * 문서는 **손으로 짠 작은 것**을 쓴다. 실측 문서(1.4MB)는 후보가 수백 개라 어떤 행이 왜 사라졌는지
 * 단언에서 읽을 수 없다. 여기서 필요한 것은 규모가 아니라 "이 행 하나가 어떻게 되었나"다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapReingestTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var edges: SceneEdgeRepository
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    // ---------- 고정 문자열. 단언이 이 값으로 행을 찾는다 ----------

    private val startGameEntry = "Assembly-CSharp|Demo.TitleController|StartGame|System.Void()"
    private val quitPopupEntry = "Assembly-CSharp|Demo.TitleController|Update|System.Void()"
    private val optionsEntry = "Assembly-CSharp|Demo.OptionsController|Update|System.Void()"

    // ---------- 문서 ----------

    /**
     * 후보 셋을 내는 가장 작은 schema 6 문서.
     *
     * | 후보 | 어떻게 씬에 붙나 | 조작 |
     * |---|---|---|
     * | `Demo.TitleController::StartGame` | `Canvas/StartButton` 의 `m_OnClick` 배선 | click |
     * | `Demo.TitleController::Update` | 오너 타입이 `TitleController` 오브젝트에 놓임 | press Escape |
     * | `Demo.OptionsController::Update` | 오너 타입이 `OptionsRoot` 오브젝트에 놓임 | press O |
     *
     * [withOptions] 를 끄면 셋째가 문서에서 사라진다 — 그것이 "코드에서 기능이 없어졌다"의 최소
     * 재현이다. [startGameConfidence] 는 5 번 규칙의 지렛대다(그 테스트의 KDoc 참고).
     */
    private fun evidenceDocument(
        startGameConfidence: String = "verified",
        withOptions: Boolean = true,
        capture: String = Capture.EDITOR.wire,
    ): String {
        val startGame = """
            {
              "owner": "Demo.TitleController",
              "entry": "System.Void Demo.TitleController::StartGame()",
              "entryId": "$startGameEntry",
              "source": "System.Void Demo.TitleController::StartGame()",
              "methodId": "$startGameEntry",
              "recordKind": "candidate",
              "triggerKind": "unity-event",
              "confidence": "$startGameConfidence",
              "calledBy": [],
              "callPath": ["System.Void Demo.TitleController::StartGame()"],
              "condition": {"kind": "always"},
              "inputs": [],
              "effects": [
                {
                  "kind": "scene", "category": "observable", "target": "MapScene", "detail": null,
                  "source": "System.Void Demo.TitleController::StartGame()", "offset": 12
                }
              ],
              "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
            }
        """

        val quitPopup = """
            {
              "owner": "Demo.TitleController",
              "entry": "System.Void Demo.TitleController::Update()",
              "entryId": "$quitPopupEntry",
              "source": "System.Void Demo.TitleController::Update()",
              "methodId": "$quitPopupEntry",
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
                  "kind": "active-state", "category": "availability", "target": "Canvas/QuitPopup",
                  "detail": "true", "source": "System.Void Demo.TitleController::Update()", "offset": 11
                }
              ],
              "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
            }
        """

        val options = """
            "Demo.OptionsController": [
              {
                "owner": "Demo.OptionsController",
                "entry": "System.Void Demo.OptionsController::Update()",
                "entryId": "$optionsEntry",
                "source": "System.Void Demo.OptionsController::Update()",
                "methodId": "$optionsEntry",
                "recordKind": "candidate",
                "triggerKind": "lifecycle",
                "confidence": "derived",
                "calledBy": [],
                "callPath": ["System.Void Demo.OptionsController::Update()"],
                "condition": {"kind": "gesture", "input": "key:O (down)", "offset": 3},
                "inputs": [
                  {"kind": "key", "control": "O", "phase": "down", "absent": false, "offset": 3}
                ],
                "effects": [
                  {
                    "kind": "active-state", "category": "availability", "target": "OptionsRoot",
                    "detail": "true", "source": "System.Void Demo.OptionsController::Update()", "offset": 5
                  }
                ],
                "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
              }
            ]
        """

        val types = if (withOptions) {
            """"Demo.TitleController": [$startGame, $quitPopup], $options"""
        } else {
            """"Demo.TitleController": [$startGame, $quitPopup]"""
        }

        return """
            {
              "schema": 6,
              "capture": "$capture",
              "capabilities": ["build-info-v1", "selector-v1", "visual-roles-v1"],
              "build": {
                "unity": "2022.3.62f3", "platform": "OSXEditor", "backend": "mono",
                "development": true, "sdk": "0.1.0", "evidence": "d4b31e4da9504b7d"
              },
              "scenes": ["TitleScene"],
              "types": { $types },
              "unplaced": {},
              "objects": [
                {
                  "path": "Canvas/StartButton",
                  "selector": "Canvas[1]/StartButton[1]",
                  "scene": "TitleScene",
                  "active": true,
                  "visuals": [
                    {
                      "role": "control-caption", "value": "START",
                      "from": "Canvas/StartButton", "type": "UnityEngine.UI.Text"
                    }
                  ],
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
                },
                {
                  "path": "OptionsRoot",
                  "selector": "OptionsRoot[1]",
                  "scene": "TitleScene",
                  "active": true,
                  "visuals": [],
                  "components": [{"type": "Demo.OptionsController", "calls": [], "refs": []}]
                }
              ],
              "persistentObjects": [],
              "gaps": []
            }
        """
    }

    /**
     * **capture 가 다른 두 문서가 같은 지도에 쌓이고 씬이 합쳐진다**(ARTEL-642).
     *
     * 지도가 빌드당 하나가 되면서 두 스캔 자리의 결과가 한 행에 모인다. 씬을 새로 만들지 않고
     * 같은 행을 덮어쓰는 것이 요점이다 — 새로 만들면 그 씬에 매달린 화면과 관측이 갈라진 지도에
     * 갇힌다.
     *
     * `capture` 는 사라지지 않고 씬으로 내려가 **마지막 walk 의 값**이 남는다. 그것이 지금 그 씬에
     * 담긴 값의 출처이기 때문이다.
     */
    @Test
    fun `capture 가 다른 두 문서가 한 지도에 쌓이고 씬이 합쳐진다`(): Unit = runBlocking {
        val map = newContentMap()

        ingestNew(map.id!!, evidenceDocument(capture = Capture.EDITOR.wire))
        val afterEditor = scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList()
        assertThat(afterEditor).singleElement().satisfies({
            assertThat(it.name).isEqualTo("TitleScene")
            assertThat(it.capture).isEqualTo(Capture.EDITOR.wire)
            assertThat(it.origin).isEqualTo(SceneOrigin.EVIDENCE.wire)
        })

        ingestNew(map.id!!, evidenceDocument(capture = Capture.PLAYER.wire))
        val afterPlayer = scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList()

        assertThat(afterPlayer).singleElement().satisfies({
            // 새 행이 아니라 같은 행이다. id 가 그것을 말한다.
            assertThat(it.id).isEqualTo(afterEditor.single().id)
            assertThat(it.capture).isEqualTo(Capture.PLAYER.wire)
        })
        assertThat(contentMaps.findByGameBuildId(map.gameBuildId)?.id).isEqualTo(map.id)
    }

    /**
     * 관측이 만든 씬에 근거가 닿으면 `origin` 이 **evidence 로 올라간다.** 내려가지는 않는다.
     *
     * 근거가 확인해 준 씬을 계속 `observed` 로 두면, "정적 분석이 이 씬을 안다"와 "런타임에서만
     * 봤다"가 구분되지 않는다. 반대 방향은 막을 필요가 없다 — 적재기가 origin 을 evidence 로만
     * 쓴다.
     */
    @Test
    fun `관측이 만든 씬이 근거로 확인되면 origin 이 올라간다`(): Unit = runBlocking {
        val map = newContentMap()
        val observed = scenes.save(
            SceneEntity(
                contentMapId = map.id!!,
                name = "TitleScene",
                origin = SceneOrigin.OBSERVED.wire,
            )
        )

        ingestNew(map.id!!, evidenceDocument())

        val after = scenes.findById(observed.id!!)!!
        assertThat(after.origin).isEqualTo(SceneOrigin.EVIDENCE.wire)
        assertThat(after.capture).isEqualTo(Capture.EDITOR.wire)
    }

    /**
     * `DontDestroyOnLoad` 오브젝트가 실제 실행 scene 에 앉고, 무엇을 읽고 그렇게 정했는지가
     * `capability_proof` 에 남는다(ARTEL-460).
     *
     * `Demo.HudController` 는 `DontDestroyOnLoad` 에만 있어 어느 scene 에도 놓이지 않았다. `scene` 을
     * 넘어 살아남으므로 `TitleScene` 과 `MapScene` 에 **둘 다** 앉는다. 그 조건이 `MapMover.stage` 를
     * 읽고 `Demo.MapMover` 는 `MapScene` 에만 놓였으므로, 근거가 지목한 것은 `MapScene` 하나다.
     * scene 이름이 아닌 `DontDestroyOnLoad` 는 `scene` 표에 앉지 않는다.
     */
    @Test
    fun `DontDestroyOnLoad 오브젝트가 모든 scene 에 앉고 지목한 scene 에 근거가 남는다`(): Unit = runBlocking {
        val map = newContentMap()

        val result = ingestNew(map.id!!, persistentEvidenceDocument())

        assertThat(scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList().map { it.name })
            .containsExactly("MapScene", "TitleScene")
        // `Demo.HudController` 는 `scene` 둘에 다 앉는다. 지목받은 것은 `MapScene` 하나다.
        assertThat(result.persistentCapabilities).isEqualTo(2)
        assertThat(result.evidencedPersistentCapabilities).isEqualTo(1)

        val hudRows = db.sql(
            """
            SELECT s.name, c.scene_presence FROM capability c
            JOIN scene s ON s.id = c.scene_id
            JOIN capability_evidence ce ON ce.capability_id = c.id
            WHERE ce.entry_id = :entryId ORDER BY s.name
            """
        ).bind("entryId", hudEntry).map { row, _ ->
            row.get("name", String::class.java) to row.get("scene_presence", String::class.java)
        }.all().collectList().awaitSingle()

        assertThat(hudRows).containsExactly(
            "MapScene" to "persistent-evidenced",
            "TitleScene" to "persistent-unconfirmed",
        )

        // 사슬은 지목받은 행의 것이다. 같은 근거의 `TitleScene` 행에는 사슬이 없다.
        val hudId = db.sql(
            """
            SELECT c.id FROM capability c
            JOIN scene s ON s.id = c.scene_id
            JOIN capability_evidence ce ON ce.capability_id = c.id
            WHERE ce.entry_id = :entryId AND s.name = 'MapScene'
            """
        ).bind("entryId", hudEntry)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingle()

        val chain = db.sql(
            """
            SELECT source, relation, target, rule, resolution
            FROM capability_proof WHERE capability_id = :id AND effect_id IS NULL ORDER BY seq
            """
        ).bind("id", hudId).map { row, _ ->
            listOf("source", "relation", "target", "rule", "resolution").map { row.get(it, String::class.java) }
        }.all().collectList().awaitSingle()

        assertThat(chain).containsExactly(
            listOf("DontDestroyOnLoad/Hud", "condition-reads", "MapMover.stage",
                "persistent-condition-subject-placed", "derived"),
            listOf("MapMover.stage", "owned-by", "Demo.MapMover",
                "persistent-condition-subject-placed", "derived"),
            listOf("Demo.MapMover", "placed-in", "MapScene",
                "persistent-condition-subject-placed", "derived"),
        )
    }

    /**
     * 옛 적재가 앉혀 둔 `DontDestroyOnLoad` scene 행이 재적재로 사라진다(ARTEL-460).
     *
     * scene 행은 이름으로 upsert 되므로 규칙이 바뀌어 그 이름이 더는 나오지 않아도 옛 행은 남는다.
     * **아무도 아무것도 모르는 행만** 지운다 — capability · screen · scene_edge 가 없고, QA 런이 서
     * 보지도 캡처를 찍지도 않은 행이다. 아래 `TitleScene` 이 그대로 남는 것이 그 경계를 지킨다는
     * 증거다.
     */
    @Test
    fun `근거가 더는 말하지 않는 빈 scene 행이 재적재로 사라진다`(): Unit = runBlocking {
        val map = newContentMap()
        val stale = scenes.save(
            SceneEntity(contentMapId = map.id!!, name = "DontDestroyOnLoad", origin = SceneOrigin.EVIDENCE.wire)
        )

        val result = ingestNew(map.id!!, evidenceDocument())

        assertThat(result.retiredScenes).isEqualTo(1)
        assertThat(scenes.findById(stale.id!!)).isNull()
        assertThat(scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList().map { it.name })
            .containsExactly("TitleScene")
    }

    private val hudEntry = "Assembly-CSharp|Demo.HudController|Start|System.Void()"
    private val moverEntry = "Assembly-CSharp|Demo.MapMover|Update|System.Void()"

    /**
     * `DontDestroyOnLoad` 오브젝트 하나를 든 가장 작은 문서.
     *
     * `Demo.MapMover` 는 `MapScene` 에만 놓였고, `Demo.HudController` 는 어느 scene 에도 놓이지
     * 않은 채 `MapMover.stage` 를 읽는다.
     */
    private fun persistentEvidenceDocument(): String = """
        {
          "schema": 7,
          "capture": "editor-play",
          "capabilities": ["build-info-v1", "selector-v1", "persistent-objects-v1"],
          "build": {"unity": "2022.3.62f3", "platform": "OSXEditor", "backend": "mono",
                    "development": true, "sdk": "0.1.0", "evidence": "5a1c0de5a1c0de5a"},
          "scenes": ["TitleScene", "MapScene"],
          "types": {
            "Demo.MapMover": [
              {
                "owner": "Demo.MapMover",
                "entry": "System.Void Demo.MapMover::Update()",
                "entryId": "$moverEntry",
                "source": "System.Void Demo.MapMover::Update()",
                "methodId": "$moverEntry",
                "recordKind": "candidate", "triggerKind": "lifecycle", "confidence": "verified",
                "calledBy": [], "callPath": ["System.Void Demo.MapMover::Update()"],
                "condition": {"kind": "gesture", "input": "key:RightArrow (down)", "offset": 3},
                "inputs": [
                  {"kind": "key", "control": "RightArrow", "phase": "down", "absent": false, "offset": 3}
                ],
                "effects": [
                  {"kind": "write", "category": "state", "target": "MapMover.stage", "detail": "1",
                   "source": "System.Void Demo.MapMover::Update()", "offset": 5}
                ],
                "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
              }
            ],
            "Demo.HudController": [
              {
                "owner": "Demo.HudController",
                "entry": "System.Void Demo.HudController::Start()",
                "entryId": "$hudEntry",
                "source": "System.Void Demo.HudController::Start()",
                "methodId": "$hudEntry",
                "recordKind": "candidate", "triggerKind": "lifecycle", "confidence": "verified",
                "calledBy": [], "callPath": ["System.Void Demo.HudController::Start()"],
                "condition": {
                  "kind": "test", "left": "MapMover.stage", "operator": "<=", "right": "0",
                  "context": "static", "offset": 9
                },
                "inputs": [],
                "effects": [
                  {"kind": "active-state", "category": "availability", "target": "Hud/Window",
                   "detail": "true", "source": "System.Void Demo.HudController::Start()", "offset": 11}
                ],
                "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
              }
            ]
          },
          "unplaced": {},
          "objects": [
            {
              "path": "Mover", "selector": "Mover[1]", "scene": "MapScene", "active": true,
              "visuals": [], "components": [{"type": "Demo.MapMover", "calls": [], "refs": []}]
            }
          ],
          "persistentObjects": [
            {
              "path": "Hud", "selector": "Hud[1]", "scene": "DontDestroyOnLoad", "active": true,
              "visuals": [], "components": [{"type": "Demo.HudController", "calls": [], "refs": []}]
            }
          ],
          "gaps": []
        }
    """

    // ---------- 픽스처 ----------

    /** 게임 빌드는 프로젝트에 FK 로 매달려 있어 프로젝트부터 만든다. */
    private suspend fun newContentMap(): ContentMapEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(
                name = "reingest-${System.nanoTime()}",
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
                evidencePromises = Json.of("""["build-info-v1","selector-v1","visual-roles-v1"]"""),
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
    private suspend fun register(contentMapId: Long, json: String): ContentMapDocumentEntity {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val objectKey = "evidence/${System.nanoTime()}.json"
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

    /** 문서를 등록하고 곧바로 적재한다. 두 걸음이 늘 붙어 다녀 한 줄로 둔다. */
    private suspend fun ingestNew(contentMapId: Long, json: String): IngestResult =
        service.ingest(register(contentMapId, json))

    // ---------- 조회. 상태는 표에서 직접 읽는다 ----------

    /**
     * 근거 출신 기능의 안정 키들.
     *
     * `id` 가 아니라 키를 비교하는 이유: `id` 는 `BIGSERIAL` 이라 지웠다 넣으면 밀리지만, 재적재가
     * 지켜야 하는 것은 "같은 기능이 같은 키를 갖는다"이지 "번호가 그대로"가 아니다.
     */
    private suspend fun evidenceKeys(contentMapId: Long): Set<String> =
        db.sql("SELECT capability_key FROM capability WHERE content_map_id = :map AND origin = 'evidence'")
            .bind("map", contentMapId)
            .map { row, _ -> row.get("capability_key", String::class.java)!! }
            .all()
            .collectList()
            .awaitSingle()
            .toSet()

    /** 근거의 진입점으로 기능을 찾는다. 지도 안에서만 찾는다 — 같은 진입점이 다른 테스트에도 있다. */
    private suspend fun capabilityIdOf(contentMapId: Long, entryId: String): Long? =
        db.sql(
            """
            SELECT c.id FROM capability c
            JOIN capability_evidence e ON e.capability_id = c.id
            WHERE c.content_map_id = :map AND e.entry_id = :entry
            """
        )
            .bind("map", contentMapId)
            .bind("entry", entryId)
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one()
            .awaitSingleOrNull()

    /** 축과 유도 컬럼을 한 번에 본다. `status` 는 생성 컬럼이라 엔티티로는 읽는 값이 축과 어긋날 수 없다. */
    private suspend fun capabilityRow(id: Long): Map<String, Any?>? =
        db.sql("SELECT applicability, status, verification, origin FROM capability WHERE id = :id")
            .bind("id", id)
            .fetch()
            .one()
            .awaitSingleOrNull()

    private suspend fun analysisConfidenceOf(capabilityId: Long): String? =
        db.sql("SELECT analysis_confidence FROM capability_evidence WHERE capability_id = :id")
            .bind("id", capabilityId)
            .map { row, _ -> row.get("analysis_confidence", String::class.java)!! }
            .one()
            .awaitSingleOrNull()

    private suspend fun confirm(capabilityId: Long) {
        db.sql("UPDATE capability SET verification = 'confirmed' WHERE id = :id")
            .bind("id", capabilityId)
            .fetch()
            .awaitRowsUpdated()
    }

    // ---------- 1. 멱등 ----------

    /**
     * 같은 문서를 두 번 적재해도 기능 집합이 그대로다.
     *
     * 깨지면 스캔을 돌릴 때마다 표가 부푼다 — 같은 기능이 문서 재전송 수만큼 늘고, TC 생성기는
     * 같은 버튼을 여러 번 누르는 시나리오를 만든다. 키가 흔들리는 경우는 더 나쁘다:
     * `scene_edge.capability_id` 가 든 런타임 지식이 매 적재마다 주인을 잃는다.
     */
    @Test
    fun `같은 문서를 두 번 적재해도 기능 집합이 그대로다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = register(map.id!!, evidenceDocument())

        val first = service.ingest(document)
        val before = evidenceKeys(map.id!!)

        // 등록 경로가 다시 집어 왔을 때와 같은 상태로 넣는다 — 도장이 찍힌 행이다.
        val second = service.ingest(documents.findById(document.id!!)!!)
        val after = evidenceKeys(map.id!!)

        assertThat(first.capabilities).isEqualTo(3)
        assertThat(second.capabilities).isEqualTo(3)
        assertThat(second.deleted).isZero()
        assertThat(second.markedNotApplicable).isZero()
        assertThat(before).hasSize(3)
        assertThat(after).isEqualTo(before)

        // 씬도 늘지 않는다. 늘면 기능이 씬마다 갈려 커버리지 분모가 매 스캔 부푼다.
        assertThat(scenes.findByContentMapIdOrderByNameAsc(map.id!!).toList()).hasSize(1)
    }

    // ---------- 2. 다른 출신 ----------

    /**
     * 재적재는 `observed` · `inferred` 출신을 건드리지 않는다.
     *
     * 저 둘은 QA 런이 실제로 눌러 보고 배운 것이라 **다시 만들 수 없다.** 스캔이 다시 돌았다고
     * 함께 지우면 게임을 다시 플레이하기 전에는 되돌아오지 않고, 그 손실은 로그에도 남지 않는다.
     */
    @Test
    fun `재적재가 다른 출신의 기능을 건드리지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = register(map.id!!, evidenceDocument())
        service.ingest(document)

        val scene = scenes.findByContentMapIdAndName(map.id!!, "TitleScene")!!
        val observed = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = map.id!!,
                origin = CapabilityOrigin.OBSERVED.wire,
                verification = VerificationState.CONFIRMED.wire,
                summary = "`Canvas/LogoImage` 를 3회 연속 클릭하면 `DebugPanel` 이 열린다",
                interaction = Interaction.CLICK.wire,
                actionability = Actionability.RUNNABLE.wire,
                observability = Observability.OBSERVABLE.wire,
            )
        )
        val inferred = capabilities.save(
            CapabilityEntity(
                sceneId = scene.id!!,
                contentMapId = map.id!!,
                origin = CapabilityOrigin.INFERRED.wire,
                summary = "`Canvas/StartButton` 이 비활성인 동안은 어떤 조작도 받지 않는다",
                interaction = Interaction.NONE.wire,
                actionability = Actionability.NOT_A_STEP.wire,
            )
        )

        // 사라진 기능이 있는 문서로 재적재한다. 내리는 경로가 도는 동안에도 남의 출신은 남아야 한다.
        val result = service.ingest(register(map.id!!, evidenceDocument(withOptions = false)))

        assertThat(result.deleted).isEqualTo(1)

        val observedAfter = capabilityRow(observed.id!!)
        assertThat(observedAfter).isNotNull()
        assertThat(observedAfter!!["origin"]).isEqualTo(CapabilityOrigin.OBSERVED.wire)
        // 확인도 그대로다. 근거 재적재는 관측이 무엇을 확인했는지에 대해 아무 말도 하지 않는다.
        assertThat(observedAfter["verification"]).isEqualTo(VerificationState.CONFIRMED.wire)
        assertThat(observedAfter["applicability"]).isEqualTo(Applicability.APPLIES.wire)

        val inferredAfter = capabilityRow(inferred.id!!)
        assertThat(inferredAfter).isNotNull()
        assertThat(inferredAfter!!["origin"]).isEqualTo(CapabilityOrigin.INFERRED.wire)
        assertThat(inferredAfter["applicability"]).isEqualTo(Applicability.APPLIES.wire)
    }

    // ---------- 3. 사라졌는데 참조가 있다 ----------

    /**
     * 사라진 기능에 런타임 참조가 매달려 있으면 **지우지 않고 내린다.**
     *
     * 지우면 `capability_observation` 이 CASCADE 로 함께 사라지고 `scene_edge.capability_id` 는
     * SET NULL 이 된다 — 코드에서 버튼 하나가 없어진 일이 QA 가 눌러 본 기록과 "이 씬에서 저 씬으로
     * 갈 수 있다"는 지식까지 지우는 일이 된다. 행은 남기고 TC 창구에서만 빼는 것이 그 둘을 함께
     * 지키는 유일한 길이다.
     *
     * `scene_edge` 를 참조로 쓰는 이유: `capability_observation` 은 `qa_run` 을 요구해 픽스처가
     * 더 필요하고, 검증하려는 것은 "참조가 있다"이지 어떤 참조인지가 아니다.
     */
    @Test
    fun `참조가 매달린 사라진 기능은 지우지 않고 적용 불가로 내린다`(): Unit = runBlocking {
        val map = newContentMap()
        ingestNew(map.id!!, evidenceDocument())

        val scene = scenes.findByContentMapIdAndName(map.id!!, "TitleScene")!!
        val vanishing = capabilityIdOf(map.id!!, optionsEntry)!!
        edges.save(
            SceneEdgeEntity(
                fromSceneId = scene.id!!,
                toSceneName = "OptionsScene",
                capabilityId = vanishing,
                source = EdgeSource.RUNTIME.wire,
                verifiedAt = Instant.now(),
                observedCount = 3,
            )
        )

        val result = ingestNew(map.id!!, evidenceDocument(withOptions = false))

        assertThat(result.markedNotApplicable).isEqualTo(1)
        assertThat(result.deleted).isZero()

        val row = capabilityRow(vanishing)
        assertThat(row).isNotNull()
        assertThat(row!!["applicability"]).isEqualTo(Applicability.NOT_APPLICABLE.wire)
        // 세 축에서 유도된 값. TC 창구는 이 칸으로 거른다.
        assertThat(row["status"]).isEqualTo(SpecStatus.UNREACHABLE_PRECONDITION.wire)

        // 매달려 있던 지식이 주인을 잃지 않았다. 이것이 지우지 않은 이유 전부다.
        //
        // 도착 씬 이름으로 짚는다. 적재기가 ARTEL-445 부터 `startGame` 의 `kind='scene'` 효과로
        // `TitleScene → MapScene` 정적 간선을 함께 쓰므로, 이 씬에서 나가는 간선은 하나가 아니다.
        val edge = edges.findByFromSceneIdAndToSceneName(scene.id!!, "OptionsScene").toList().single()
        assertThat(edge.capabilityId).isEqualTo(vanishing)
        assertThat(edge.observedCount).isEqualTo(3)

        // 남은 둘은 그대로 적용된다 — 내리는 경로가 이웃까지 쓸어가지 않는다.
        assertThat(capabilityRow(capabilityIdOf(map.id!!, startGameEntry)!!)!!["applicability"])
            .isEqualTo(Applicability.APPLIES.wire)
    }

    // ---------- 4. 사라졌고 참조가 없다 ----------

    /**
     * 참조가 없는 사라진 기능은 지운다.
     *
     * 근거 출신 기능은 문서에서 다시 계산되는 파생물이다. 남겨 두면 코드에 없는 버튼이 표에 남아
     * TC 생성기가 존재하지 않는 것을 누르는 스텝을 만들고, 그 실패가 게임의 결함으로 보고된다.
     */
    @Test
    fun `참조 없는 사라진 기능은 지운다`(): Unit = runBlocking {
        val map = newContentMap()
        ingestNew(map.id!!, evidenceDocument())
        val vanishing = capabilityIdOf(map.id!!, optionsEntry)!!

        val result = ingestNew(map.id!!, evidenceDocument(withOptions = false))

        assertThat(result.deleted).isEqualTo(1)
        assertThat(result.markedNotApplicable).isZero()
        assertThat(result.capabilities).isEqualTo(2)
        assertThat(capabilityRow(vanishing)).isNull()
        assertThat(evidenceKeys(map.id!!)).hasSize(2)
        // 근거 행도 함께 사라진다. 남으면 `v_spec_gap` 이 주인 없는 근거를 세게 된다.
        assertThat(analysisConfidenceOf(vanishing)).isNull()
    }

    // ---------- 5. 확인 되돌리기 ----------

    /**
     * 확인(`verification`)은 **근거가 실제로 달라졌을 때만** 되돌아간다.
     *
     * 두 방향이 다 비싸다. 매번 되돌리면 문서가 한 줄 때문에 다시 구워진 날 멀쩡한 기능 수백 개의
     * 확인이 버려져 QA 가 같은 것을 다시 누른다. 반대로 절대 되돌리지 않으면 코드가 바뀐 기능에
     * "확인됨"이 남아, 그 표시가 지금 코드에 대한 것이 아니게 되고 아무도 그것을 모른다.
     *
     * **조건 트리를 흔들지 않는 이유:** `capability_key` 가 조건을 해시한다([CapabilityKey]).
     * `right` 값을 바꾸면 키가 달라져 옛 행은 "사라진 기능"이 되고 새 행이 태어난다 — 그러면
     * 검증하는 것이 확인 되돌리기가 아니라 삭제·삽입이 된다. 키가 해시하지 않으면서
     * `capability_evidence` 에는 그대로 실리는 칸이 필요하고, `confidence` 가 그것이다
     * (`verified` → `derived` 는 `analysis_confidence` 를 `exact` → `derived` 로 바꾼다).
     */
    @Test
    fun `근거가 그대로면 확인이 살아남고 달라지면 되돌아간다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = register(map.id!!, evidenceDocument())
        service.ingest(document)

        val capabilityId = capabilityIdOf(map.id!!, startGameEntry)!!
        confirm(capabilityId)
        assertThat(analysisConfidenceOf(capabilityId)).isEqualTo("exact")

        // 같은 문서 재적재. 문서가 다시 구워졌을 뿐 코드는 그대로인 가장 흔한 경우다.
        service.ingest(documents.findById(document.id!!)!!)

        assertThat(capabilityRow(capabilityId)!!["verification"])
            .isEqualTo(VerificationState.CONFIRMED.wire)

        // 같은 키, 다른 근거. 확신도만 달라져도 그 판정은 지금 코드에 대한 것이 아니다.
        ingestNew(map.id!!, evidenceDocument(startGameConfidence = "derived"))

        val sameRow = capabilityIdOf(map.id!!, startGameEntry)
        assertThat(sameRow).isEqualTo(capabilityId)
        assertThat(analysisConfidenceOf(capabilityId)).isEqualTo("derived")
        assertThat(capabilityRow(capabilityId)!!["verification"])
            .isEqualTo(VerificationState.UNVERIFIED.wire)

        // 옆 행은 근거가 그대로라 건드려지지 않는다 — 되돌리기가 문서 단위가 아니라 행 단위다.
        val neighbour = capabilityIdOf(map.id!!, quitPopupEntry)!!
        confirm(neighbour)
        ingestNew(map.id!!, evidenceDocument(startGameConfidence = "partial"))
        assertThat(capabilityRow(neighbour)!!["verification"])
            .isEqualTo(VerificationState.CONFIRMED.wire)
    }
}
