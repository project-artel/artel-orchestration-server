package kr.artel.orchestration.contentmap.ingest

import com.fasterxml.jackson.databind.ObjectMapper
import com.fasterxml.jackson.databind.node.ArrayNode
import com.fasterxml.jackson.databind.node.ObjectNode
import com.fasterxml.jackson.module.kotlin.readValue
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneObjectComponent
import kr.artel.orchestration.contentmap.evidence.EvidenceParser
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneObjectRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.scenecontext.service.SceneContextService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import org.springframework.transaction.reactive.TransactionalOperator
import org.springframework.transaction.reactive.executeAndAwait
import java.security.MessageDigest
import java.time.Instant

/**
 * `scene_object` 를 적재가 채우고, 재적재가 갈아 끼우고, 백필이 V114 이전 지도에 채우는가(ARTEL-1059).
 *
 * 문서는 손으로 짠 `contentmap/scene-objects.json` 이다. 실측 문서(1.1MB)는 오브젝트가 40 개를 넘어
 * 어느 행이 왜 남았는지 단언에서 읽을 수 없다. 이 문서는 규칙마다 한 오브젝트씩 둔다.
 *
 * | 오브젝트 | 보는 규칙 |
 * |---|---|
 * | `Canvas[0]/CombineButton[2]` | 코드가 건드리지 않는 타입(`Button` · `CombineZone`)은 `fields` 가 빈다 |
 * | `Word[13]` | `active = false` 가 그대로 남고, 조건과 효과의 필드가 짧은 타입 이름 아래 모인다 |
 * | `TitleMenu` | selector 가 없으면 path 를 담는다 |
 * | `Ghost[0]` | `scene` 행이 없는 오브젝트는 버린다 |
 * | `SaveLoadController[0]` (`persistentObjects`) | `DontDestroyOnLoad` 도 `scene` 행이 없어 버린다 |
 */
