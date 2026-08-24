package kr.artel.orchestration.contentmap.ingest

import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
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
 * **문서 하나가 한 트랜잭션이다.** 중간에 죽으면 아무것도 남지 않아야 한다.
 *
 * 이 테스트가 없으면 트랜잭션 경계를 지워도 나머지 26개가 전부 통과한다 — 행복한 경로만 보기 때문이다.
 * 실제로 걸린 사고는 이렇다: 절반만 앉은 상태로 커밋되면 도장(`ingested_at`)이 안 찍혀 다음 tick 이
 * 같은 문서를 다시 적재하고, 그 사이 `retireVanished` 가 "이번 문서에 없는 기능"으로 **아직 쓰지 못한
 * 살아 있는 기능**을 지운다. 그 삭제는 `capability_observation` 을 CASCADE 로 함께 날린다.
 *
 * 실패를 만드는 방법은 컬럼 폭이다. `capability.input_key` 는 `VARCHAR(64)` 이고, 두 번째 레코드의
 * gesture 키를 그보다 길게 만들면 **첫 레코드가 앉은 뒤에** INSERT 가 거절된다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapIngestTransactionTest {

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
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /**
     * 두 번째 레코드가 컬럼 폭에 걸리면 **첫 번째도 남지 않는다.**
     *
     * 도장도 함께 되돌아가, 다음 tick 이 이 문서를 다시 집는다 — 절반만 앉은 지도가 "적재 완료"로
     * 보이는 것보다 다시 시도하는 쪽이 낫다.
     */
    @Test
    fun `적재 중간에 실패하면 아무것도 남지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = newDocument(map.id!!, documentWithOverlongInputKey())

        assertThatThrownBy { runBlocking { service.ingest(document) } }
            .isNotNull()

        // 이 지도 범위로만 센다. 다른 테스트가 남긴 행과 섞이면 이 단언은 아무것도 지키지 못한다.
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(map.id!!).toList()).isEmpty()
        assertThat(countOfMap("capability_evidence e JOIN capability c ON c.id = e.capability_id", map.id!!)).isZero()
        assertThat(countOfMap("capability_effect f JOIN capability c ON c.id = f.capability_id", map.id!!)).isZero()
        assertThat(countOfMap("scene s2 JOIN capability c ON c.scene_id = s2.id", map.id!!)).isZero()

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestedAt).isNull()
        assertThat(reloaded.ingestedBy).isNull()
        // 원본은 그대로 있어야 다시 집을 수 있다.
        assertThat(reloaded.objectKey).isEqualTo(document.objectKey)
    }

    /**
     * 실패한 문서는 대기 목록에 그대로 남는다.
     *
     * `ingestPending` 은 한 문서가 깨져도 나머지를 계속한다 — 한 게임의 문서가 깨졌다고 다른 게임의
     * 적재가 멈추면, 고치는 사람이 깨진 문서를 찾기 전에 큐가 밀린 것부터 보게 된다.
     */
    @Test
    fun `깨진 문서가 배치를 멈추지 않는다`(): Unit = runBlocking {
        val broken = newContentMap()
        newDocument(broken.id!!, documentWithOverlongInputKey())

        val healthy = newContentMap(capture = Capture.PLAYER.wire)
        val healthyDocument = newDocument(healthy.id!!, minimalDocument())

        val results = service.ingestPending(limit = 10)

        assertThat(results.map { it.documentId }).contains(healthyDocument.id)
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(healthy.id!!).toList()).isNotEmpty()
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(broken.id!!).toList()).isEmpty()
        assertThat(documents.findById(healthyDocument.id!!)!!.ingestedAt).isNotNull()
    }

    /**
     * 실패한 문서에 시도 횟수와 사유가 남는다 (ARTEL-502).
     *
     * 이 장부가 없으면 트리거를 켤 수 없다. 파싱에서 죽는 문서 하나가 매 tick 마다 스토리지에서
     * 1.4 MB 를 다시 읽고, `received_at ASC` 라 언제나 큐의 앞자리를 차지한다.
     *
     * **횟수는 적재 트랜잭션 밖에서 올라야 한다.** 안에서 올리면 적재가 되돌아갈 때 장부도 함께
     * 되돌아가 영영 0이다 — 이 단언이 지키는 것이 그것이다.
     */
    @Test
    fun `실패한 문서에 시도 횟수와 사유가 남는다`(): Unit = runBlocking {
        val broken = newContentMap()
        val document = newDocument(broken.id!!, documentWithOverlongInputKey())

        service.ingestPending(limit = 50)

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestAttempts).isEqualTo(1)
        assertThat(reloaded.lastError).isNotNull()
        // 실패했으므로 도장은 없다. 다음 tick 이 다시 집을 수 있어야 한다.
        assertThat(reloaded.ingestedAt).isNull()
    }

    /**
     * 집어 온 문서에는 **이번 시도가 이미 들어 있다.**
     *
     * `claimPending` 의 `RETURNING` 이 UPDATE 뒤의 값을 돌려주기 때문이다. 적재기의 상한 경고가
     * 이 전제 위에 서 있어서 — 여기서 한 번 더 세면 경고가 한 시도 이르게 뜨고, 그 줄이 "5/5"
     * 라고 적으면서 정작 문서는 한 번 더 집힌다. 조용히 틀리는 종류라 못으로 박아 둔다.
     */
    @Test
    fun `집어 온 문서는 이번 시도가 이미 세어진 채로 온다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = newDocument(map.id!!, minimalDocument())

        val claimed = documents.claimPending(limit = 50, maxAttempts = 5).toList()
            .first { it.id == document.id }

        assertThat(claimed.ingestAttempts).isEqualTo(1)
        assertThat(documents.findById(document.id!!)!!.ingestAttempts).isEqualTo(1)
    }

    /**
     * 시도 상한을 넘긴 문서는 큐에서 빠진다.
     *
     * 빠졌다는 증거는 **횟수가 더 오르지 않는 것**이다. `claimPending` 이 집는 그 자리에서 올리므로,
     * 다시 집혔다면 숫자가 움직였을 것이다.
     */
    @Test
    fun `시도 상한을 넘긴 문서는 더는 집히지 않는다`(): Unit = runBlocking {
        val broken = newContentMap()
        val document = newDocument(broken.id!!, documentWithOverlongInputKey())

        service.ingestPending(limit = 50, maxAttempts = 1)
        assertThat(documents.findById(document.id!!)!!.ingestAttempts).isEqualTo(1)

        service.ingestPending(limit = 50, maxAttempts = 1)

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestAttempts).isEqualTo(1)
        // 행은 남는다. 왜 실패했는지는 여기서만 읽을 수 있다 — 로그는 돌지만 이것은 남는다.
        assertThat(reloaded.lastError).isNotNull()
    }

    /**
     * 상한을 넘겨 큐에서 빠진 문서가 있어도 나머지는 계속 적재된다.
     *
     * 상한의 목적이 큐를 세우는 것이 아니라 **비켜 주는 것**이라는 단언이다.
     */
    @Test
    fun `상한을 넘긴 문서가 나머지를 막지 않는다`(): Unit = runBlocking {
        val broken = newContentMap()
        val brokenDocument = newDocument(broken.id!!, documentWithOverlongInputKey())

        // 상한까지 태운다.
        service.ingestPending(limit = 50, maxAttempts = 1)
        assertThat(documents.findById(brokenDocument.id!!)!!.ingestAttempts).isEqualTo(1)

        // 깨진 문서보다 나중에 도착했지만, 앞자리가 비켜 있으므로 이번 tick 에 적재된다.
        val healthy = newContentMap(capture = Capture.PLAYER.wire)
        val healthyDocument = newDocument(healthy.id!!, minimalDocument())

        val results = service.ingestPending(limit = 50, maxAttempts = 1)

        assertThat(results.map { it.documentId }).contains(healthyDocument.id)
        assertThat(documents.findById(healthyDocument.id!!)!!.ingestedAt).isNotNull()
        assertThat(documents.findById(brokenDocument.id!!)!!.ingestAttempts).isEqualTo(1)
    }

    /**
     * 적재된 문서는 큐에서 빠진다.
     *
     * 도장이 큐의 조건(`ingested_at IS NULL`)이기도 하다는 것을 고정한다. 빠지지 않으면 같은
     * 문서가 매 tick 다시 적재되고, `retireVanished` 가 그때마다 돈다.
     */
    @Test
    fun `적재된 문서는 다시 집히지 않는다`(): Unit = runBlocking {
        val map = newContentMap()
        val document = newDocument(map.id!!, minimalDocument())

        service.ingestPending(limit = 50)
        val ingested = documents.findById(document.id!!)!!
        assertThat(ingested.ingestedAt).isNotNull()
        val attemptsAfterIngest = ingested.ingestAttempts

        service.ingestPending(limit = 50)

        assertThat(documents.findById(document.id!!)!!.ingestAttempts).isEqualTo(attemptsAfterIngest)
    }

    // ---------- 픽스처 ----------

    private suspend fun countOfMap(from: String, contentMapId: Long): Long =
        db.sql("SELECT count(*) FROM $from WHERE c.content_map_id = :id")
            .bind("id", contentMapId)
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()

    private suspend fun newContentMap(capture: String = Capture.EDITOR.wire): ContentMapEntity {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "ingest-tx-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )
        return contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!,
                schemaVersion = 6,
                capture = capture,
                evidenceDigest = "d4b31e4da9504b7d",
            )
        )
    }

    private suspend fun newDocument(contentMapId: Long, json: String): ContentMapDocumentEntity {
        val objectKey = "evidence/${System.nanoTime()}.json"
        fakeStorage.put(objectKey, json.toByteArray())
        return documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = "%064x".format(System.nanoTime()),
                byteSize = json.length.toLong(),
                receivedAt = Instant.now(),
            )
        )
    }

    /** 레코드 하나짜리 성한 문서. */
    private fun minimalDocument(): String = document(secondRecordKey = null)

    /**
     * 둘째 레코드의 키가 `VARCHAR(64)` 를 넘는 문서.
     *
     * 첫째 레코드는 성해서 먼저 앉고, 둘째에서 거절된다 — 그것이 이 테스트가 필요로 하는 순서다.
     */
    private fun documentWithOverlongInputKey(): String = document(secondRecordKey = "K".repeat(120))

    private fun document(secondRecordKey: String?): String {
        val first = """
            {
              "owner": "Demo.Title",
              "entry": "System.Void Demo.Title::Start()",
              "entryId": "Assembly-CSharp|Demo.Title|Start|System.Void()",
              "source": "System.Void Demo.Title::Start()",
              "methodId": "Assembly-CSharp|Demo.Title|Start|System.Void()",
              "recordKind": "candidate", "triggerKind": "lifecycle", "confidence": "derived",
              "calledBy": [], "callPath": ["System.Void Demo.Title::Start()"],
              "condition": {"kind": "always"},
              "inputs": [],
              "effects": [
                {"kind": "scene", "category": "observable", "target": "MapScene", "detail": null,
                 "source": "System.Void Demo.Title::Start()", "offset": 3}
              ],
              "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
            }
        """
        val second = secondRecordKey?.let { key ->
            """,
            {
              "owner": "Demo.Title",
              "entry": "System.Void Demo.Title::Update()",
              "entryId": "Assembly-CSharp|Demo.Title|Update|System.Void()",
              "source": "System.Void Demo.Title::Update()",
              "methodId": "Assembly-CSharp|Demo.Title|Update|System.Void()",
              "recordKind": "candidate", "triggerKind": "lifecycle", "confidence": "derived",
              "calledBy": [], "callPath": ["System.Void Demo.Title::Update()"],
              "condition": {"kind": "gesture", "input": "key:$key (down)", "offset": 9},
              "inputs": [{"kind": "key", "control": "$key", "phase": "down", "absent": false, "offset": 9}],
              "effects": [], "calls": [], "handles": [], "alsoReachedBy": [], "gaps": []
            }
            """
        }.orEmpty()

        return """
            {
              "schema": 6,
              "capture": "editor",
              "capabilities": ["build-info-v1"],
              "build": {"unity": "2022.3.62f3", "platform": "OSXEditor", "backend": "mono",
                        "development": true, "sdk": "0.1.0", "evidence": "d4b31e4da9504b7d"},
              "scenes": ["TitleScene"],
              "types": { "Demo.Title": [$first$second] },
              "unplaced": {},
              "objects": [
                {
                  "path": "TitleController", "selector": "TitleController[1]", "scene": "TitleScene",
                  "active": true, "visuals": [],
                  "components": [{"type": "Demo.Title", "calls": [], "refs": []}]
                }
              ],
              "persistentObjects": [],
              "gaps": []
            }
        """.trimIndent()
    }
}
