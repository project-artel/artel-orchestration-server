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
     * 문서 한 장이 케이스 **139개**로 앉는다.
     *
     * 그 수가 어디서 오는가:
     *
     * ```
     * 기능            491     적재기가 앉힌 행 전부
     *  → 창구           51     뷰가 `not-a-step` 과 `merged_into` 를 거른다
     *  → 케이스        139     확인할 수 있는 효과 하나마다 한 줄
     * ```
     *
     * 자기 효과를 든 갈래는 그것으로, 안 든 갈래는 **공통 호출자를 통해 빌려 온다**
     * ([MapTestCaseSiblings]). 코루틴·상태 머신에서는 입력을 받는 갈래와 결과를 내는 갈래가 다른
     * 행이라, 빌려 오지 않으면 그 조작은 케이스가 되지 못한다.
     *
     * **이 수가 줄면 케이스가 조용히 사라진 것이다.** 확인할 것이 있는데 못 내는 자리가 생겨도
     * 아무 데서도 오류가 나지 않는다.
     */
    @Test
    fun `문서 한 장이 케이스 139개가 된다`() {
        assertThat(cases).hasSize(139)
    }

    /**
     * **씬 전환을 케이스가 스스로 말한다.**
     *
     * 저작의 도달성 검사는 케이스가 끝난 뒤 어느 화면인지를 알아야 하는데, 지금은 `expected_value`
     * 산문에서 씬 이름을 찾아 추측한다(ARTEL-528). 케이스가 직접 말하면 그 추측이 사라진다.
     *
     * 구버전은 같은 문서에서 17건이 말했다. 여기는 20건이다.
     */
    @Test
    fun `씬 전환을 스스로 말하는 케이스가 스무 건이다`() {
        val moves = cases.filter { it.expected.contains("화면으로 전환된다") }

        assertThat(moves).hasSize(20)
        // 오늘 저작이 막히던 자리 — StoryScene · EndingScene 이 어디로 가는지 말한다.
        assertThat(moves.map { it.scene }.distinct()).contains("StoryScene", "EndingScene")
    }

    /**
     * **모순된 전제를 내지 않는다.**
     *
     * 갈래에서 결과를 빌려 오면 사전조건이 양쪽 조건을 함께 드는데, 둘이 모순이면 절대 만들 수 없는
     * 전제가 된다. 실측에서 실제로 나왔다 — `waitingForAcknowledge != 0` 과 `== 0` 이 한 줄에.
     * QA 담당자는 그 전제를 만들 수 없고, 만들 수 없는 것을 만들라고 적는 것이 곧 거짓 명세다.
     *
     * `ConditionOverlap` 이 그 자리를 막는다. 실측에서 4건이 걸린다(105 → 101).
     */
    @Test
    fun `같은 변수를 두고 모순되는 전제를 내지 않는다`() {
        assertThat(cases).allSatisfy { case ->
            val comparisons = case.precondition.substringAfter(" / ", "")
                .split(" 그리고 ").mapNotNull { part ->
                    Regex("""^(\S+)\s+(==|!=|>=|<=|>|<)\s+(\S+)$""").find(part.trim())
                        ?.destructured?.let { (name, op, value) -> Triple(name, op, value) }
                }
            comparisons.forEach { (name, op, value) ->
                if (op == "==") {
                    assertThat(comparisons).noneSatisfy { other ->
                        assertThat(other.first).isEqualTo(name)
                        assertThat(other.second).isEqualTo("!=")
                        assertThat(other.third).isEqualTo(value)
                    }
                }
            }
        }
    }

    /**
     * **모든 씬에서 케이스가 나온다.**
     *
     * 호출 엣지를 싣기 전에는 StoryScene 과 EndingScene 이 **0건**이었다. 그 둘의 지시 가능한
     * 기능은 `Story.StoryController.IsAdvanceKeyDown()` 뿐이고 효과가 하나도 없다 — 결과는
     * `UpdateChatStream` · `SetAnyKeyPromptVisible` · `LoadMapScene` 에 있고, 셋을 다 부르는
     * `StoryController.StoryTelling()` 코루틴만이 그것들과 입력 갈래를 잇는다.
     *
     * 하필 그 둘이 실측(런 155·158)에서 저작이 막히던 씬이다. 케이스가 없으니 저작이 무엇을 담을지
     * 몰랐다.
     *
     * ```
     * EndingScene 35 · StoryScene 35 · Map_scene 28 · GameClearScene 26
     * TitleScene 8 · TurnBattleScene 6 · GameOverScene 1
     * ```
     */
    @Test
    fun `일곱 씬 모두에서 케이스가 나온다`() {
        val byScene = cases.groupingBy { it.scene }.eachCount()

        assertThat(byScene).hasSize(7)
        assertThat(byScene["StoryScene"]).isEqualTo(35)
        assertThat(byScene["EndingScene"]).isEqualTo(35)
        assertThat(byScene["Map_scene"]).isEqualTo(28)
    }

    /**
     * **같은 조작이라도 상황이 다르면 다른 케이스다.**
     *
     * 기능을 검증하는 것 너머의 목적은 **게임이 정상 진행되는지**를 보는 것이고, 그러려면 같은 기능이
     * 여러 상황에서 제대로 도는지를 봐야 한다. 그래서 사전조건이 다르면 검증해야 할 개별 엔티티다.
     *
     * 실측에서 EndingScene 의 `press any` 하나가 이렇게 갈린다:
     *
     * ```
     * MapMove.StagePosition == 5  →  `TitleScene` 화면으로 전환된다
     * MapMove.StagePosition != 5  →  `Map_scene` 화면으로 전환된다
     * ```
     *
     * 특정 게임의 `StagePosition` 을 아는 코드는 한 줄도 없다. 호출 엣지가 이은 갈래마다 조건이
     * 다르면 줄이 갈라질 뿐이다.
     */
    @Test
    fun `같은 조작이 상황에 따라 다른 화면으로 간다`() {
        val fromEnding = cases.filter { it.scene == "EndingScene" && it.expected.contains("화면으로 전환된다") }

        // 한 조작에서 나왔는데 도착 화면이 둘 이상이다 — 그것이 갈래다.
        assertThat(fromEnding.map { it.step }.distinct()).hasSize(1)
        assertThat(fromEnding.map { it.expected }.distinct()).hasSizeGreaterThan(1)
        // 갈린 줄들은 사전조건이 서로 다르다. 같으면 어느 쪽을 만들지 알 수 없다.
        assertThat(fromEnding.map { it.precondition }.distinct()).hasSameSizeAs(fromEnding)
    }

    /**
     * **전제는 사람이 만들 수 있는 만큼만 적는다.**
     *
     * 갈래를 이으면 조건이 셋씩 겹쳐 붙는다. 그대로 두면 전제가 평균 189자까지 부풀었고, 그중 61건이
     * 200자를 넘었다 — 읽고 재현할 수 있는 분량이 아니다. 세 가지를 덜어 119자로 내렸다:
     *
     * - 호출자 조건은 **판정에만** 쓰고 문장에는 안 싣는다(코루틴이 몇 번째 대사를 넘겼는지 같은 내부
     *   진행 상태다). 모순 갈래를 거르는 데는 여전히 본다.
     * - 코드가 스스로 지금 화면을 확인하는 자리는 사전조건이 이미 그 화면을 말했다.
     * - 행동이 이미 말하는 입력 판정은 뺀다.
     *
     * 구버전은 69자다. 남은 차이는 **갈래를 잇기 때문**이고, 그것이 위 테스트가 지키는 기능이다.
     * 200자를 넘는 5건은 튜토리얼 채팅창처럼 진짜로 상태 넷이 겹치는 자리다.
     */
    @Test
    fun `전제가 읽을 수 있는 분량 안에 있다`() {
        assertThat(cases.map { it.precondition.length }.average()).isLessThan(130.0)
        assertThat(cases.count { it.precondition.length > 200 }).isLessThanOrEqualTo(5)
    }

    /**
     * **행동이 말하는 것을 전제가 다시 말하지 않는다.**
     *
     * 게임이 입력을 자기 메서드로 감싸면(`IsAdvanceKeyDown()`) 그것이 조건에 `test` 로 들어와
     * `gesture` 필터에 안 걸린다. 남기면 "`IsAdvanceKeyDown() != 0` 인 상태에서 아무 키나 누른다"가
     * 되어 같은 말을 두 번 한다.
     */
    @Test
    fun `키를 누르는 케이스의 전제에 입력 판정이 남지 않는다`() {
        assertThat(cases.filter { it.step.contains("누른다") }).allSatisfy { case ->
            assertThat(case.precondition).doesNotContain("IsAdvanceKeyDown")
        }
    }

    /**
     * **읽을 수 없는 값은 문장에 넣지 않는다.**
     *
     * 문서가 값을 못 읽은 자리를 `(not a literal)` · `(not a simple receiver)` 로 적어 둔다. 그대로
     * 내면 "표시 상태가 `(not a literal)`" 처럼 실행하는 사람이 무엇을 볼지 알 수 없는 문장이 된다.
     * 값을 빼고 "바뀐다"로 말한다 — 무엇으로 바뀌는지는 몰라도 **바뀐다는 것은 안다.**
     */
    @Test
    fun `문서가 못 읽은 값을 문장에 싣지 않는다`() {
        assertThat(cases).allSatisfy {
            assertThat(it.expected).doesNotContain("(not a")
            assertThat(it.precondition).doesNotContain("(not a")
        }
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
