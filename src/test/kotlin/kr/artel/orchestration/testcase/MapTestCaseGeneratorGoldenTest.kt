package kr.artel.orchestration.testcase

import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.runBlocking
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
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.project.storage.DocumentStorage
import kr.artel.orchestration.testcase.generator.MapTestCase
import kr.artel.orchestration.testcase.generator.MapTestCaseGenerator
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeAll
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.TestInstance
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.boot.test.context.TestConfiguration
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Primary
import org.springframework.test.context.ActiveProfiles
import java.io.File
import java.security.MessageDigest
import java.time.Instant

/**
 * 실측 근거 문서 한 장을 적재하고 **거기서 나온 케이스를 수치로 박제한다**(ARTEL-554).
 *
 * 픽스처를 만들지 않는다. 문서를 적재기에 통째로 넣고, 그 위에서 생성기를 돌린다. 이 파일이 깨지면
 * 지도와 케이스 사이의 계약이 어긋난 것이다.
 *
 * **수치를 그냥 베끼지 않는다.** 각 단언에 그 수가 어디서 오는 관계인지 적는다 — 숫자만 고치는
 * 사람이 없도록.
 */
@ActiveProfiles("test")
@SpringBootTest
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MapTestCaseGeneratorGoldenTest {

    @TestConfiguration
    class FakeStorageConfig {
        @Bean
        @Primary
        fun fakeDocumentStorage(): DocumentStorage = FakeDocumentStorage()
    }

    @Autowired private lateinit var generator: MapTestCaseGenerator
    @Autowired private lateinit var ingest: ContentMapIngestService
    @Autowired private lateinit var storage: DocumentStorage
    @Autowired private lateinit var projects: ProjectRepository
    @Autowired private lateinit var gameBuilds: GameBuildRepository
    @Autowired private lateinit var contentMaps: ContentMapRepository
    @Autowired private lateinit var documents: ContentMapDocumentRepository

    private lateinit var cases: List<MapTestCase>

    @BeforeAll
    fun `문서를 적재하고 케이스를 뽑는다`(): Unit = runBlocking {
        val now = Instant.now()
        val project = projects.save(
            ProjectEntity(name = "gen-golden", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        val build = gameBuilds.save(
            GameBuildEntity(projectId = project.id!!, version = "gen", createdAt = now, updatedAt = now)
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
        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/${map.id}/wv-editor-latest.json"
        (storage as FakeDocumentStorage).put(objectKey, bytes)
        ingest.ingest(
            documents.save(
                ContentMapDocumentEntity(
                    contentMapId = map.id!!, objectKey = objectKey,
                    contentHash = MessageDigest.getInstance("SHA-256").digest(bytes)
                        .joinToString("") { "%02x".format(it) },
                    byteSize = bytes.size.toLong(),
                )
            )
        )
        cases = generator.generate(map.id!!)

    }

    /**
     * 문서 한 장이 케이스 **35개**로 앉는다.
     *
     * 그 수가 어디서 오는가:
     *
     * ```
     * 기능            491     적재기가 앉힌 행 전부
     *  → 창구           51     뷰가 `not-a-step` 과 `merged_into` 를 거른다
     *    - 효과 없음    12     전부 `record_kind = 'flow'` — 명세 후보가 아니라 연결점이다
     *    - state 만      8     값은 바뀌는데 화면에서 확인할 수 없다
     *  = 기능           31
     *  → 케이스         35     확인할 수 있는 효과 하나마다 한 줄
     * ```
     *
     * **버리는 것이 아니다.** 20행은 `v_spec_gap` 이 세고 있고 "QA 결함이 아니라 개발 우선순위
     * 신호"로 화면에 나간다.
     *
     * **구버전과 아직 차이가 있다.** 같은 문서에서 구버전은 실행 가능한 것만 53건을 낸다:
     *
     * ```
     * 씬                구버전   여기   차이
     * Map_scene           25     14    -11   조작 없는 "진입해 관찰" 케이스가 여기 없다
     * TurnBattleScene     11      6     -5
     * EndingScene          5      0     -5   효과가 호출한 다른 기능에 달려 있다
     * StoryScene           5      0     -5   같음
     * TitleScene           5      4     -1
     * GameClearScene       2     10     +8   효과를 갈라 여기가 더 많다
     * GameOverScene        0      1     +1
     * ```
     *
     * 그 차이를 메우는 것은 ARTEL-556 의 일이다. **이 수가 줄면 케이스가 조용히 사라진 것이다.**
     */
    @Test
    fun `문서 한 장이 케이스 35개가 된다`() {
        assertThat(cases).hasSize(35)
    }

    /**
     * **두 씬은 케이스가 하나도 안 나온다.** 지어낸 것이 아니라 지도가 그렇다.
     *
     * ```
     * Map_scene 14 · GameClearScene 10 · TurnBattleScene 6 · TitleScene 4 · GameOverScene 1
     * StoryScene 0 · EndingScene 0
     * ```
     *
     * 그 둘의 지시 가능한 기능은 `Story.StoryController.IsAdvanceKeyDown()` 뿐이고 **그 기능에는
     * 효과가 달려 있지 않다.** 효과는 그것이 호출하는 다른 기능(`SetAnyKeyPromptVisible` ·
     * `LoadMapScene`)에 있고, 구버전은 호출 그래프를 타고 내려가 그것을 끌어와 케이스 5건씩을
     * 만든다. 이 생성기는 `capability_effect` 만 본다.
     *
     * 하필 그 둘이 실측(런 155·158)에서 저작이 막히던 씬이다. 케이스가 없으니 저작이 무엇을 담을지
     * 모르고, 사이를 메울 근거도 없다. **이 수가 0 을 벗어나면 그쪽이 고쳐진 것이다.**
     */
    @Test
    fun `케이스가 나오지 않는 씬이 둘이다`() {
        val byScene = cases.groupingBy { it.scene }.eachCount()

        assertThat(byScene).containsOnlyKeys(
            "Map_scene", "GameClearScene", "TurnBattleScene", "TitleScene", "GameOverScene",
        )
        assertThat(byScene["Map_scene"]).isEqualTo(14)
        assertThat(byScene["GameClearScene"]).isEqualTo(10)
    }

    /** 케이스는 전부 지도를 되짚을 수 있어야 한다 — 그것이 이 개편의 목적이다. */
    @Test
    fun `모든 케이스가 안정 키를 든다`() {
        assertThat(cases).allSatisfy { assertThat(it.capabilityKey).isNotBlank() }
        // 한 기능이 케이스 여럿일 수 있다 — 확인할 수 있는 효과마다 하나다.
        assertThat(cases.map { it.capabilityKey }.distinct()).isNotEmpty
    }

    /** 세 칸이 사용자가 보는 전부다. 하나라도 비면 읽을 수 없는 케이스가 나간다. */
    @Test
    fun `세 칸이 모두 차 있다`() {
        assertThat(cases).allSatisfy {
            assertThat(it.precondition).contains("화면인 상태")
            assertThat(it.step).isNotBlank()
            assertThat(it.expected).isNotBlank()
        }
    }

    /**
     * 실제로 나온 케이스 한 줄. 지어낸 값이 아니라 적재 결과다.
     *
     * `TurnBattleScene → GameClearScene` 은 오늘 저작이 계속 막히던 자리다. 케이스가 그 전이를
     * 스스로 말하면, 도달성 검사가 산문에서 씬 이름을 찾을 필요가 없어진다.
     */
    @Test
    fun `씬을 넘는 케이스가 어디로 가는지 말한다`() {
        val moves = cases.filter { it.expected.contains("화면으로 전환된다") }

        assertThat(moves).isNotEmpty
        assertThat(moves.map { it.expected }.distinct())
            .anySatisfy { assertThat(it).contains("화면으로 전환된다") }
    }

    /**
     * **식별자를 말로 바꾸지 않는다.** 이 규칙이 깨지면 명세가 거짓이 되고, 그 거짓은 QA 담당자가
     * 게임을 직접 확인하기 전까지 드러나지 않는다.
     */
    @Test
    fun `기대결과가 지도의 이름을 그대로 든다`() {
        assertThat(cases).allSatisfy { assertThat(it.expected).contains("`") }
    }

    /**
     * 등급은 지도가 낸 것을 그대로 옮긴다. 생성기가 다시 판단하지 않는다 — 세 축에서 유도된 값이고,
     * 여기서 고쳐 쓰면 판정이 두 곳이 된다.
     */
    @Test
    fun `등급은 지도의 세 축이 낸 값이다`() {
        assertThat(cases.map { it.status }.distinct()).isSubsetOf("runnable", "needs-probe")
    }

    private companion object {
        const val DOCUMENT = "src/test/resources/contentmap/wv-editor-latest.json"
    }
}
