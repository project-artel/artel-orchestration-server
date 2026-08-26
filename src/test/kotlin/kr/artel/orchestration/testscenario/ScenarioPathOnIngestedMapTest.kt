package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.Capture
import kr.artel.orchestration.contentmap.entity.ContentMapDocumentEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.ingest.ContentMapIngestService
import kr.artel.orchestration.contentmap.repository.ContentMapDocumentRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.FakeDocumentStorage
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kotlinx.coroutines.flow.toList
import kr.artel.orchestration.testscenario.repository.ScenarioCaseFactRepository
import kr.artel.orchestration.testscenario.service.ScenarioPathResult
import kr.artel.orchestration.testscenario.service.ScenarioPathService
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
 * 저작을 **적재기가 만든 지도 위에서** 돌린다(ARTEL-533).
 *
 * 지금까지 저작 테스트는 전부 손으로 세운 픽스처였고, 로컬 QA 도 손으로 넣은 골든 지도 위에서
 * 돌았다. 그 지도만 `given_text` 를 들고 있었기 때문에, **저작이 실제 적재 결과에서는 사전조건을
 * 한 줄도 못 읽는다는 사실이 어떤 테스트에도 걸리지 않았다.**
 *
 * 실측(`wv-editor-latest.json` 을 이 테스트가 적재한 결과):
 *
 * | | 손적재 골든 | 적재기 |
 * |---|---|---|
 * | 기능 | 18 | 491 |
 * | `given_text` 가 있는 기능 | 14 | **0** |
 * | `capability_evidence` 행 | 0 | **491** |
 *
 * 그래서 여기서는 픽스처를 만들지 않는다. 문서를 적재기에 통째로 넣고, 그 위에서 경로를 묻는다.
 * 이 파일이 깨지면 저작과 적재 사이의 계약이 어긋난 것이다.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ScenarioPathOnIngestedMapTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var service: ScenarioPathService
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var members: ProjectMemberRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository
    @Autowired private lateinit var testCases: TestCaseRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var db: DatabaseClient
    @Autowired private lateinit var factRepository: ScenarioCaseFactRepository

    private var projectId: Long = 0
    private var userId: Long = 0
    private var contentMapId: Long = 0

    @BeforeAll
    fun `실측 문서를 적재기로 앉힌다`(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "ingested-map",
                login = "ingested", displayName = "Ingested", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        val project = projects.save(
            ProjectEntity(name = "ingested-map", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        members.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = gameBuilds.save(
            GameBuildEntity(projectId = projectId, version = "ingested", createdAt = now, updatedAt = now)
        )
        val map = contentMaps.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 6, capture = Capture.EDITOR.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        contentMapId = map.id!!

        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/$contentMapId/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        val document = documents.save(
            ContentMapDocumentEntity(
                contentMapId = contentMapId,
                objectKey = objectKey,
                contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                    .joinToString("") { "%02x".format(it) },
                byteSize = bytes.size.toLong(),
            )
        )
        ingest.ingest(document)
    }

    /**
     * **이 지도에는 `given_text` 가 한 줄도 없다.**
     *
     * 이 테스트가 서는 전제이자, 이 작업이 필요했던 이유다. 적재기가 언젠가 이 칸을 채우기 시작하면
     * 여기가 깨지고, 그때는 아래 두 테스트가 무엇을 증명하는지 다시 봐야 한다.
     */
    @Test
    fun `적재기가 만든 지도는 조건을 트리로만 든다`(): Unit = runBlocking {
        val row = db.sql(
            """
            SELECT count(*) AS total,
                   count(*) FILTER (WHERE given_text IS NOT NULL) AS with_given
            FROM capability WHERE content_map_id = $contentMapId
            """
        ).fetch().one().block()!!

        assertThat(row["total"] as Long).isGreaterThan(100)
        assertThat(row["with_given"] as Long).isZero()

        val trees = db.sql(
            """
            SELECT count(*) AS n FROM capability_evidence ev
            JOIN capability c ON c.id = ev.capability_id
            WHERE c.content_map_id = $contentMapId AND ev.condition_tree->>'kind' IS NOT NULL
            """
        ).fetch().one().block()!!
        assertThat(trees["n"] as Long).isEqualTo(row["total"] as Long)
    }

    /**
     * 전투에서 클리어 화면으로 넘어가는 자리.
     *
     * 간선은 있으나 그 조작이 `interaction=none` · `not-a-step` 이라 지시할 수 없다. 명세는 언제
     * 넘어가는지를 알고 있고, 그 조건이 **트리에만** 있다. 트리를 안 읽으면 "무엇을 해야 그 전이가
     * 일어나는지는 명세에 없다"로 끝나 사용자가 없는 것을 알려주려 하게 된다.
     */
    @Test
    fun `저절로 넘어가는 씬 전이의 조건을 지도에서 읽어 말한다`(): Unit = runBlocking {
        val battle = case("TurnBattleScene", "TurnBattleScene 화면인 상태")
        val clear = case("GameClearScene", "GameClearScene 화면인 상태")

        val answer = service.findPath(projectId, userId, battle, clear)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("TurnBattleScene→GameClearScene")
        assertThat(answer.note)
            .contains("저절로")
            .contains("BattleWaveController.wave")
            // 문자열을 쪼개던 경로에서는 여기가 `GetBattleWaveDatas` 로 잘렸다.
            .contains("GetBattleWaveDatas().Count")
            .doesNotContain("명세에 없다")
    }

    /**
     * 값을 쓰는 자리도 조건을 말한다 — **지시할 수 있는 쓰기에 가려지지 않는다**(ARTEL-534).
     *
     * 적재된 지도에서 `MapMove.StagePosition` 을 쓰는 기능은 다섯이다:
     *
     * | 기능 | 조작 | 쓰는 값 |
     * |---|---|---|
     * | 63 | `none` · `not-a-step` | `+1` — 마지막 웨이브가 끝날 때 저절로 |
     * | 144 · 145 | `click` · `runnable` | `PlayerPrefs.GetInt("StagePosition", -1)` |
     * | 209 · 210 | `click` · `runnable` | `0` |
     *
     * 지시할 수 있는 넷 중 어느 것도 `== 2` 를 못 만든다. 그 넷이 있다는 이유로 "방법이 명세에
     * 없다"로 끝내면 63번이 든 조건이 가려지고, 사용자는 명세가 이미 아는 것을 알려주려 하게 된다.
     *
     * 손으로 넣은 골든 지도에는 63번 하나뿐이라 이 상황이 나오지 않았다 — 실제 적재 결과에서만
     * 드러나는 자리다.
     */
    @Test
    fun `저절로 쓰는 값의 조건도 지도에서 읽어 말한다`(): Unit = runBlocking {
        val one = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 1")
        val two = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 2")

        val answer = service.findPath(projectId, userId, one, two)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("StagePosition")
        assertThat(answer.note)
            .contains("조작으로 지시할 수 없다")
            .contains("BattleWaveController.wave")
            .contains("GetBattleWaveDatas().Count")
            .doesNotContain("명세에 없다")
    }

    /**
     * 케이스가 든 UI 조준 대상이 지도의 기능을 찾는다(ARTEL-537).
     *
     * 값은 프로젝트 20 의 실제 케이스에서 그대로 가져왔다 — 1310 번의 근거가
     * `object:Canvas[2]/MapSceneButton[1]@?` 이고, 적재기가 앉힌 기능의 `control_selector` 가
     * `Canvas[2]/MapSceneButton[1]` 이다. 접두와 꼬리만 떼면 같은 문자열이다.
     */
    @Test
    fun `UI 조준 대상이 지도의 기능을 찾는다`(): Unit = runBlocking {
        val found = factRepository.findByControlSelector(contentMapId, "Canvas[2]/MapSceneButton[1]").toList()

        assertThat(found).isNotEmpty
        assertThat(found).allSatisfy { assertThat(it.interaction).isEqualTo("click") }
    }

    /**
     * 코드 근거는 한 조작을 가리키지 않는다(ARTEL-536).
     *
     * 이 메서드 하나가 기능 여럿을 낳고 그 안에 서로 다른 키가 섞여 있다. `performs()` 가 이것을
     * 보고 판단을 접는다 — 여기서 조작이 하나로 모이기 시작하면 그 규칙이 필요 없어진 것이다.
     */
    @Test
    fun `한 메서드가 서로 다른 조작을 낳는다`(): Unit = runBlocking {
        val found = factRepository
            .findByEvidenceTail(contentMapId, "%Map.MapMove|CharacterMove|System.Void()").toList()

        assertThat(found).hasSizeGreaterThan(1)
        assertThat(found.map { it.inputKey }.distinct()).hasSizeGreaterThan(1)
    }

    private suspend fun case(scene: String, precondition: String): Long =
        testCases.save(
            TestCaseEntity(
                projectId = projectId, scene = scene, step = "step-$precondition",
                precondition = precondition, expectedValue = "expected", metadata = Json.of("{}"),
            )
        ).id!!

    private companion object {
        const val DOCUMENT = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
