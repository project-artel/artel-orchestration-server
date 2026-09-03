package kr.artel.orchestration.contentmap

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.common.error.NotFoundException
import kr.artel.orchestration.contentmap.dto.ContentMapStreamEvent
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.scan.ContentMapScanService
import kr.artel.orchestration.contentmap.scan.ScanState
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.entity.GameInstanceEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.game.repository.GameInstanceRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.sdk.service.SessionManager
import org.assertj.core.api.Assertions.assertThat
import org.assertj.core.api.Assertions.assertThatThrownBy
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.http.codec.ServerSentEvent
import org.springframework.r2dbc.core.DatabaseClient
import org.springframework.test.context.ActiveProfiles
import org.springframework.web.reactive.socket.WebSocketSession
import org.mockito.Mockito
import java.security.MessageDigest
import java.time.Instant

/**
 * `GET .../content-map/events` 가 흘리는 SSE(ARTEL-763). [ContentMapViewService.events] 를 직접
 * 불러 검증한다 — 이 컨트롤러의 다른 테스트([ProjectContentMapAccessTest], [ContentMapScanTest])와
 * 같은 자리다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ContentMapEventStreamTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var view: ContentMapViewService
    @Autowired private lateinit var scan: ContentMapScanService
    @Autowired private lateinit var ingestService: ContentMapIngestService
    @Autowired private lateinit var sessionManager: SessionManager
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var db: DatabaseClient

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /**
     * 경로의 projectId 가 그 빌드의 것과 다르면 스트림을 **열기 전에** 404 다.
     *
     * `ProjectContentMapAccessTest`와 같은 이유로 사용자를 두 프로젝트 모두의 멤버로 둔다 — 권한이
     * 아니라 경로가 어긋난 것을 잡는지 보려는 것이다. `ProjectAccessService.requireMember` 하나만으로는
     * 이 경우를 잡지 못한다(계약 4절 "404 접근할 수 없는 project 또는 game build" 참고).
     */
    @Test
    fun `경로의 프로젝트가 다르면 events 도 열리지 않는다`(): Unit = runBlocking {
        val userId = newUser()
        val mine = newProject(userId)
        val other = newProject(userId)
        val build = newBuild(mine)

        assertThatThrownBy { runBlocking { view.events(userId, other, build) } }
            .isInstanceOf(NotFoundException::class.java)

        // 정상 경로는 막히지 않는다.
        assertThat(view.events(userId, mine, build)).isNotNull()
    }

    /**
     * 구독 직후 `snapshot` 프레임이 **정확히 한 번** 온다. 스캔도 문서도 없는 빌드라 `scan` 은 null,
     * 진행 세 수는 전부 0, `documents` 는 빈 목록이다.
     */
    @Test
    fun `구독 직후 snapshot 프레임이 한 번 온다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)

        val flow = view.events(userId, projectId, buildId)
        val first = flow.take(1).map { it.data()!! }.toList().single()

        assertThat(first.type).isEqualTo("snapshot")
        assertThat(first.scan).isNull()
        assertThat(first.ingest!!.receivedDocuments).isZero()
        assertThat(first.ingest!!.ingestedDocuments).isZero()
        assertThat(first.ingest!!.failedDocuments).isZero()
        assertThat(first.documents).isEmpty()
    }

    /**
     * 스캔이 `REQUESTED` 로 바뀌면 **이미 구독 중인** 스트림에 `scan` 이벤트가 온다.
     *
     * 구독을 먼저 걸고 그 뒤에 버튼을 누르는 순서가 요점이다 — 여러 사람이 같은 빌드를 보고 있을 때,
     * 이 버튼을 누른 적 없는 다른 사람의 화면도 REQUESTED 로 넘어간 사실을 알아야 한다.
     */
    @Test
    fun `스캔이 REQUESTED 로 바뀌면 구독 중인 스트림에 scan 이벤트가 온다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId, name = "Editor - MacBook")
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        val flow = view.events(userId, projectId, buildId)
        val collected = collectAsync(flow, 2)

        scan.startScan(userId, projectId, buildId)

        val events = collected.await()
        assertThat(events[0].type).isEqualTo("snapshot")
        assertThat(events[1].type).isEqualTo("scan")
        assertThat(events[1].scan!!.state).isEqualTo(ScanState.REQUESTED)
        assertThat(events[1].scan!!.gameInstanceId).isEqualTo(instanceId)

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    /**
     * 문서 하나가 앉으면 `ingest`(진행 두 수 + 실패 수)와 `document`(그 문서 한 행)가 **같은 사건에서
     * 함께** 온다.
     */
    @Test
    fun `문서가 앉으면 ingest 와 document 이벤트가 함께 온다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val map = newContentMap(buildId)
        val document = registerDocument(map.id!!, minimalEvidenceDocument())

        val flow = view.events(userId, projectId, buildId)
        val collected = collectAsync(flow, 3)

        ingestService.ingestBuild(buildId)

        val events = collected.await()
        assertThat(events[0].type).isEqualTo("snapshot")

        val ingestEvent = events.single { it.type == "ingest" }
        assertThat(ingestEvent.ingest!!.receivedDocuments).isEqualTo(1)
        assertThat(ingestEvent.ingest!!.ingestedDocuments).isEqualTo(1)
        assertThat(ingestEvent.ingest!!.failedDocuments).isZero()

        val documentEvent = events.single { it.type == "document" }
        assertThat(documentEvent.document!!.documentId).isEqualTo(document.id)
        assertThat(documentEvent.document!!.ingestedAt).isNotNull()
        assertThat(documentEvent.document!!.ingestFailedAt).isNull()
    }

    /**
     * 같은 빌드를 보는 두 구독자가 **둘 다** 같은 이벤트를 받는다. key 가 `gameBuildId` 라 여러
     * 사람이 볼 수 있다는 계약의 핵심이다 — 구독자 하나가 먼저 나가도(map 항목을 지우지 않으므로)
     * 나머지가 죽지 않는다는 것은 [collectAsync] 가 각자 독립된 Flow.collect 인 것으로 이미 보장된다.
     */
    @Test
    fun `같은 빌드를 보는 두 구독자가 둘 다 scan 이벤트를 받는다`(): Unit = runBlocking {
        val userId = newUser()
        val projectId = newProject(userId)
        val buildId = newBuild(projectId)
        val instanceId = newInstance(projectId, buildId)
        sessionManager.register(instanceId.toString(), Mockito.mock(WebSocketSession::class.java))

        val first = collectAsync(view.events(userId, projectId, buildId), 2)
        val second = collectAsync(view.events(userId, projectId, buildId), 2)

        scan.startScan(userId, projectId, buildId)

        assertThat(first.await()[1].type).isEqualTo("scan")
        assertThat(second.await()[1].type).isEqualTo("scan")

        sessionManager.removeSession(instanceId.toString(), sessionManager.getSession(instanceId.toString())!!)
    }

    // ---------- 구독 헬퍼 ----------

    /**
     * `flow` 를 즉시 구독시킨 뒤 `count` 개를 모으는 [Deferred] 를 돌려준다.
     *
     * `CoroutineStart.UNDISPATCHED` 가 요점이다 — 이 호출이 반환되는 시점에 이미 SharedFlow 의
     * 구독자 슬롯이 등록돼 있어야, 뒤이어 부르는 트리거(`scan.startScan` 등)가 그 이벤트를 놓치지
     * 않는다. `emitAll` 이 아직 안 온 값을 기다리며 진짜로 suspend 하는 지점이 곧 그 등록이 끝난
     * 지점이다.
     */
    private fun CoroutineScope.collectAsync(
        flow: Flow<ServerSentEvent<ContentMapStreamEvent>>,
        count: Int,
    ): Deferred<List<ContentMapStreamEvent>> =
        async(start = CoroutineStart.UNDISPATCHED) {
            flow.take(count).map { it.data()!! }.toList()
        }

    // ---------- 픽스처 ----------

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "events-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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

    private suspend fun newInstance(projectId: Long, gameBuildId: Long, name: String = "instance"): Long {
        val now = Instant.now()
        return gameInstances.save(
            GameInstanceEntity(
                projectId = projectId,
                name = name,
                platform = "UNITY",
                lastGameBuildId = gameBuildId,
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        ).id!!
    }

    private suspend fun newContentMap(gameBuildId: Long): ContentMapEntity =
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = "editor",
                unity = "2022.3.62f3",
                backend = "mono",
                development = true,
                sdkVersion = "0.1.0",
            )
        )

    /** 등록 경로가 남기는 것과 같은 상태 — 스토리지에 바이트, DB 에는 포인터만. */
    private suspend fun registerDocument(contentMapId: Long, json: String) =
        json.toByteArray(Charsets.UTF_8).let { bytes ->
            val objectKey = "evidence/${System.nanoTime()}.json"
            fakeStorage.put(objectKey, bytes)
            documents.save(
                kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity(
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
     * 후보 하나(`Demo.TitleController::Update`, lifecycle)를 내는 가장 작은 schema 6 문서.
     * `ContentMapReingestTest.evidenceDocument()` 의 `quitPopup` 레코드와 같은 모양이다.
     */
    private fun minimalEvidenceDocument(): String = """
        {
          "schema": 6,
          "capture": "editor",
          "capabilities": ["build-info-v1", "selector-v1", "visual-roles-v1"],
          "build": {
            "unity": "2022.3.62f3", "platform": "OSXEditor", "backend": "mono",
            "development": true, "sdk": "0.1.0", "evidence": "eventstream00001"
          },
          "scenes": ["TitleScene"],
          "types": {
            "Demo.TitleController": [
              {
                "owner": "Demo.TitleController",
                "entry": "System.Void Demo.TitleController::Update()",
                "entryId": "Assembly-CSharp|Demo.TitleController|Update|System.Void()",
                "source": "System.Void Demo.TitleController::Update()",
                "methodId": "Assembly-CSharp|Demo.TitleController|Update|System.Void()",
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
            ]
          },
          "unplaced": {},
          "objects": [
            {
              "path": "TitleController", "selector": "TitleController[1]", "scene": "TitleScene", "active": true,
              "visuals": [], "components": [{"type": "Demo.TitleController", "calls": [], "refs": []}]
            }
          ],
          "persistentObjects": [],
          "gaps": []
        }
    """
}
