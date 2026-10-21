package kr.artel.orchestration.contentmap.scan

import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.mapNotNull
import kotlinx.coroutines.flow.take
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import kr.artel.orchestration.contentmap.dto.LastScanResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
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
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.entity.TestCaseVersionEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testcase.repository.TestCaseVersionRepository
import org.assertj.core.api.Assertions.assertThat
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
 * 스캔 결과가 돌아왔을 때 **적재까지 이어지고, 깨지면 그 사유가 화면까지 간다.**
 *
 * 이 고리가 없으면 (B) 비동기 설계는 "요청은 갔다"까지만 아는 모양이 된다 — 화면이 조회를 되풀이해도
 * 영원히 아무것도 바뀌지 않고, 왜 안 바뀌는지도 말해 주지 않는다.
 *
 * 함께 짚는 것이 하나 더 있다. 이 라우터는 `ACTION_RESULT` 를 QA 브리지에서 갈라 가져오므로,
 * **QA 가 쓰는 모양의 프레임을 건드리지 않는다**는 것이 계약이다. 마지막 두 테스트가 그것이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScanResultRoutingTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var router: ScanResultRouter
    @Autowired private lateinit var statuses: ScanStatusRegistry
    @Autowired private lateinit var progress: ScanProgressRecorder
    @Autowired private lateinit var streamManager: ContentMapEventStreamManager
    @Autowired private lateinit var view: ContentMapViewService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var gameInstances: GameInstanceRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var capabilities: CapabilityRepository
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var versions: TestCaseVersionRepository

    private val fakeStorage: FakeDocumentStorage get() = storage as FakeDocumentStorage

    /**
     * `success:true` 가 **그 빌드의** 대기 문서를 앉힌다.
     *
     * 전역 큐를 그대로 쓰면 남의 프로젝트 문서가 이 사람의 스캔 결과에 섞이므로, 다른 빌드의 대기
     * 문서를 하나 더 두고 그것이 **안 앉는 것**까지 함께 본다.
     */
    @Test
    fun `스캔 성공이 그 빌드의 대기 문서를 앉힌다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, healthyDocument())

        val other = newWorld()
        val untouched = newDocument(other.contentMapId, healthyDocument())

        statuses.put(requestedStatus(world))

        assertThat(router.handle(world.instanceId, actionResult(success = true))).isTrue()

        assertThat(documents.findById(document.id!!)!!.ingestedAt).isNotNull()
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(world.contentMapId).toList()).isNotEmpty()

        // 남의 빌드 문서는 이 결과에 섞이지 않는다.
        assertThat(documents.findById(untouched.id!!)!!.ingestedAt).isNull()

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.state).isEqualTo(ScanState.SUCCEEDED)
        assertThat(status.ingestedDocuments).isEqualTo(1)
        assertThat(status.finishedAt).isNotNull()
    }

    /**
     * 끊은 스캔의 늦은 성공 결과는 앉히지 않는다(ARTEL-1183).
     *
     * 취소는 게임에 멈추라고 보내지만, 그 사이 이미 올라간 문서의 성공 결과가 뒤늦게 도착할 수 있다. 앉히면 사람이
     * 끊은 스캔이 지도를 바꾸고, 그 지도는 반쯤 돈 순회의 것이라 못 돈 씬이 사라진 모습이 된다.
     */
    @Test
    fun `끊은 스캔의 늦은 결과는 앉히지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, healthyDocument())

        statuses.put(
            requestedStatus(world).copy(state = ScanState.FAILED, cancelled = true, error = "사용자가 스캔을 취소했습니다."),
        )

        // 프레임은 우리 것이므로 처리했다고 답하되, 적재는 하지 않는다.
        assertThat(router.handle(world.instanceId, actionResult(success = true))).isTrue()

        assertThat(documents.findById(document.id!!)!!.ingestedAt).isNull()

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.state).isEqualTo(ScanState.FAILED)
        assertThat(status.cancelled).isTrue()
    }

    /**
     * 상태를 모르는 결과는 지금까지 하던 대로 앉힌다.
     *
     * 상태는 프로세스 메모리에 살아서 서버가 다시 뜨면 비어 있다. 그때 결과가 오는 것은 끊긴 것이 아니라 재시작을
     * 건너온 것이라, 모른다는 이유로 막으면 멀쩡한 스캔이 버려진다.
     */
    @Test
    fun `상태를 모르는 결과는 그대로 앉힌다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, healthyDocument())

        assertThat(router.handle(world.instanceId, actionResult(success = true))).isTrue()

        assertThat(documents.findById(document.id!!)!!.ingestedAt).isNotNull()
    }

    /** 스캔하고 TC 생성(ARTEL-1040): 문서가 다 앉은 뒤 그 지도로 TC 를 만들고, 결과를 스캔 상태에 싣는다. */
    @Test
    fun `TC 생성을 함께 요청한 스캔은 적재 뒤 TC 를 만든다`(): Unit = runBlocking {
        val world = newWorld()
        newDocument(world.contentMapId, healthyDocument())
        statuses.put(requestedStatus(world).copy(testCases = ScanTestCases(carryOver = true)))

        assertThat(router.handle(world.instanceId, actionResult(success = true))).isTrue()

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.state).isEqualTo(ScanState.SUCCEEDED)
        val testCases = status.testCases!!
        assertThat(testCases.state).isEqualTo(ScanTestCaseState.GENERATED)
        assertThat(testCases.created).isEqualTo(versions.findByGameBuildId(world.gameBuildId).toList().size)
    }

    /** 그사이 같은 build 의 TC 를 누가 만들었으면 건너뛰고, 스캔은 그대로 성공이다. */
    @Test
    fun `이미 TC 를 만든 build 면 스캔 뒤 생성을 건너뛴다`(): Unit = runBlocking {
        val world = newWorld()
        newDocument(world.contentMapId, healthyDocument())
        val testCase = testCases.save(TestCaseEntity(projectId = world.projectId, scene = "S", step = "지도", expectedValue = "x"))
        versions.save(TestCaseVersionEntity.snapshotOf(testCase, world.gameBuildId).copy(contentMapId = world.contentMapId))
        statuses.put(requestedStatus(world).copy(testCases = ScanTestCases(carryOver = true)))

        router.handle(world.instanceId, actionResult(success = true))

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.state).isEqualTo(ScanState.SUCCEEDED)
        assertThat(status.testCases!!.state).isEqualTo(ScanTestCaseState.SKIPPED)
        assertThat(versions.findByGameBuildId(world.gameBuildId).toList()).hasSize(1)
    }

    /**
     * **로컬 스택에서 실제로 받은 프레임 문자열 그대로**가 적재까지 간다.
     *
     * 이 테스트가 이 파일에서 가장 중요한 이유: 나머지는 우리가 만든 픽스처를 우리가 읽는 것이고,
     * 이것만이 **SDK 가 진짜로 보낸 바이트**를 읽는다. 2026-08-26 10:12:03 에 instanceId 9 가 보낸
     * 프레임을 서버 로그에서 그대로 옮겼다.
     *
     * 앞서 이 파일의 픽스처가 `action` 을 최상위에 두는 모양이었고, 그 모양은 계약 문서에만 있었다.
     * 테스트 13건이 통과하는 동안 프로덕션에서는 프레임이 조용히 QA 브리지로 흘러 **적재가 한 번도
     * 돌지 않았다** — 문서는 등록되는데 씬·기능 행이 0인 채였고 오류 로그도 없었다.
     */
    @Test
    fun `실측 프레임 그대로가 적재까지 간다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, healthyDocument())
        statuses.put(requestedStatus(world))

        val captured = """{"type":"ACTION_RESULT","id":12,"requestId":1,"results":[""" +
            """{"id":1,"success":true,"error":"","action":"scan_evidence","returnValue":""" +
            """{"objectKey":"content-map-evidence/1/34b8be56-52b4-4aa4-93ad-08152233ba90.json",""" +
            """"evidenceDigest":"d4b31e4da9504b7d","byteSize":695510,"schemaVersion":7,""" +
            """"sceneCount":7,"alreadyRegistered":false}}]}"""

        assertThat(router.handle(world.instanceId, captured)).isTrue()

        assertThat(documents.findById(document.id!!)!!.ingestedAt).isNotNull()
        assertThat(capabilities.findEvidenceCapabilitiesOfMap(world.contentMapId).toList()).isNotEmpty()
        assertThat(statuses.find(world.gameBuildId)!!.state).isEqualTo(ScanState.SUCCEEDED)
    }

    /** 액션 이름이 최상위에 있는 모양도 계속 받는다. 프레임이 바뀌어도 조용히 멎지 않게 하는 방어다. */
    @Test
    fun `평평한 모양도 받는다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, healthyDocument())
        statuses.put(requestedStatus(world))

        assertThat(router.handle(world.instanceId, flatActionResult(success = true))).isTrue()

        assertThat(documents.findById(document.id!!)!!.ingestedAt).isNotNull()
    }

    /**
     * 적재가 깨지면 **`ingest_failed_at` · `ingest_error` 에 남고 조회 API 가 그것을 실어 낸다.**
     *
     * 이 두 칸은 V48 이 만들었지만 적는 코드가 없었다. 없으면 실패한 문서는 도장이 안 찍힌 채로만
     * 남아 **아무 일도 없었던 것과 똑같은 모양**이 되고, 사람이 화면에서 "왜 안 됐나"를 물을 자리가
     * 없다.
     *
     * 기록이 **적재 트랜잭션 밖**에서 쓰인다는 것이 요점이다. 안에서 쓰면 롤백에 함께 쓸려 나가
     * 이 단언이 전부 null 을 본다.
     */
    @Test
    fun `적재가 깨지면 사유가 문서에 남고 화면까지 간다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, documentWithOverlongInputKey())
        statuses.put(requestedStatus(world))

        assertThat(router.handle(world.instanceId, actionResult(success = true))).isTrue()

        val reloaded = documents.findById(document.id!!)!!
        assertThat(reloaded.ingestFailedAt).isNotNull()
        assertThat(reloaded.ingestError).isNotBlank()
        // 실패해도 대기 상태 그대로다 — 고친 뒤 다시 집을 수 있어야 한다.
        assertThat(reloaded.ingestedAt).isNull()

        // 화면이 읽는 창구가 그 사유를 싣는다.
        val response = view.read(world.userId, world.projectId, world.gameBuildId)!!
        val pending = response.pendingDocuments.single { it.documentId == document.id }
        assertThat(pending.ingestFailedAt).isNotNull()
        assertThat(pending.ingestError).isEqualTo(reloaded.ingestError)

        assertThat(statuses.find(world.gameBuildId)!!.state).isEqualTo(ScanState.FAILED)
    }

    /**
     * `ingest_error` 에 **잡은 예외의 원문이 실리지 않는다.**
     *
     * 이 칸은 조회 API 가 브라우저에 그대로 내보내는 값이고, `ContentMapViewDtos` 가 "내부 예외
     * 원문은 로그에만 남는다"로 이미 못 박아 뒀다. 컬럼 폭에 걸린 실패는 R2DBC 예외가 **SQL 문과
     * 테이블·컬럼 타입**을 그대로 들고 오므로, 원문을 넣으면 그것이 화면까지 간다.
     */
    @Test
    fun `실패 사유에 내부 예외 원문이 새지 않는다`(): Unit = runBlocking {
        val world = newWorld()
        val document = newDocument(world.contentMapId, documentWithOverlongInputKey())
        statuses.put(requestedStatus(world))

        router.handle(world.instanceId, actionResult(success = true))

        val reason = documents.findById(document.id!!)!!.ingestError!!
        assertThat(reason).doesNotContainIgnoringCase("insert")
        assertThat(reason).doesNotContainIgnoringCase("varchar")
        assertThat(reason).doesNotContainIgnoringCase("capability")
        assertThat(reason).doesNotContain("input_key")
        // 컬럼 폭(512)을 넘지 않아야 이 UPDATE 자체가 깨지지 않는다.
        assertThat(reason.length).isLessThanOrEqualTo(512)
    }

    /**
     * 게임이 **스캔에 실패했다고 답하면** 그 사실이 남는다.
     *
     * 이때는 올라온 문서가 없어 `content_map_document` 행이 아예 안 생긴다 — 문서 칸에 적을 수 없는
     * 유일한 경우이고, 그래서 마지막 스캔 상태가 그 자리를 맡는다. 여기에 안 남기면 화면은 눌렀는데
     * 아무 일도 안 일어난 것과 게임이 거절한 것을 구분할 수 없다.
     */
    @Test
    fun `게임이 스캔 실패로 답하면 그 사유가 남는다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world))

        val payload = """{"type":"ACTION_RESULT","action":"scan_evidence","success":false,"error":"릴리스 빌드에서는 스캔할 수 없습니다."}"""
        assertThat(router.handle(world.instanceId, payload)).isTrue()

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.state).isEqualTo(ScanState.FAILED)
        assertThat(status.error).isEqualTo("릴리스 빌드에서는 스캔할 수 없습니다.")

        // 화면이 읽는 창구도 같은 말을 한다.
        val response = view.read(world.userId, world.projectId, world.gameBuildId)!!
        assertThat(response.lastScan!!.state).isEqualTo(ScanState.FAILED)
        assertThat(response.lastScan!!.error).isEqualTo("릴리스 빌드에서는 스캔할 수 없습니다.")
    }

    /**
     * **QA 가 지금 쓰는 모양의 `ACTION_RESULT` 는 이 라우터를 그냥 지나간다.**
     *
     * 이것이 QA 경로가 안 깨진다는 증거다. 핸들러는 `handle` 이 `false` 일 때만 QA 브리지로 넘기므로,
     * 이 단언이 곧 "QA 결과는 지금까지처럼 QA 브리지로 간다"이다. 페이로드는
     * `ArtelWebSocketIntegrationTest` 가 실제로 흘려 보내는 것과 같은 모양이다 — `action` 칸이 없다.
     */
    @Test
    fun `QA 모양의 액션 결과는 이 라우터를 지나간다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world))

        val qaShaped = """
            {"type":"ACTION_RESULT","id":1,"requestId":7,
             "results":[{"id":2,"success":true,"error":""},
                        {"id":3,"success":false,"error":"Unknown target id: 123"}]}
        """.trimIndent()

        assertThat(router.handle(world.instanceId, qaShaped)).isFalse()

        // 남의 프레임을 보고 상태를 건드리지 않았다.
        assertThat(statuses.find(world.gameBuildId)!!.state).isEqualTo(ScanState.REQUESTED)
    }

    /** 다른 액션 이름도, 깨진 JSON 도 마찬가지로 지나간다 — 이 분기는 스캔 결과에만 반응한다. */
    @Test
    fun `스캔이 아닌 프레임은 무엇이든 지나간다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world))

        assertThat(router.handle(world.instanceId, """{"action":"scan_all_scenes","success":true}""")).isFalse()
        assertThat(router.handle(world.instanceId, "not json at all")).isFalse()
        assertThat(router.handle(world.instanceId, """{"type":"ACTION_RESULT"}""")).isFalse()

        assertThat(statuses.find(world.gameBuildId)!!.state).isEqualTo(ScanState.REQUESTED)
    }

    /** 게임이 알린 씬 진행이 마지막 스캔 상태에 적히고 `scan` 이벤트로 흘러간다(ARTEL-1178). */
    @Test
    fun `게임이 알린 진행이 상태와 화면에 간다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world))
        val events = collectScanEvents(world.gameBuildId, 1) {
            progress.record(world.instanceId, progressFrame("SCANNING", done = 3, total = 12, scene = "Title"))
        }

        val status = statuses.find(world.gameBuildId)!!
        assertThat(status.phase).isEqualTo(ScanPhase.SCANNING)
        assertThat(status.progress).isEqualTo(ScanProgress(done = 3, total = 12, scene = "Title"))
        assertThat(events.single().progress).isEqualTo(status.progress)
    }

    /**
     * 우리가 그 인스턴스에 보낸, 아직 도는 스캔의 진행만 적는다. 다른 인스턴스의 진행 · 끝난 스캔 뒤에 늦게 닿은 진행 ·
     * 서버 쪽 단계를 사칭한 진행은 버린다.
     */
    @Test
    fun `남의 스캔 · 끝난 스캔 · 서버 단계의 진행은 버린다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world).copy(gameInstanceId = world.instanceId + 100_000))
        progress.record(world.instanceId, progressFrame("SCANNING", done = 1, total = 2))
        assertThat(statuses.find(world.gameBuildId)!!.progress).isNull()

        statuses.put(requestedStatus(world))
        progress.record(world.instanceId, progressFrame("INGESTING", done = 1, total = 2))
        assertThat(statuses.find(world.gameBuildId)!!.progress).isNull()

        statuses.put(requestedStatus(world).copy(state = ScanState.SUCCEEDED, finishedAt = Instant.now()))
        progress.record(world.instanceId, progressFrame("UPLOADING", done = 0, total = 1))
        assertThat(statuses.find(world.gameBuildId)!!.phase).isEqualTo(ScanPhase.SCANNING)
    }

    /** 게임이 보낸 수가 범위를 벗어나도 상태는 범위 안에 있다. */
    @Test
    fun `진행 수는 범위 안으로 자른다`(): Unit = runBlocking {
        val world = newWorld()
        statuses.put(requestedStatus(world))
        progress.record(world.instanceId, progressFrame("SCANNING", done = 40, total = 12, scene = "S".repeat(500)))

        val recorded = statuses.find(world.gameBuildId)!!.progress!!
        assertThat(recorded.done).isEqualTo(12)
        assertThat(recorded.scene).hasSize(200)
    }

    /** 결과가 오면 적재 · TC 생성 단계를 차례로 알린 뒤 끝난다. 큰 게임에서 이 둘이 수십 초다. */
    @Test
    fun `결과가 오면 적재와 TC 생성 단계를 지나 끝난다`(): Unit = runBlocking {
        val world = newWorld()
        newDocument(world.contentMapId, healthyDocument())
        statuses.put(requestedStatus(world).copy(testCases = ScanTestCases(carryOver = true)))

        val events = collectScanEvents(world.gameBuildId, 3) {
            router.handle(world.instanceId, actionResult(success = true))
        }

        assertThat(events.map { it.state to it.phase }).containsExactly(
            ScanState.REQUESTED to ScanPhase.INGESTING,
            ScanState.REQUESTED to ScanPhase.GENERATING_TEST_CASES,
            ScanState.SUCCEEDED to ScanPhase.GENERATING_TEST_CASES,
        )
    }

    /**
     * 게임이 씬을 도는 도중 끊기면 결과는 오지 않는다. 그 스캔은 실패로 닫히고 화면에 흘러간다 — 아니면 화면은 영영 "스캔하는
     * 중" 이다. 이미 결과를 받아 서버가 적재 중인 스캔은 건드리지 않는다.
     */
    @Test
    fun `게임이 씬을 도는 도중 끊기면 그 스캔은 실패로 닫힌다`(): Unit = runBlocking {
        val scanning = newWorld()
        val ingesting = newWorld()
        statuses.put(requestedStatus(scanning).copy(phase = ScanPhase.SCANNING))
        statuses.put(requestedStatus(ingesting).copy(gameInstanceId = scanning.instanceId, phase = ScanPhase.INGESTING))

        val events = collectScanEvents(scanning.gameBuildId, 1) { router.gameDisconnected(scanning.instanceId) }

        val closed = statuses.find(scanning.gameBuildId)!!
        assertThat(closed.state).isEqualTo(ScanState.FAILED)
        assertThat(closed.error).contains("연결이 끊겼습니다")
        assertThat(events.single().state).isEqualTo(ScanState.FAILED)
        assertThat(statuses.find(ingesting.gameBuildId)!!.state).isEqualTo(ScanState.REQUESTED)
    }

    // ---------- 픽스처 ----------

    private suspend fun collectScanEvents(
        gameBuildId: Long,
        count: Int,
        action: suspend () -> Unit,
    ): List<LastScanResponse> = coroutineScope {
        val collected = async(start = CoroutineStart.UNDISPATCHED) {
            withTimeout(10_000) {
                streamManager.stream(gameBuildId)
                    .mapNotNull { it.data()?.scan }
                    .take(count)
                    .toList()
            }
        }
        action()
        collected.await()
    }

    private fun progressFrame(stage: String, done: Int, total: Int, scene: String? = null) =
        """{"type":"EVIDENCE_SCAN_PROGRESS","stage":"$stage","done":$done,"total":$total""" +
            (scene?.let { ""","scene":"$it"""" } ?: "") + "}"

    private data class World(
        val userId: Long,
        val projectId: Long,
        val gameBuildId: Long,
        val instanceId: Long,
        val contentMapId: Long,
    )

    private fun requestedStatus(world: World) = ScanStatus(
        gameBuildId = world.gameBuildId,
        gameInstanceId = world.instanceId,
        gameInstanceName = "instance",
        state = ScanState.REQUESTED,
        requestedAt = Instant.now(),
    )

    /**
     * **실측 프레임의 모양이다.** SDK 는 결과를 `results[]` 배열에 싣는다.
     *
     * 이 헬퍼가 처음에는 `action` 과 `success` 를 최상위에 두는 평평한 모양을 만들었다. 그 모양은
     * 계약 문서에만 있었고 SDK 가 실제로 보내는 것이 아니었다 — 그래서 이 파일의 테스트가 전부
     * 통과하는 동안 프로덕션에서는 적재가 한 번도 돌지 않았다. 픽스처가 실물과 다르면 테스트는
     * 자기 자신을 검증한다.
     */
    private fun actionResult(success: Boolean, error: String? = null) =
        """{"type":"ACTION_RESULT","id":12,"requestId":1,"results":[""" +
            """{"id":1,"success":$success,"error":${error?.let { "\"$it\"" } ?: "\"\""},""" +
            """"action":"scan_evidence"}]}"""

    /**
     * 액션 이름이 최상위에 있는 모양도 계속 받는다.
     *
     * 프레임이 한 결과만 평평하게 싣는 모양으로 바뀌어도 이쪽이 조용히 멎지 않게 하는 방어다.
     */
    private fun flatActionResult(success: Boolean) =
        """{"type":"ACTION_RESULT","action":"scan_evidence","success":$success,"error":null}"""

    private suspend fun newWorld(): World {
        val now = Instant.now()
        val userId = db.sql("INSERT INTO app_user (display_name, nickname, user_tag) VALUES ('console', 'console-' || gen_random_uuid(), '0000') RETURNING id")
            .map { row, _ -> row.get("id", java.lang.Long::class.java)!!.toLong() }
            .one().block()!!
        val project = projects.save(
            ProjectEntity(name = "scan-result-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
        )
        members.save(
            ProjectMemberEntity(projectId = project.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id, version = "v${System.nanoTime()}", createdAt = now, updatedAt = now)
        )
        val instance = gameInstances.save(
            GameInstanceEntity(
                projectId = project.id,
                name = "instance",
                platform = "UNITY",
                lastGameBuildId = build.id!!,
                lastConnectedAt = now,
                createdAt = now,
                updatedAt = now,
            )
        )
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id,
                schemaVersion = 6,
                capture = Capture.EDITOR.wire,
                evidenceDigest = "d4b31e4da9504b7d",
            )
        )
        return World(userId, project.id, build.id, instance.id!!, map.id!!)
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

    private fun healthyDocument(): String = document(secondRecordKey = null)

    /** 둘째 레코드의 키가 `capability.input_key` 의 `VARCHAR(64)` 를 넘어 적재가 거절된다. */
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