@ActiveProfiles("test")
@SpringBootTest
class SceneObjectIngestTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var backfill: SceneObjectBackfillWorker
    @Autowired private lateinit var writer: SceneObjectWriter
    @Autowired private lateinit var sceneContext: SceneContextService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var sceneObjects: SceneObjectRepository
    @Autowired private lateinit var transactionalOperator: TransactionalOperator
    @Autowired private lateinit var objectMapper: ObjectMapper

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    private val fixture: String =
        requireNotNull(javaClass.getResource("/contentmap/scene-objects.json")).readText()

    // ------------------------------------------------------------------ 적재

    @Test
    fun `적재가 scene 에 놓인 오브젝트와 코드가 건드리는 필드를 남긴다`(): Unit = runBlocking {
        val map = newContentMap()

        ingestNew(map.id!!, fixture)

        assertThat(storedObjects(map.id)).containsExactly(
            StoredObject(
                "BattleScene", "Canvas[0]/CombineButton[2]", true,
                listOf(SceneObjectComponent("Button"), SceneObjectComponent("CombineZone")),
            ),
            // `Player.hp`(조건)와 `Player.HpText.text`(효과)가 `Player` 아래 모인다. 이름 오름차순이라
            // 대문자가 먼저다. `count`(지역변수)와 `Enemy.Find().hp`(호출 결과)는 필드가 아니다.
            StoredObject(
                "BattleScene", "Word[13]", false,
                listOf(
                    SceneObjectComponent("SelectableObject", listOf("selectable")),
                    SceneObjectComponent("Player", listOf("HpText", "hp")),
                ),
            ),
            StoredObject("TitleScene", "TitleMenu", true, listOf(SceneObjectComponent("TitleMenu"))),
        )
    }

    /**
     * 재적재는 지도의 오브젝트를 **지우고 다시 넣는다.** 남기면 `scene` 에서 사라진 오브젝트를 planner 가
     * 계속 본다.
     */
    @Test
    fun `재적재가 오브젝트를 갈아 끼운다`(): Unit = runBlocking {
        val map = newContentMap()
        ingestNew(map.id!!, fixture)

        ingestNew(map.id, withoutObject(fixture, selector = "Word[13]"))

        assertThat(storedObjects(map.id).map { it.selector })
            .containsExactly("Canvas[0]/CombineButton[2]", "TitleMenu")
    }

    /**
     * 적재한 것이 `scene-context` 로 나간다. 오브젝트는 그 `scene` 아래, 코드가 쓰는 필드는 그 `capability`
     * 의 `writes` 로. `active-state` 효과는 `write` 가 아니라 들어가지 않는다.
     */
    @Test
    fun `적재한 오브젝트와 writes 가 scene-context 에 실린다`(): Unit = runBlocking {
        val map = newContentMap()
        ingestNew(map.id!!, fixture)
        val build = gameBuilds.findById(map.gameBuildId)!!

        val response = sceneContext.read(build.projectId, build.id!!, qaTryId = null)!!

        val battle = response.scenes.single { it.sceneName == "BattleScene" }
        assertThat(battle.objectsTotal).isEqualTo(2)
        assertThat(battle.objects.map { it.selector }).containsExactly("Canvas[0]/CombineButton[2]", "Word[13]")
        val combine = battle.capabilities.single { it.controlPath == "Canvas/CombineButton" }
        assertThat(combine.writes)
            .containsExactly("SelectableObject.selectable", "Player.HpText.text", "(not a simple receiver)")
    }

    // ------------------------------------------------------------------ 백필

    /**
     * V114 이전에 적재된 지도를 흉내 낸다 — 적재한 뒤 오브젝트만 지운다. 백필이 같은 문서에서 같은 행을
     * 되살리고, 문서가 스토리지에 없는 지도는 **건너뛰고** 다음 지도로 간다.
     *
     * 깨진 지도를 먼저 만든다. 백필은 지도 id 순서로 돌므로, 깨진 지도가 앞에 있어도 뒤 지도가 채워져야
     * "건너뛴다" 가 성립한다.
     *
     * 백필의 대상 조회는 지도 전체를 본다. 공유 DB 에 다른 테스트가 남긴 지도가 있으면 그것도 이 가짜
     * 스토리지에 없어 실패로 세어지므로, 결말의 수는 하한으로만 본다.
     */
    @Test
    fun `백필이 오브젝트 없는 지도를 문서에서 채우고 깨진 지도는 건너뛴다`(): Unit = runBlocking {
        val broken = newContentMap()
        val missingDocument = documents.save(
            ContentMapDocumentEntity(
                contentMapId = broken.id!!,
                objectKey = "content-map-evidence/${broken.gameBuildId}/gone.json",
                contentHash = "0".repeat(64),
                byteSize = 1,
                receivedAt = Instant.now(),
            )
        )
        documents.stampIngested(missingDocument.id!!, ContentMapIngestService.INGESTER_VERSION, Instant.now())

        val map = newContentMap()
        ingestNew(map.id!!, fixture)
        val ingested = storedObjects(map.id)
        sceneObjects.deleteByContentMapId(map.id)

        val result = backfill.runOnce()

        assertThat(storedObjects(map.id)).isEqualTo(ingested)
        assertThat(storedObjects(broken.id)).isEmpty()
        assertThat(result.filled).isGreaterThanOrEqualTo(1)
        assertThat(result.failed).isGreaterThanOrEqualTo(1)

        // 채운 지도는 다시 고르지 않는다 — 두 번 돌려도 두 벌이 되지 않는다.
        backfill.runOnce()
        assertThat(storedObjects(map.id)).isEqualTo(ingested)
    }

    /**
     * 백필이 문서를 읽는 사이 적재가 먼저 채웠으면 백필은 넣지 않는다. 적재가 넣은 것이 더 새 문서다.
     */
    @Test
    fun `이미 오브젝트가 있는 지도에는 백필이 넣지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        ingestNew(map.id!!, fixture)
        val before = storedObjects(map.id)
        val sceneIds = scenes.findByContentMapIdOrderByNameAsc(map.id).toList().associate { it.name to it.id!! }

        val inserted = transactionalOperator.executeAndAwait {
            writer.fillIfEmpty(map.id, EvidenceParser(objectMapper).parse(fixture), sceneIds)
        }

        assertThat(inserted).isNull()
        assertThat(storedObjects(map.id)).isEqualTo(before)
    }

    // ------------------------------------------------------------------ 세우기

    private suspend fun newContentMap(): ContentMapEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "scene-object-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )
        return contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 7,
                capture = Capture.PLAYER.wire,
                evidencePromises = Json.of("""["build-info-v1","selector-v1","visual-roles-v1"]"""),
                evidenceDigest = "5ce0b1ec7a11d00d",
            )
        )
    }

    /** 등록 경로가 남기는 상태(스토리지의 바이트 + DB 의 포인터)를 만들고 곧바로 적재한다. */
    private suspend fun ingestNew(contentMapId: Long, json: String): IngestResult {
        val bytes = json.toByteArray(Charsets.UTF_8)
        val objectKey = "content-map-evidence/$contentMapId/${System.nanoTime()}.json"
        fakeStorage.put(objectKey, bytes)
        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) },
                byteSize = bytes.size.toLong(),
                receivedAt = Instant.now(),
            )
        )
        return ingest.ingest(document)
    }

    private fun withoutObject(json: String, selector: String): String {
        val root = objectMapper.readTree(json) as ObjectNode
        val objects = root.path("objects") as ArrayNode
        val index = objects.indexOfFirst { it.path("selector").asText() == selector }
        objects.remove(index)
        return objectMapper.writeValueAsString(root)
    }

    private suspend fun storedObjects(contentMapId: Long): List<StoredObject> {
        val sceneNames = scenes.findByContentMapIdOrderByNameAsc(contentMapId).toList().associate { it.id!! to it.name }
        return sceneObjects.findByContentMapIdOrderByIdAsc(contentMapId).toList().map { row ->
            StoredObject(
                sceneName = sceneNames.getValue(row.sceneId),
                selector = row.selector,
                active = row.active,
                components = objectMapper.readValue(row.components.asString()),
            )
        }
    }

    /** id · 시각처럼 다시 넣으면 바뀌는 칸을 뺀 `scene_object` 한 행. */
    private data class StoredObject(
        val sceneName: String,
        val selector: String,
        val active: Boolean,
        val components: List<SceneObjectComponent>,
    )
}
