package kr.artel.orchestration.contentmap

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.reactive.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.contentmap.dto.ConditionNodeResponse
import kr.artel.orchestration.contentmap.dto.ContentMapResponse
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.EdgeSource
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SpecGapReason
import kr.artel.orchestration.contentmap.entity.SpecStatus
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.contentmap.service.ContentMapViewService
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
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
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 실측 근거 문서를 적재하고 **조회 API 가 그 표를 정직하게 옮기는가**를 본다.
 *
 * `ContentMapIngestGoldenTest` 가 "행이 DB 에 앉는가"를 보는 자리라면, 여기는 앉은 행이 **화면이
 * 읽을 수 있는 모양으로 나오는가**를 본다. 두 테스트가 같은 문서를 쓰므로 수치가 서로를 검산한다 —
 * 저쪽이 못 박은 기능 491 · 창구 51 · 스폰 98 이 이쪽 응답의 합과 맞아야 한다.
 *
 * **수치를 그냥 베끼지 않는다.** 각 단언에 그 수가 어디서 나오는 관계인지를 적고, 가능한 곳에서는
 * 하드코딩된 수 대신 DB 를 다시 세어 관계 자체를 확인한다. 그래야 적재 규칙이 바뀌어 수가 움직일 때
 * 이 파일이 "숫자를 고치라"가 아니라 "관계가 깨졌다"를 가리킨다.
 *
 * 실측(2026-08-19, `wv-editor-latest.json`, schema 6 · editor)에서 status 분포:
 *
 * | status | 수 | 어디서 |
 * |---|---|---|
 * | `not-a-step` | 440 | 조작이 없는 행. 이 중 98 이 스폰 출신이다 |
 * | `runnable` | 31 | 조작이 있고 관측 가능한 효과가 있다 |
 * | `needs-probe` | 20 | 조작은 있는데 근거가 효과를 말하지 않는다 |
 * | `unreachable-precondition` | 0 | 이 문서에는 없다 |
 *
 * `31 + 20 = 51` 이 `v_content_map_capability` 의 행 수이고(적재 골든 테스트가 못 박은 값),
 * `440 + 51 = 491` 이 기능 총수다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ContentMapViewGoldenTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var view: ContentMapViewService
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var scenes: SceneRepository
    @Autowired private lateinit var sceneEdges: SceneEdgeRepository
    @Autowired private lateinit var db: DatabaseClient

    private var userId = 0L
    private var projectId = 0L
    private var gameBuildId = 0L
    private var contentMapId = 0L
    private lateinit var response: ContentMapResponse

    /**
     * 1.4MB 를 테스트마다 다시 적재하지 않는다. 적재는 문서 하나가 한 트랜잭션이라, 한 번 돌려 놓고
     * 그 결과를 여러 각도에서 읽는 것이 이 파일이 하는 일과도 맞는다.
     *
     * 이 지도의 문서를 집어 `ingest(document)` 로 부른다. 전역 큐를 도는 `ingestPending` 을 쓰지
     * 않는 이유: 같은 Postgres 를 나눠 쓰는 다른 테스트가 남긴 대기 문서까지 이 테스트의 시간에
     * 적재되고, 그 실패가 이 테스트의 실패로 보인다.
     */
    @BeforeAll
    fun `골든 문서를 한 번 적재하고 읽는다`(): Unit = runBlocking {
        userId = newUser()
        projectId = newProject(userId)
        gameBuildId = newBuild(projectId)
        contentMapId = newContentMap(gameBuildId, Capture.EDITOR).id!!

        putGoldenDocument(contentMapId)
        ingest.ingest(documents.findByContentMapIdOrderByReceivedAtDesc(contentMapId).first())

        response = view.read(userId, projectId, gameBuildId, capture = null)!!
    }

    /**
     * 씬 7개가 그대로 나오고, **씬별 카운트의 합이 기능 총수와 같다.**
     *
     * 합이 어긋나면 어느 씬의 기능이 응답에서 통째로 빠진 것이고, 화면에서는 그 씬이 그냥 작아
     * 보일 뿐 아무 오류도 나지 않는다. 그래서 총수를 하드코딩하지 않고 DB 를 다시 세어 비교한다.
     *
     * 씬 이름을 함께 못 박는 것은 적재 골든 테스트와 같은 이유다 — 이 목록이 문서의 `scenes` 배열과
     * 같아야 하고, 늘어나면 조인이 문서에 없는 씬 이름을 만들기 시작한 것이다.
     */
    @Test
    fun `씬 7개가 나오고 씬별 카운트의 합이 기능 총수와 같다`(): Unit = runBlocking {
        assertThat(response.scenes).hasSize(7)
        assertThat(response.scenes.map { it.name }).containsExactly(
            // 이름 오름차순. 화면이 정렬을 다시 하지 않아도 되게 서버가 순서를 정한다.
            "EndingScene",
            "GameClearScene",
            "GameOverScene",
            "Map_scene",
            "StoryScene",
            "TitleScene",
            "TurnBattleScene",
        )

        val capabilityRows = count("SELECT count(*) FROM capability WHERE content_map_id = :id")
        assertThat(response.scenes.sumOf { it.capabilities.total }).isEqualTo(capabilityRows)
        assertThat(capabilityRows).isEqualTo(491)

        // 씬 하나 안에서도 네 칸의 합이 total 이다. 어긋나면 status 어휘에 우리가 모르는 값이
        // 생긴 것이고, 그 행들은 어느 칸에도 세어지지 않은 채 사라진다.
        response.scenes.forEach { scene ->
            with(scene.capabilities) {
                assertThat(runnable + needsProbe + notAStep + unreachablePrecondition)
                    .describedAs(scene.name)
                    .isEqualTo(total)
            }
        }
    }

    /**
     * **`notAStep` 은 뷰가 답할 수 없는 칸이고, 그것이 이 엔드포인트가 뷰를 안 쓰는 이유다.**
     *
     * `v_content_map_capability` 는 `status <> 'not-a-step'` 으로 걸러 내므로, 뷰로 셌다면 여기가
     * 전부 0 이고 씬별 합이 491 이 아니라 51 이 된다. 표가 아홉 배 작아 보이는데 아무 오류도 나지
     * 않는다.
     *
     * 관계로 확인한다: `total - notAStep` 의 합이 뷰의 행 수와 같아야 한다. 이 등식이 성립하면
     * 응답이 뷰가 무엇을 거르는지 정확히 알고 있다는 뜻이다.
     */
    @Test
    fun `notAStep 은 뷰가 거르는 행이고 합이 뷰 행 수를 채운다`(): Unit = runBlocking {
        val viewRows = count("SELECT count(*) FROM v_content_map_capability WHERE content_map_id = :id")

        assertThat(response.scenes.sumOf { it.capabilities.total - it.capabilities.notAStep })
            .isEqualTo(viewRows)
        // 적재 골든 테스트가 못 박은 값. 창구에 남는 것은 조작을 든 51 뿐이다(click 24 + press 27).
        assertThat(viewRows).isEqualTo(51)

        assertThat(response.scenes.sumOf { it.capabilities.notAStep }).isEqualTo(440)
        assertThat(response.scenes.sumOf { it.capabilities.runnable }).isEqualTo(31)
        assertThat(response.scenes.sumOf { it.capabilities.needsProbe }).isEqualTo(20)
        // 이 문서에는 하나도 없다. ARTEL-461 이 gap 판정을 실행 축으로 옮기면 여기가 움직인다.
        assertThat(response.scenes.sumOf { it.capabilities.unreachablePrecondition }).isZero()

        // 씬 하나를 통째로 못 박는다. TurnBattleScene 이 스폰 출신 행의 본체라 가장 크다.
        val battle = response.scenes.single { it.name == "TurnBattleScene" }
        assertThat(battle.capabilities.total).isEqualTo(232)
        assertThat(battle.capabilities.notAStep).isEqualTo(224)
        assertThat(battle.capabilities.runnable).isEqualTo(6)
        assertThat(battle.capabilities.needsProbe).isEqualTo(2)
    }

    /**
     * 오늘 `walked` 는 전부 `false` 다. **그것이 정직한 값이다.**
     *
     * 이 칸을 `true` 로 올리는 것은 QA 런인데 그 경로가 아직 없다. 적재기는 이 칸을 일부러 건드리지
     * 않는다 — 스캔이 다시 돌았다고 "가 봤다"가 취소되면 안 되기 때문이다. 언젠가 적재기가 문서의
     * `scenes` 목록만 보고 이 칸을 채우기 시작하면 여기가 깨지고, 그때 물어야 할 것은
     * **"순회했다는 말이 근거 문서가 할 수 있는 말인가"**다.
     */
    @Test
    fun `아직 아무도 씬을 밟지 않아 walked 는 전부 false 다`() {
        assertThat(response.scenes.map { it.walked }).containsOnly(false)
    }

    /**
     * gap 이 사유별로 묶여 나오고, **그 합이 사유 있는 행 수와 같다.**
     *
     * `when-missing` 342 는 우연한 수가 아니다 — `not-a-step` 440 에서 스폰 출신 98 을 뺀 값이다
     * (V46 이 스폰 행을 이 사유에서 빼낸다. 스폰 행은 조작이 **없어야 맞는** 행이라 그대로 두면
     * 실제 결함을 덮는다). 그 관계를 여기서 다시 계산해 확인한다.
     *
     * `evidence-missing` 이 0 인 것도 단언한다. 그것만 성격이 다른 사유다 — 게임의 근거가 부족한
     * 것이 아니라 **우리 적재기가 근거를 잃은 것**이라, 세어지면 고칠 곳은 SDK 가 아니라 우리 쪽이다.
     */
    @Test
    fun `gap 이 사유별로 묶이고 합이 사유 있는 행 수와 같다`(): Unit = runBlocking {
        val withReason = count(
            "SELECT count(*) FROM v_spec_gap WHERE content_map_id = :id AND reason IS NOT NULL"
        )
        assertThat(response.gaps.sumOf { it.count.toLong() }).isEqualTo(withReason)

        val byReason = response.gaps.associate { it.reason to it.count }
        assertThat(byReason).containsOnlyKeys(
            SpecGapReason.WHEN_MISSING.wire,
            SpecGapReason.THEN_MISSING.wire,
            SpecGapReason.GIVEN_INCOMPLETE.wire,
            SpecGapReason.THEN_DETAIL_UNKNOWN.wire,
            SpecGapReason.GIVEN_SUBJECT_UNKNOWN.wire,
        )

        // 342 = not-a-step 440 - 스폰 98. 스폰 행이 when-missing 을 덮지 않는다는 V46 의 약속이다.
        val spawned = count(
            "SELECT count(*) FROM capability WHERE content_map_id = :id AND spawned_by_field IS NOT NULL"
        )
        assertThat(spawned).isEqualTo(98)
        assertThat(byReason[SpecGapReason.WHEN_MISSING.wire]?.toLong()).isEqualTo(440 - spawned)

        assertThat(byReason[SpecGapReason.THEN_MISSING.wire]).isEqualTo(73)
        assertThat(byReason[SpecGapReason.GIVEN_INCOMPLETE.wire]).isEqualTo(17)
        assertThat(byReason[SpecGapReason.THEN_DETAIL_UNKNOWN.wire]).isEqualTo(11)
        assertThat(byReason[SpecGapReason.GIVEN_SUBJECT_UNKNOWN.wire]).isEqualTo(6)

        // 많은 것부터 나온다. 이 표가 답하는 질문이 "다음에 무엇을 고칠까"라 순서가 곧 답이다.
        assertThat(response.gaps.map { it.count })
            .isEqualTo(response.gaps.map { it.count }.sortedDescending())

        // 적재기가 근거를 잃지 않았다. 세어지면 고칠 곳은 SDK 가 아니라 우리 쪽이다.
        assertThat(byReason).doesNotContainKey(SpecGapReason.EVIDENCE_MISSING.wire)
    }

    /**
     * 커버리지 지표의 분자와 분모. 첫 적재 직후라 **분자가 0 인 것이 정답이다.**
     *
     * 분모 491 은 근거 출신 기능 전부이고, 이 문서에서는 기능 전체와 같다(QA 런이 없어 observed ·
     * inferred 행이 아직 없다). 분자가 0 이 아니면 적재기가 `verification` 을 쓴 것이고, 그 칸은
     * 적재기가 쓰면 안 되는 칸이다 — 되돌릴지는 근거가 실제로 달라졌는지가 정한다.
     */
    @Test
    fun `첫 적재 직후 확인은 0 이고 분모는 근거 출신 기능 전부다`(): Unit = runBlocking {
        assertThat(response.verification.verified).isZero()
        assertThat(response.verification.total).isEqualTo(491)
        assertThat(response.verification.total).isEqualTo(
            count("SELECT count(*) FROM capability WHERE content_map_id = :id AND origin = 'evidence'")
        )
    }

    /**
     * 적재된 지도의 루트가 헤더 그대로 나오고, **`ingestedAt` 이 찍혀 있다.**
     *
     * 화면은 이 한 칸으로 "등록만 됨"과 "적재됨"을 가른다. `content_map` 행에는 적재 시각 칸이 없어
     * 문서의 도장에서 유도하는데, 그 유도가 끊기면 적재가 끝난 지도도 영영 "적재 대기"로 보인다.
     */
    @Test
    fun `적재된 지도는 ingestedAt 이 찍히고 대기 문서가 없다`() {
        val contentMap = response.contentMap!!
        assertThat(contentMap.id).isEqualTo(contentMapId)
        assertThat(contentMap.capture).isEqualTo(Capture.EDITOR.wire)
        assertThat(contentMap.schemaVersion).isEqualTo(6)
        assertThat(contentMap.evidenceDigest).isEqualTo("d4b31e4da9504b7d")
        assertThat(contentMap.unity).isEqualTo("2022.3.62f3")
        assertThat(contentMap.platform).isEqualTo("OSXEditor")
        assertThat(contentMap.sdkVersion).isEqualTo("0.1.0")

        assertThat(contentMap.ingestedAt).isNotNull()
        assertThat(contentMap.ingestedAt).isBeforeOrEqualTo(Instant.now())
        assertThat(response.pendingDocuments).isEmpty()
    }

    /**
     * **문서가 한 번도 등록되지 않은 빌드는 `contentMap: null` 이다. 404 가 아니다.**
     *
     * 빌드는 존재하고 접근도 된다. 없는 것은 아직 아무도 올리지 않은 문서이고, 그것은 오류가 아니라
     * 화면이 "문서를 올리세요"를 그려야 하는 정상 상태다. 404 로 답하면 화면은 그 상태와 "남의
     * 빌드"를 구분할 수 없다.
     */
    @Test
    fun `문서가 없는 빌드는 404 가 아니라 빈 응답이다`(): Unit = runBlocking {
        val empty = view.read(userId, projectId, newBuild(projectId), capture = null)!!

        assertThat(empty.contentMap).isNull()
        assertThat(empty.scenes).isEmpty()
        assertThat(empty.edges).isEmpty()
        assertThat(empty.gaps).isEmpty()
        assertThat(empty.pendingDocuments).isEmpty()
        assertThat(empty.verification.total).isZero()
        assertThat(empty.verification.verified).isZero()
    }

    /**
     * **등록만 되고 아직 앉지 않은 지도는 `ingestedAt: null` 이고 대기 문서가 서 있다.**
     *
     * 이 상태가 `contentMap: null` 과 구분되지 않으면 화면이 "올리세요"와 "적재 버튼을 누르세요"를
     * 같은 말로 그린다. 두 상태를 가르는 값이 딱 이 둘이라, 한 번의 호출로 둘 다 답할 수 있어야 한다.
     *
     * 실패 자국(`ingestFailedAt` · `ingestError`)도 함께 낸다. 없으면 "아직 안 눌렀다"와 "눌러도
     * 깨진다"가 같은 모양이 된다.
     */
    @Test
    fun `등록만 된 지도는 ingestedAt 이 비고 대기 문서가 선다`(): Unit = runBlocking {
        val build = newBuild(projectId)
        val map = newContentMap(build, Capture.PLAYER)
        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = map.id!!,
                objectKey = "content-map/${map.id}/not-yet.json",
                contentHash = "0".repeat(64),
                byteSize = 10,
            )
        )
        val failedAt = Instant.parse("2026-08-20T09:00:00Z")
        // 이 칸에 적는 프로덕션 코드는 아직 없다(ARTEL-491). 조회가 그 값을 실어 내는지만 보면 되므로
        // 여기서는 직접 적는다 — 저장소에 이 diff 가 쓰지 않는 쓰기 메서드를 만들지 않는다.
        db.sql("UPDATE content_map_document SET ingest_failed_at = :at, ingest_error = :err WHERE id = :id")
            .bind("at", failedAt)
            .bind("err", "schema 7 은 아직 읽지 못합니다.")
            .bind("id", document.id!!)
            .fetch().rowsUpdated().block()

        val registered = view.read(userId, projectId, build, capture = null)!!

        assertThat(registered.contentMap).isNotNull()
        assertThat(registered.contentMap!!.ingestedAt).isNull()
        assertThat(registered.scenes).isEmpty()
        assertThat(registered.pendingDocuments).hasSize(1)
        with(registered.pendingDocuments.single()) {
            assertThat(documentId).isEqualTo(document.id!!)
            assertThat(receivedAt).isNotNull()
            assertThat(ingestFailedAt).isEqualTo(failedAt)
            assertThat(ingestError).isEqualTo("schema 7 은 아직 읽지 못합니다.")
        }
    }

    /**
     * **`capture` 를 지정하면 폴백하지 않는다.**
     *
     * 이 빌드에는 editor 지도만 있다. `?capture=player` 에 editor 를 내주면 화면이 authoring 값을
     * 플레이 이후 값이라고 그린다 — 적의 `label` 이 authored `20` 인가 남은 체력 `20` 인가가 갈리는
     * 자리라, 조용히 틀리고 아무도 못 알아본다.
     *
     * 생략했을 때 editor 가 나오는 것도 함께 지킨다. 기본값이 흔들리면 화면은 매번 다른 지도를 본다.
     */
    @Test
    fun `capture 를 지정하면 다른 capture 로 폴백하지 않는다`(): Unit = runBlocking {
        val player = view.read(userId, projectId, gameBuildId, capture = Capture.PLAYER)!!
        assertThat(player.contentMap).isNull()
        assertThat(player.scenes).isEmpty()

        val editor = view.read(userId, projectId, gameBuildId, capture = Capture.EDITOR)!!
        assertThat(editor.contentMap?.id).isEqualTo(contentMapId)

        // 생략하면 가장 최근에 알게 된 지도. 이 빌드에는 editor 하나뿐이다.
        assertThat(view.read(userId, projectId, gameBuildId, capture = null)!!.contentMap?.id)
            .isEqualTo(contentMapId)
    }

    /**
     * 씬 전이가 **적재만으로 선다.** 골든 문서에 심은 행이 아니라 근거에서 나온 행이다.
     *
     * ARTEL-445 가 `effects.kind='scene'` 을 간선으로 옮기기 시작했다. 그 수와 쌍은 그쪽 테스트가
     * 고정하므로 여기서는 되풀이하지 않고, **조회가 그 행을 곱하지도 빠뜨리지도 않고 싣는지**만 본다.
     *
     * 기능 요약을 함께 내는 것이 계약 밖의 덤이다. 없으면 같은 씬으로 가는 간선 여럿이 화면에서
     * 전부 같은 이름으로 보인다 — 실측 문서에서 `Player::Death` 하나가 진입점 넷이라 실제로 일어난다.
     * `LEFT JOIN` 인 이유는 자동 전이의 `capability_id` 가 null 이기 때문이고, 그때도 "갔다는 사실"은
     * 남아야 한다.
     */
    @Test
    fun `씬 전이가 기능 요약을 달고 나오고 관측 간선도 함께 실린다`(): Unit = runBlocking {
        val fromEvidence = response.edges
        assertThat(fromEvidence).isNotEmpty()
        assertThat(fromEvidence).allSatisfy {
            assertThat(it.source).isEqualTo(EdgeSource.STATIC.wire)
            // 정적 분석은 "갈 수 있다"까지만 안다. 가 봤다는 것은 QA 런만 적는다.
            assertThat(it.verifiedAt).isNull()
            assertThat(it.capabilityId).isNotNull()
            assertThat(it.capabilitySummary).isNotBlank()
        }
        // 응답의 간선 수가 표의 행 수와 같다 — `capability` 조인이 행을 곱하지 않는다는 증거다.
        val rows = count(
            """
            SELECT count(*) FROM scene_edge e
            JOIN scene s ON s.id = e.from_scene_id
            WHERE s.content_map_id = :id
            """
        )
        assertThat(fromEvidence).hasSize(rows.toInt())

        // 아직 순회하지 못한 씬으로 가는 관측 간선. 이름만 있고 id 가 없는 것이 정상이다.
        val title = scenes.findByContentMapIdAndName(contentMapId, "TitleScene")!!
        sceneEdges.save(
            SceneEdgeEntity(
                fromSceneId = title.id!!,
                toSceneName = "Scene_that_was_never_walked",
                source = EdgeSource.RUNTIME.wire,
            )
        )

        val edges = view.read(userId, projectId, gameBuildId, capture = null)!!.edges

        assertThat(edges).hasSize(fromEvidence.size + 1)
        with(edges.single { it.toSceneName == "Scene_that_was_never_walked" }) {
            // 아직 못 가본 전이가 곧 커버리지 구멍이다. 자동 전이라 기능도 요약도 없다.
            assertThat(toSceneId).isNull()
            assertThat(capabilityId).isNull()
            assertThat(capabilitySummary).isNull()
            assertThat(verifiedAt).isNull()
            assertThat(source).isEqualTo(EdgeSource.RUNTIME.wire)
        }
    }

    /**
     * **씬마다 조작 단계의 목록이 실린다. `steps.size` 가 `total - notAStep` 과 같다.**
     *
     * 이 등식이 이 diff 의 핵심이다. 카운트와 목록의 출처가 다르기 때문이다 — 카운트는 `capability`
     * 직접 집계이고(뷰가 `not-a-step` 을 걸러 그 칸을 답할 수 없다), 목록은
     * `v_content_map_capability` 다. 두 출처가 같은 표를 본다는 것을 이 등식 말고는 확인할 방법이
     * 없고, 어긋나면 화면은 "14개 있다"고 쓴 옆에 12줄을 그리며 아무 오류도 내지 않는다.
     *
     * 씬별 수를 **골든 문서에서 세어** 못 박는다. 이슈 본문의 실측 수치는 `editor-play` 캡처의 것이라
     * 다른 문서다 — 그것을 여기 베끼면 이 파일이 자기가 적재한 적 없는 문서를 단언하게 된다.
     */
    @Test
    fun `씬마다 조작 단계 목록이 실리고 개수가 카운트와 맞는다`(): Unit = runBlocking {
        response.scenes.forEach { scene ->
            assertThat(scene.steps.size.toLong())
                .describedAs(scene.name)
                .isEqualTo(scene.capabilities.total - scene.capabilities.notAStep)
        }

        assertThat(response.scenes.associate { it.name to it.steps.size }).containsExactlyInAnyOrderEntriesOf(
            mapOf(
                "EndingScene" to 2,
                "GameClearScene" to 8,
                "GameOverScene" to 1,
                "Map_scene" to 16,
                "StoryScene" to 2,
                "TitleScene" to 14,
                "TurnBattleScene" to 8,
            )
        )

        // 31 runnable + 20 needs-probe. 적재 골든 테스트가 못 박은 창구 51 과 같은 수다.
        assertThat(response.scenes.sumOf { it.steps.size }).isEqualTo(51)
    }

    /**
     * **근거 조인이 행을 곱하지 않는다.**
     *
     * `v_content_map_capability` 는 `LEFT JOIN capability_evidence` 로 근거를 붙이고 그 조인을 접는
     * 장치가 뷰 안에 없다. 오늘 `capability_evidence.capability_id` 는 PK 라 1:1 이지만, 서비스는 그
     * 가정을 두지 않고 기능 하나당 한 줄로 접는다. 그 접기가 실제로 걸려 있는지를 **기능 id 의
     * 유일성**으로 확인한다 — 곱해졌다면 같은 id 가 두 번 선다.
     *
     * 총수가 뷰 행 수와 같다는 것도 함께 본다. 접기가 너무 많이 접었다면 여기가 줄어든다.
     */
    @Test
    fun `단계는 기능 하나에 한 줄이고 총수가 뷰 행 수와 같다`(): Unit = runBlocking {
        val ids = response.scenes.flatMap { scene -> scene.steps.map { it.id } }

        assertThat(ids).doesNotHaveDuplicates()
        assertThat(ids).hasSize(
            count("SELECT count(*) FROM v_content_map_capability WHERE content_map_id = :id").toInt()
        )
    }

    /**
     * **`not-a-step` 은 목록에 오지 않는다.** 단계가 아닌 것을 단계 목록에 넣지 않는다.
     *
     * 골든 문서에서 기능 491행 중 440행이 거기다. 실으면 응답이 아홉 배가 되고, 무엇보다 화면이
     * 누를 수 없는 것을 누르라고 그린다.
     *
     * `status` 는 **kebab-case 값 그대로** 나간다. `capabilities: {needsProbe, notAStep}` 이
     * camelCase 인 것과 헷갈리기 쉬운데, 저쪽은 키 이름이고 이쪽은 값이라 규칙이 다르다. 이 저장소의
     * 값 어휘는 전부 kebab 이다(`source: static|runtime`, `capture: editor-play`).
     */
    @Test
    fun `단계에 not-a-step 이 없고 status 는 kebab-case 세 값 안이다`() {
        val statuses = response.scenes.flatMap { it.steps }.map { it.status }

        assertThat(statuses).isNotEmpty()
        assertThat(statuses).doesNotContain(SpecStatus.NOT_A_STEP.wire)
        assertThat(statuses).isSubsetOf(
            SpecStatus.RUNNABLE.wire,
            SpecStatus.NEEDS_PROBE.wire,
            SpecStatus.UNREACHABLE_PRECONDITION.wire,
        )
        // 이 문서에는 두 값만 나온다. 씬별 카운트의 합과 같아야 한다.
        assertThat(statuses.count { it == SpecStatus.RUNNABLE.wire }.toLong())
            .isEqualTo(response.scenes.sumOf { it.capabilities.runnable })
        assertThat(statuses.count { it == SpecStatus.NEEDS_PROBE.wire }.toLong())
            .isEqualTo(response.scenes.sumOf { it.capabilities.needsProbe })
    }

    /**
     * **조건 트리가 한 벌 어휘로 정규화되어 나온다.**
     *
     * 계약 둘: `kind` 는 늘 소문자이고, 이름표 없는 노드는 나가지 않는다. 지저분한 것은 서버가
     * 흡수하고 화면은 한 어휘만 안다.
     *
     * 흡수할 지저분함이 실제로 있다. 적재기는 조건을 갈랐을 때 **내부 모델을 그대로 직렬화**하므로
     * (`ContentMapIngestService.conditionJsonOf`) `{"kind":"EVERY"}` 같은 대문자가 표에 앉아 있다.
     * 오늘 파서는 그것을 `unknown` 으로 읽고, 그래서 **`unknown` 이 이 문서에 실제로 나온다** —
     * 버려지지 않고 이름표를 달고 나가는 것이 요점이다.
     *
     * **`unknown` 의 수를 못 박지 않는다.** ARTEL-495 가 파서에 대문자 `EVERY` 를 읽는 관대함을 넣는
     * 중이고, 그것이 들어오면 이 노드들이 `every` 로 바뀐다. 이 조회는 파서를 재사용하므로 **한 줄도
     * 고치지 않고** 그 개선을 물려받는다 — 그것이 정규화를 두 벌로 만들지 않은 이유다. 그래서 여기서
     * 지키는 것은 개수가 아니라 **어휘를 벗어나는 노드가 없다**는 성질이다.
     */
    @Test
    fun `조건 트리가 소문자 한 벌 어휘로 나오고 이름표 없는 노드가 없다`() {
        val steps = response.scenes.flatMap { it.steps }
        val kinds = steps.flatMap { flatten(it.given) }.map { it.kind }

        assertThat(kinds).isNotEmpty()
        assertThat(kinds).isSubsetOf("always", "test", "gesture", "every", "either", "unknown")
        assertThat(kinds).allSatisfy { assertThat(it).isEqualTo(it.lowercase()) }
        assertThat(kinds).noneMatch { it.isBlank() }

        // 51건 전부 조건을 든다. 이 문서는 전부 근거 출신이라 `given` 이 빌 이유가 없다 —
        // null 은 `capability_evidence` 행이 없는 관측 출신 기능에만 나온다.
        assertThat(steps).allSatisfy { assertThat(it.given).isNotNull() }

        // `givenText` 는 51건 전부 null 이다. ARTEL-447 이 채우기 전까지 화면을 지탱하는 것은
        // 조건 트리뿐이고, 그것이 아래 테스트가 말하는 바다.
        assertThat(steps).allSatisfy { assertThat(it.givenText).isNull() }
    }

    /**
     * **조건이 없으면 화면에 똑같은 줄이 여럿 선다. 이 테스트가 그 이유다.**
     *
     * GameClearScene 의 8단계는 `summary` · `inputKey` · `status` 세 축으로 묶으면 2 · 2 · 4 짜리
     * 세 무리가 된다. 무리 안에서 세 축이 **전부 같고**, `givenText` 도 전부 null 이다. 조건을 싣지
     * 않으면 화면은 구분할 근거가 하나도 없는 줄 여덟 개를 그리고, 사람은 그것을 중복 버그로 읽는다.
     *
     * 조건을 실으면 무리마다 전부 갈린다. 그 사실을 여기서 못 박는다.
     *
     * 이 성질은 ARTEL-495 뒤에도 지켜진다 — 오늘 대문자 `EVERY` 는 `parts` 를 통째로 잃은 채
     * `unknown` 하나로 눌리므로, 파서가 관대해지면 갈래는 **더** 갈리지 덜 갈리지 않는다.
     */
    @Test
    fun `세 축이 같은 단계들을 조건이 가른다`() {
        val gameClear = response.scenes.single { it.name == "GameClearScene" }
        val groups = gameClear.steps.groupBy { Triple(it.summary, it.inputKey, it.status) }

        // 여덟 줄이 세 무리로 눌린다. 세 축만 보면 화면이 그릴 수 있는 것은 세 줄뿐이다.
        assertThat(gameClear.steps).hasSize(8)
        assertThat(groups.map { it.value.size }).containsExactlyInAnyOrder(2, 2, 4)
        assertThat(groups.values.flatten()).allSatisfy { assertThat(it.givenText).isNull() }

        // 조건을 실으면 무리 안에서 전부 갈린다.
        groups.forEach { (axes, steps) ->
            assertThat(steps.map { it.given }.distinct())
                .describedAs("$axes")
                .hasSize(steps.size)
        }
    }

    /** 조건 트리를 깊이 우선으로 편다. `every` · `either` 만 자식을 든다. */
    private fun flatten(node: ConditionNodeResponse?): List<ConditionNodeResponse> = when (node) {
        null -> emptyList()
        is ConditionNodeResponse.Every -> listOf(node) + node.parts.flatMap(::flatten)
        is ConditionNodeResponse.Either -> listOf(node) + node.parts.flatMap(::flatten)
        else -> listOf(node)
    }

    // ---------- 픽스처 ----------

    private suspend fun count(sql: String): Long = db
        .sql(sql)
        .bind("id", contentMapId)
        .map { row, _ -> (row.get(0) as Number).toLong() }
        .one()
        .awaitSingle()

    private suspend fun putGoldenDocument(contentMapId: Long) {
        val bytes = File(GOLDEN_PATH).readBytes()
        val objectKey = "content-map/$contentMapId/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = sha256Hex(bytes),
                byteSize = bytes.size.toLong(),
            )
        )
    }

    private suspend fun newContentMap(gameBuildId: Long, capture: Capture): ContentMapEntity =
        contentMaps.save(
            ContentMapEntity(
                gameBuildId = gameBuildId,
                schemaVersion = 6,
                capture = capture.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3",
                platform = "OSXEditor",
                backend = "mono",
                development = true,
                sdkVersion = "0.1.0",
            )
        )

    private suspend fun newUser(): Long =
        db.sql("INSERT INTO app_user (display_name) VALUES ('console') RETURNING id")
            .map { row, _ -> (row.get(0) as Number).toLong() }
            .one()
            .awaitSingle()

    /**
     * **씬 대표 이미지가 서명된 단기 주소로 나온다.**
     *
     * 바이트를 이 서버가 중계하지 않는다는 것이 요점이다 — 씬이 수백 개인 지도에서 그만큼의 이미지가
     * 응답 하나에 실리면 조회가 조회가 아니게 된다. 그리고 **없음과 못 찍음을 가른다**: 옛 SDK 는
     * 캡처를 아예 신고하지 않아 `thumbnail` 자체가 null 이고, 새 SDK 가 시도했다 실패하면
     * `unavailable` 과 이유가 온다. 화면이 "아직 안 올렸다"와 "이 씬은 못 찍는다"를 다르게 말해야 한다.
     */
    @Test
    fun `씬 이미지가 서명된 주소로 나오고 못 찍은 이유가 따로 실린다`(): Unit = runBlocking {
        // 씬 이름을 문서에서 집는다. 골든 문서의 씬 목록이 바뀌어도 이 테스트가 도는 이유다
        val captured = scenes.findByContentMapIdAndName(contentMapId, "TitleScene")!!
        val failed = response.scenes.first { it.name != captured.name }.name
        val capturedAt = Instant.parse("2026-08-26T00:00:00Z")
        scenes.save(
            captured.copy(
                imageObjectKey = "content-map-scene-captures/$gameBuildId/title.jpg",
                imageWidth = 320,
                imageHeight = 180,
                imageCapturedAt = capturedAt,
            )
        )
        scenes.save(
            scenes.findByContentMapIdAndName(contentMapId, failed)!!
                .copy(imageFailureCode = "unsupported-render-pipeline")
        )

        val fresh = view.read(userId, projectId, gameBuildId, capture = null)!!

        with(fresh.scenes.single { it.name == "TitleScene" }.thumbnail!!) {
            assertThat(state).isEqualTo("available")
            // 주소는 스토리지가 서명한 것이고, 객체 키 자체는 응답에 나가지 않는다
            assertThat(url).contains("content-map-scene-captures/$gameBuildId/title.jpg")
            assertThat(expiresAt).isNotNull()
            assertThat(width).isEqualTo(320)
            assertThat(height).isEqualTo(180)
            assertThat(reason).isNull()
        }
        with(fresh.scenes.single { it.name == failed }.thumbnail!!) {
            assertThat(state).isEqualTo("unavailable")
            assertThat(url).isNull()
            assertThat(reason).isEqualTo("unsupported-render-pipeline")
        }
        // 신고 자체가 없던 씬은 두 상태 어느 쪽도 아니다
        assertThat(fresh.scenes.filter { it.thumbnail == null }).isNotEmpty()
    }

    /**
     * **전이가 정규화된 조건을 함께 낸다.**
     *
     * `givenText` 는 사람이 읽는 한 줄이라 화면이 갈래를 구분하는 데 쓸 수 없다. 같은 컨트롤이 조건으로
     * 갈릴 때 무엇이 다른지는 조건 트리에만 있다.
     *
     * 조인이 간선을 늘리지 않는다는 것도 함께 본다 — `capability_evidence` 는 기능당 한 행
     * (PRIMARY KEY) 이므로 간선 수가 표의 행 수와 같아야 한다. 이 관계가 깨지면 화면의 그래프에
     * 같은 전이가 여러 번 그려진다.
     */
    @Test
    fun `씬 전이가 정규화된 조건을 함께 낸다`(): Unit = runBlocking {
        val fresh = view.read(userId, projectId, gameBuildId, capture = null)!!
        val fromEvidence = fresh.edges.filter { it.source == EdgeSource.STATIC.wire }

        val rows = count(
            """
            SELECT count(*) FROM scene_edge e
            JOIN scene s ON s.id = e.from_scene_id
            WHERE s.content_map_id = :id AND e.source = 'static'
            """
        )
        assertThat(fromEvidence).hasSize(rows.toInt())

        // 근거 출신 전이는 기능을 달고 있고, 그 기능은 조건 트리를 가진 행이다
        assertThat(fromEvidence).allSatisfy { assertThat(it.capabilityId).isNotNull() }
        assertThat(fromEvidence).allSatisfy { assertThat(it.given).isNotNull() }
        // 자동 전이는 기능이 없으므로 조건도 없다. null 이 "조건 없음"을 뜻한다
        assertThat(fresh.edges.filter { it.capabilityId == null })
            .allSatisfy { assertThat(it.given).isNull() }
    }

    private suspend fun newProject(userId: Long): Long {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "view-${System.nanoTime()}", genre = "ACTION", createdAt = now, updatedAt = now)
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

    private fun sha256Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        const val GOLDEN_PATH = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
