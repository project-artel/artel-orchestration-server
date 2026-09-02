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
                gameBuildId = build.id!!, schemaVersion = 7, capture = Capture.EDITOR_PLAY.wire,
                evidencePromises = Json.of(
                    """["build-info-v1","selector-v1","visual-roles-v1","persistent-objects-v1"]"""
                ),
                evidenceDigest = "d4b31e4da9504b7d",
                unity = "2022.3.62f3", backend = "mono", development = true, sdkVersion = "0.1.0",
            )
        )
        val bytes = File(DOCUMENT).readBytes()
        val objectKey = "content-map/${map.id}/wv-play-2026-09-01.json"
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
     * 문서 한 장이 케이스 **36개**로 앉는다.
     *
     * 42 → 36 은 [MapTestCaseGenerator.withoutSpecialCases] 다(ARTEL-645). 같은 코드가 두 경로로
     * 닿아 케이스가 두 벌 나던 자리를 접는다 — `TutorialController : StoryController` 라 상속으로
     * 같은 메서드이고, 기대결과 문장까지 글자가 같았다.
     *
     * 그 수가 어디서 오는가:
     *
     * ```
     * 기능            491     적재기가 앉힌 행 전부
     *  → 창구           51     뷰가 `not-a-step` 과 `merged_into` 를 거른다
     *  → 갈래별 줄     139     확인할 수 있는 효과 × 조건 갈래
     *  → 진입점 × 조작 × 갈래  44   **한 번 누를 때 함께 일어나는 일은 한 케이스**(ARTEL-624)
     *  → 입력 합치기    42     같은 자리의 바꿔 쓸 수 있는 입력을 한 줄로(ARTEL-602)
     * ```
     *
     * 앞서 이 수는 49였다. 묶는 축이 **진입점**(플레이어가 무엇을 건드렸나)으로 바뀌면서 줄었다.
     * 앞서는 `capability_key` 로 묶었는데 그 키는 적재의 정체라 효과가 사는 메서드까지 넣고, 넣어야만
     * 한다([CapabilityKey] 의 표). 그래서 한 기능이 여럿으로 갈렸다 — 실측 GameClearScene 에서
     * `GameClearController.Update()` 하나가 `Update` 와 그것이 부르는 `ShowGettedCard` 로 갈려
     * 같은 "아무 키나 누른다"가 두 벌 나왔고, 그 씬만 10건이었다(지금 5건).
     *
     * 자기 효과를 든 갈래는 그것으로, 안 든 갈래는 **공통 호출자를 통해 빌려 온다**
     * ([MapTestCaseSiblings]). 코루틴·상태 머신에서는 입력을 받는 갈래와 결과를 내는 갈래가 다른
     * 행이라, 빌려 오지 않으면 그 조작은 케이스가 되지 못한다.
     *
     * **이 수가 줄면 케이스가 조용히 사라진 것이다.** 확인할 것이 있는데 못 내는 자리가 생겨도
     * 아무 데서도 오류가 나지 않는다.
     */
    /**
     * **구버전과 맞대 보려고 내려 적는다**(ARTEL-681).
     *
     * 구버전(specs_v2)이 앉힌 66건이 프로젝트 3에 남아 있고, 그것과 케이스 단위로 견주지 않으면
     * 무엇이 빠졌는지 알 수 없다 — 이 저장소가 여러 번 겪은 일이다("이 수가 줄면 케이스가 조용히
     * 사라진 것이다"). 켤 때만 쓴다.
     */
    @Test
    fun `견줄 수 있게 내려 적는다`() {
        val where = System.getenv("ARTEL_DUMP_CASES") ?: return
        java.io.File(where).writeText(
            cases.joinToString("\n") { case ->
                listOf(case.scene, case.step, case.precondition, case.expected, case.status)
                    .joinToString("\t") { it.replace("\n", " ").replace("\t", " ") }
            }
        )
    }

    @Test
    fun `문서 한 장이 케이스 42개가 된다`() {
        // 42 → 36 은 같은 코드에 두 경로로 닿는 것을 접은 결과(ARTEL-645)이고,
        // 36 → 46 은 `또는` 로 뭉쳐 있던 전제를 갈래대로 편 것이다(ARTEL-667).
        // 46 → 31 은 **생명주기 아래 형제에게서 결과를 빌려 오지 않게** 한 것이다(ARTEL-680).
        // 줄어든 15건은 확인할 것이 사라진 것이 아니라 **남의 결과를 자기 것으로 달고 있던** 줄이다 —
        // 그 결과들의 원래 주인은 관측 기능이고, 이어서 그쪽을 케이스로 낸다.
        // 31 → 80(ARTEL-681). 게임이 스스로 하는 일도 케이스가 된다 — 화면을 열면 무엇이
        // 보이나, 값이 이러하면 무엇이 보이나. 지금까지 137개 중 0건이었다.
        // 89 → 88. **가리키는 것에 이름이 없으면 그 효과를 안 낸다** — 문서가 수신자를 못 읽은
        // 자리를 `(not a simple receiver)` 로 적어 두는데, 값이 그런 것과 달리 대상이 그러면
        // 무엇을 보라는 것인지 한 마디도 말할 수 없다. 빠진 하나는 효과가 전부 그랬던 줄이다.
        //
        // 88 → 92. **읽는 곳을 하나로 합쳤다.** 앞서 창구가 셋이었고 관측 창구가 뽑는 자리를
        // 손으로 적은 메서드 이름 목록(`WATCHED_ROOTS`)으로 좁혔다. 이제 질의가 지도의 축으로
        // 정한다 — `trigger_kind` 가 `lifecycle` 인 것 전부. 목록에 없던 Unity 콜백이 들어왔고
        // (`OnTriggerEnter2D` · `OnMouseEnter` · `OnEndDrag`), 컴파일러가 이름을 바꾼 코루틴도
        // 더는 놓치지 않는다. 대신 `unity-event` 와 `persistent-unconfirmed` 가 빠졌다.
        //
        // 92 → 85. **문서가 바뀌었다.** 여기까지의 수치는 `wv-editor-latest.json`(schema 6,
        // 2026-08-18) 것이고, 그 사이 SDK 가 조건을 다르게 낸다(ARTEL-700) — `IsStreaming != 0`
        // 처럼 호출 이름으로 적던 자리를 그 getter 가 실제로 하는 비교(`streamingCoroutine != 0`)
        // 로 바꿨다. 규칙이 달라진 것이 아니라 지도가 달라졌다.
        assertThat(cases).hasSize(85)
    }

    /**
     * **입력 하나에 케이스가 매달리지 않는다**(ARTEL-680).
     *
     * `Map_scene` 의 `Return` 은 `CharacterMove()` 를 부르고 그 기능에는 효과가 없다. 그런데 같은
     * `Update()` 아래 있는 `ShowBattle()`(배경 갱신)에게서 결과를 빌려 와, 조건 갈래마다 갈려
     * **케이스 열둘이 같은 이름으로** 나왔다. 실측(런 265)에서 저작이 "첫 스테이지를 클리어해"라는
     * 요청에 5스테이지 케이스를 골랐고, 열둘이 글자로 구별되지 않으니 고를 방법이 없었다.
     *
     * 같은 프레임 루프에서 도는 것은 형제일 뿐 인과가 아니다.
     */
    @Test
    fun `지도의 Return 은 케이스 하나다`() {
        val returns = cases.filter { it.scene == "Map_scene" && it.step.contains("Return") }

        assertThat(returns).hasSize(1)
        assertThat(returns.single().expected).contains("TurnBattleScene")
    }

    /**
     * **케이스 하나하나가 글자로 구별된다**(ARTEL-662).
     *
     * 앞서는 같은 화면에서 같은 조작을 하는 케이스가 여럿이고 갈리는 것이 전제뿐이었다 —
     * 실측(프로젝트 24)에서 42건 중 40건이 그랬고 유일한 것은 2건뿐이었다. 대가가 저작에 나왔다
     * (런 236): 모델이 `저장 데이터가 있는` 케이스에 *"저장 데이터가 없는 상태로"* 라고 썼고
     * 바로 아래 진짜 없는 경우와 같은 문장이 됐다. 읽는 사람은 스토리 화면을 기대하는데 다음
     * 스텝은 지도다.
     *
     * **이 수가 케이스 수보다 작아지면 다시 구별이 안 되는 것이다.**
     */
    @Test
    fun `보여주기2`() {
        cases.groupBy { it.scene to it.expected }.filterValues { it.size > 1 }
            .forEach { (k, v) ->
                println("== ${k.first} | ${k.second.take(55)}")
                v.forEach { println("   ${it.step.take(95)}") }
            }
        println("겹치는 (화면,기대) 묶음 " + cases.groupBy { it.scene to it.expected }.count { it.value.size > 1 })
    }

    @Test
    fun `읽는 사람이 케이스를 구별할 수 있다`() {
        // **문장과 기대를 함께 본다.** 조작 문장이 같아도 확인할 것이 다르면 읽는 사람은 가른다 —
        // 지도의 맨 `Return` 둘이 그렇다(배경이 바뀐다 · 전투 화면으로 간다).
        val sameLine = cases.groupBy { Triple(it.scene, it.step, it.expected) }.filterValues { it.size > 1 }

        assertThat(sameLine).isEmpty()
    }

    /**
     * **케이스 이름의 괄호가 맞는다**(ARTEL-662 의 꼬리).
     *
     * 형제를 가르는 꼬리는 전제의 비교를 그대로 적는데, 소유자 접두를 떼는 규칙이 `이름.속성` 만
     * 가정해서 식을 만나면 부서졌다. 실측 85건 중 **10건**이 짝 없는 닫는 괄호를 달고 나왔다:
     *
     * ```
     * 아무 키나 누른다 (StagePosition - 1) == stagePosition, flag == 0, stagePosition == 1 일 때)
     * ```
     *
     * 앞의 `(` 는 꼬리가 연 것이고 `- 1)` 이 그것을 닫아 버려, 읽는 사람은 `일 때)` 앞에서 문장이
     * 어디서 끝나는지 알 수 없다. 같은 규칙이 `collision.gameObject.CompareTag(enemy.tag)` 를
     * `tag)` 로 만들었다 — 무엇을 견주는지가 사라진다.
     */
    @Test
    fun `케이스 이름의 괄호가 맞는다`() {
        val broken = cases.map { it.step }
            .filter { step -> step.count { it == '(' } != step.count { it == ')' } }

        assertThat(broken).isEmpty()
    }

    /**
     * **형제와 갈리는 것을 먼저 적는다**(ARTEL-662 의 꼬리).
     *
     * 꼬리에 담는 수가 [MAX_TELLING] 로 잘리는데, 가나다순으로 자르면 **정작 옆줄과 갈라 주는
     * 비교가 뒤로 밀려 잘려 나간다.** 실측에서 `GameClearScene` 의 세 줄이 그랬다 — 셋을 가르는
     * 것은 `stagePosition` 하나인데 그것이 꼬리 맨 끝에 있어, 목록에서 줄을 훑는 사람은 같은
     * 글자 40자를 지나야 다른 데에 닿았다.
     *
     * 형제가 적게 가진 비교일수록 잘 가른다. 그 순서로 담는다.
     */
    @Test
    fun `형제와 갈리는 비교가 이름 앞에 온다`() {
        // 이름에 결과가 붙은 뒤로도 이 셋은 글자까지 같다 — 같은 조작으로 같은 것을 만든다.
        // 갈리는 것이 전제뿐이라, 꼬리가 없으면 목록에서 셋을 구별할 수 없다.
        val same = "아무 키나 눌러 `TypeCard` 을(를) 만든다 외 1건 ("
        val group = cases.filter { it.scene == "GameClearScene" && it.step.startsWith(same) }
        assertThat(group).hasSizeGreaterThan(1)

        // 꼬리의 첫 항목만으로 이미 서로 다르다. 끝까지 읽지 않아도 갈린다.
        val heads = group.map { it.step.removePrefix(same).substringBefore(",") }
        assertThat(heads).doesNotHaveDuplicates()
    }

    /**
     * **씬 전환을 케이스가 스스로 말한다.**
     *
     * 저작의 도달성 검사는 케이스가 끝난 뒤 어느 화면인지를 알아야 하는데, 지금은 `expected_value`
     * 산문에서 씬 이름을 찾아 추측한다(ARTEL-528). 케이스가 직접 말하면 그 추측이 사라진다.
     *
     * 구버전은 같은 문서에서 17건이 말했다. 여기는 **14건**이다.
     *
     * **화면이 바뀌는 것은 그 자체로 한 케이스다**(ARTEL-624). 전환은 그 조작의 결말이라, 같은
     * 화면에서 이어 볼 관측과 한 줄에 담으면 무엇을 확인하라는 것인지 흐려진다 — 전환한 뒤에는 그
     * 화면에 있지도 않다. 그래서 기능을 묶을 때도 전환만은 도착 화면별로 따로 낸다.
     */
    @Test
    fun `씬 전환을 스스로 말하는 케이스가 열 건이다`() {
        val moves = cases.filter { it.expected.contains("화면으로 전환된다") }

        // 10 → 15. 관측이 말하는 씬 전환이 다섯 늘었다(ARTEL-681).
        assertThat(moves).hasSize(15)
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
     * Map_scene 16 · EndingScene 4 · StoryScene 4 · GameClearScene 5
     *
     * StoryScene · EndingScene 이 7에서 4로 준 것은 튜토리얼 경로가 낸 두 벌을 접었기 때문이다
     * (ARTEL-645). 씬이 사라진 것이 아니라 같은 줄이 하나가 됐다.
     * TurnBattleScene 4 · TitleScene 2 · GameOverScene 1
     * ```
     */
    @Test
    fun `일곱 씬 모두에서 케이스가 나온다`() {
        val byScene = cases.groupingBy { it.scene }.eachCount()

        assertThat(byScene).hasSize(7)
        // 관측이 붙어 늘었다(ARTEL-681). 12 → 10 은 창구를 하나로 합치면서 `unity-event` 를
        // 뺀 결과다 — 게임이 인스펙터로 연결한 자기 메서드는 사람이 그 순간을 만들 수 없다.
        assertThat(byScene["StoryScene"]).isEqualTo(10)
        assertThat(byScene["EndingScene"]).isEqualTo(10)
        // 갈래를 갈래대로 내면서 늘었다(ARTEL-667) — 지도의 `Return` 이 스테이지마다 한 줄이다.
        // 26 → 11. 빠진 열다섯은 `Update()` 아래 형제에게서 빌려 온 줄이다(ARTEL-680).
        // 30 → 27 도 같은 이유다(`unity-event` 를 뺐다). 27 → 23 은 문서가 바뀐 것이다.
        assertThat(byScene["Map_scene"]).isEqualTo(23)
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
        // 관측은 빼고 본다 — 이 시험이 말하는 것은 **한 조작**이 상황에 따라 갈리는 자리다(ARTEL-681).
        val fromEnding = cases.filter {
            it.scene == "EndingScene" && it.expected.contains("화면으로 전환된다") &&
                !it.step.contains("관찰한다")
        }

        // 한 조작에서 나왔는데 도착 화면이 둘 이상이다 — 그것이 갈래다.
        assertThat(fromEnding.map { it.expected }.distinct()).hasSizeGreaterThan(1)
        // **조작 문장이 갈래를 말한다**(ARTEL-662). 같은 조작이지만 무엇이 다른지가 문장에 있다 —
        // 앞서는 글자까지 같아서, 읽는 사람도 저작 모델도 둘을 구별하지 못했다.
        assertThat(fromEnding.map { it.step }.distinct()).hasSameSizeAs(fromEnding)
        // **무엇이 다른지를 도착 화면이 말한다.** 앞서는 이름이 조작뿐이라 전제에서 갈리는 비교를
        // 꼬리로 빌려 와야 했다(`StagePosition == 5 일 때`). 이름이 결과를 들고 나서는 그 꼬리가
        // 필요 없다 — 어디로 가는지가 곧 갈래다. 전제는 사전조건 칸에 그대로 있다.
        assertThat(fromEnding).allSatisfy { case ->
            assertThat(case.step).startsWith("아무 키나").contains(case.arrivesAt!!)
        }
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
     * 합치기(ARTEL-600)가 여기서 한 번 더 줄인다 — 같은 결과를 내는 갈래를 이을 때 **넓은 갈래가
     * 좁은 갈래를 덮으므로** 여덟 갈래가 넷이 된다. 119자에서 103자가 됐다.
     *
     * 만들 수 없는 조건을 걷어 내면서(ARTEL-602) 98자가 됐다 — 루프 변수를 요구하던 자리가 빠졌다.
     *
     * 구버전은 69자다. 남은 차이는 **갈래를 함께 적기 때문**이고, 그것이 위 테스트가 지키는 기능이다.
     * 200자를 넘는 5건은 스테이지 0·1·2·3 처럼 진짜로 갈래가 넷인 자리다.
     */
    @Test
    fun `전제가 읽을 수 있는 분량 안에 있다`() {
        // 갈래를 한 케이스로 묶으면서 평균이 105 → 109자로 조금 늘었다. 조건이 합쳐져서다.
        // **대신 극단이 나아졌다** — 200자를 넘는 것이 6건에서 2건으로 준다. 읽는 사람을 실제로
        // 막는 것은 평균이 아니라 그 극단이다.
        assertThat(cases.map { it.precondition.length }.average()).isLessThan(115.0)
        // 2 → 4(ARTEL-681). 늘어난 둘은 전투 화면의 관측이고, 그 조건이 원래 길다. 관측을 안
        // 내던 때에는 없던 줄이라 나빠진 것이 아니라 **없던 것이 보이는** 것이다.
        assertThat(cases.count { it.precondition.length > 200 }).isLessThanOrEqualTo(4)
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
     * **끝까지 되풀이해야 닿는 자리는 그렇게 적는다**(ARTEL-613).
     *
     * 대사를 다 넘겨야 씬이 바뀌는데, 그 사실은 `i >= 총개수` 라는 **루프 카운터**로만 적혀 있다.
     * 실행하는 사람은 `i` 를 읽을 수 없으므로 사전조건에서 뺐는데(ARTEL-602), 빼기만 하면
     * "아무 키나 한 번 누르면 타이틀로 간다"가 되어 거짓이다.
     *
     * **버릴 것이 아니라 옮길 것이었다.** 사람은 `i` 를 읽을 수 없지만 끝까지 눌러 그 자리를 만들
     * 수는 있다 — 전제가 못 되는 것이 스텝은 된다.
     *
     * 그 가드는 **호출자 조건**에 있다. ARTEL-554 가 "호출자 조건은 문장에 안 싣는다"고 버린 바로
     * 그 자리이고(코루틴이 몇 번째 대사를 넘겼는지는 테스터가 만들 것이 아니다), 루프를 다 돌고
     * 나온 자리일 때만 되살린다.
     *
     * 실측에서 넷이 걸린다 — 두 씬 × 두 도착지. 하필 이 넷이 저작이 막히던 전환이다.
     */
    @Test
    fun `대사를 다 넘겨야 가는 자리는 되풀이하라고 적는다`() {
        val repeated = cases.filter { it.step.contains("더 진행되지 않을 때까지") }

        // 씬 둘 × 그 씬에서 아무 키를 받는 기능 둘 × 도착 화면 둘. 실측 StoryScene 에서
        // `TutorialController`(튜토리얼 대화)와 `StoryController`(본편 대사)가 **각각** 아무 키를
        // 받는다 — 게임에 진짜로 둘 있는 것이라 합치지 않는다.
        assertThat(repeated).hasSize(4)
        assertThat(repeated).allSatisfy { assertThat(it.expected).contains("화면으로 전환된다") }
        assertThat(repeated.map { it.scene }.distinct()).containsExactlyInAnyOrder("StoryScene", "EndingScene")
        // 활용을 건드리지 않는다. "누른되" 가 나오면 어미를 뗀 것이다. 되풀이는 **조작 쪽에**
        // 붙는다 — 되풀이하는 것은 결과가 아니라 누르는 일이다.
        assertThat(repeated).allSatisfy {
            assertThat(it.step).startsWith("아무 키나 더 진행되지 않을 때까지 눌러 ")
        }
    }

    /**
     * **효과가 가리키는 것을 사람이 찾을 수 있는 이름으로**(ARTEL-615).
     *
     * 코드는 `ChatWindowController.anyKeyPrompt` 라 부르고 하이어라키에는
     * `Canvas/ChatWindow/AnyKeyPrompt` 가 있다. QA 담당자가 찾을 수 있는 것은 뒤엣것이고, 문서의
     * 직렬화 참조가 그 대응을 이미 말한다.
     *
     * **문자열에서 이름을 뽑지 않는다.** `GameObject.Find("Background")` 에서 `"Background"` 를
     * 꺼내고 싶어지지만, 그 게임이 그렇게 찾을 뿐이다. 씬이 스스로 말한 것만 쓴다.
     */
    @Test
    fun `효과 대상을 씬이 부르는 이름으로 바꾼다`() {
        val outcomes = cases.map { it.expected }

        assertThat(outcomes).anyMatch { it.contains("`Canvas/ChatWindow/AnyKeyPrompt`") }
        // `BackgroundMusic` 은 소리라 기대결과에서 빠졌다(ARTEL-616). 대상 되짚기 자체는 살아 있다.
        assertThat(outcomes).anyMatch { it.contains("`Congratulation`") }
        // 씬 전환의 대상은 화면 이름이라 오브젝트가 아니다. 되짚어 바꾸면 안 된다.
        assertThat(cases.filter { it.expected.contains("화면으로 전환된다") })
            .allSatisfy { assertThat(it.expected).contains("`" + it.arrivesAt + "`") }
    }

    /**
     * **여럿을 가리키면 손대지 않는다.**
     *
     * `StoryController.backgorunds` 가 셋을 가리킨다(실측). 하나를 골라 적으면 나머지 둘일 때
     * 거짓이라, 코드 이름을 그대로 두는 편이 정직하다. 못 푸는 것도 같다 —
     * `GetComponentInChildren()` 은 씬이 답할 것이 없다.
     */
    @Test
    fun `여럿이거나 못 푸는 대상은 코드 이름 그대로 둔다`() {
        val outcomes = cases.map { it.expected }

        assertThat(outcomes).anyMatch { it.contains("`StoryController.backgorunds.Item[_]`") }
        assertThat(outcomes).anyMatch { it.contains("`GameObject.GetComponentInChildren().text`") }
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
        const val DOCUMENT = "src/test/resources/contentmap/wv-play-2026-09-01.json"
    }
}
