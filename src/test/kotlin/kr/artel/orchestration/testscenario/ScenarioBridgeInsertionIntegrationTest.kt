package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import kotlinx.coroutines.flow.toList
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.CapabilityRepository
import kr.artel.orchestration.contentmap.repository.ContentMapRepository
import kr.artel.orchestration.contentmap.repository.SceneEdgeRepository
import kr.artel.orchestration.contentmap.repository.SceneRepository
import kr.artel.orchestration.game.entity.GameBuildEntity
import kr.artel.orchestration.game.repository.GameBuildRepository
import kr.artel.orchestration.project.entity.ProjectEntity
import kr.artel.orchestration.project.entity.ProjectMemberEntity
import kr.artel.orchestration.project.repository.ProjectMemberRepository
import kr.artel.orchestration.project.repository.ProjectRepository
import kr.artel.orchestration.testcase.entity.TestCaseEntity
import kr.artel.orchestration.testcase.repository.TestCaseRepository
import kr.artel.orchestration.testrun.entity.TestRunEntity
import kr.artel.orchestration.testrun.repository.TestRunRepository
import kr.artel.orchestration.testrun.entity.TestRunMessageEntity
import kr.artel.orchestration.testrun.repository.TestRunMessageRepository
import kr.artel.orchestration.testrun.repository.TestRunScenarioRepository
import kr.artel.orchestration.testscenario.dto.ChatScenarioStep
import kr.artel.orchestration.testscenario.dto.ScenarioResult
import kr.artel.orchestration.testscenario.dto.ScenarioStepKind
import kr.artel.orchestration.testscenario.dto.ScenarioStepSource
import kr.artel.orchestration.testscenario.entity.toDraft
import kr.artel.orchestration.testscenario.repository.TestScenarioRepository
import kr.artel.orchestration.testscenario.service.ScenarioReconcileService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 씬을 건너뛴 결과가 **저장될 때 메워지는가**(ARTEL-468).
 *
 * [ScenarioBridgeRepairTest]가 스텝 배열 규칙을 보는 곳이라면, 여기는 그 규칙이 실제 씬 명세·실제
 * 저장 경로와 이어져 있는지를 본다 — 상황 2(씬을 넷 건너뛴 결과 100% 도달 불가)가 이 경로에서
 * 실제로 사라지는지가 이 작업의 결론이기 때문이다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioBridgeInsertionIntegrationTest {

    @Autowired private lateinit var reconcileService: ScenarioReconcileService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var sceneEdgeRepository: SceneEdgeRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var runRepository: TestRunRepository
    @Autowired private lateinit var runScenarioRepository: TestRunScenarioRepository
    @Autowired private lateinit var scenarioRepository: TestScenarioRepository
    @Autowired private lateinit var runMessageRepository: TestRunMessageRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var template: org.springframework.data.r2dbc.core.R2dbcEntityTemplate

    private val seq = AtomicLong(700_000)

    private var projectId: Long = 0
    private var userId: Long = 0
    private var runId: Long = 0
    private var contentMapId: Long = 0
    private var mapSceneId: Long = 0
    private var battleSceneId: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "bridge-${seq.incrementAndGet()}",
                login = "bridge", displayName = "Bridge", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        projectId = projectRepository.save(
            ProjectEntity(name = "bridge-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!.id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        runId = runRepository.save(TestRunEntity(projectId = projectId, name = "런")).id!!
        val build = buildRepository.save(
            GameBuildEntity(
                projectId = projectId, version = "v-${seq.incrementAndGet()}",
                createdAt = now, updatedAt = now,
            )
        )
        val map = contentMapRepository.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 6, capture = "editor",
                evidenceDigest = "bridge-digest-${seq.incrementAndGet()}",
            )
        )
        contentMapId = map.id!!
        mapSceneId = sceneRepository.save(
            SceneEntity(contentMapId = map.id!!, name = "Map_scene", walked = true)
        ).id!!
        battleSceneId = sceneRepository.save(
            SceneEntity(contentMapId = map.id!!, name = "TurnBattleScene", walked = true)
        ).id!!
    }

    @Test
    fun `씬을 건너뛴 결과에 이동 스텝이 끼워져 저장된다`(): Unit = runBlocking {
        val enter = capability(mapSceneId, interaction = "press", inputKey = "Return")
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )
        val onMap = case("Map_scene", "Map_scene 화면인 상태")
        val inBattle = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        // 에이전트가 낸 것: 맵에서 확인하고 **곧바로** 전투를 확인한다. 그 사이가 비어 있다.
        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "전투 진입", description = "맵에서 전투로",
                    steps = listOf(
                        ChatScenarioStep(action = "맵을 확인한다", caseId = onMap),
                        ChatScenarioStep(action = "전투 화면을 확인한다", caseId = inBattle),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        val stored = storedSteps()
        assertThat(stored.map { it.action })
            .containsExactly("맵을 확인한다", "Return 키를 누른다 (Map_scene → TurnBattleScene)", "전투 화면을 확인한다")
        assertThat(stored[1].stepSource).isEqualTo(ScenarioStepSource.CAPABILITY)
        assertThat(stored[1].stepSourceCapabilityId).isEqualTo(enter)
        assertThat(outcome.notices).isEmpty()
    }

    @Test
    fun `길을 모르면 미상 스텝으로 남기고 사용자에게 알린다`(): Unit = runBlocking {
        // 간선을 만들지 않는다 — 전투로 들어가는 조작이 명세에 없는 상태.
        val onMap = case("Map_scene", "Map_scene 화면인 상태")
        val inBattle = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "전투 진입", description = "맵에서 전투로",
                    steps = listOf(
                        ChatScenarioStep(action = "맵을 확인한다", caseId = onMap),
                        ChatScenarioStep(action = "전투 화면을 확인한다", caseId = inBattle),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        val stored = storedSteps()
        assertThat(stored).hasSize(3)
        assertThat(stored[1].stepSource).isEqualTo(ScenarioStepSource.UNKNOWN)
        assertThat(stored[1].stepUnknownReason).isEqualTo("Map_scene→TurnBattleScene")
        // 스텝에만 있고 말하지 않으면 사용자는 모른다. 이제 **되묻는다**(ARTEL-487) — 통보로
        // 되풀이하지 않는 이유는 같은 말이 두 줄이면 어느 쪽에 답해야 하는지 알 수 없어서다.
        assertThat(outcome.question?.id).isEqualTo("gap:Map_scene→TurnBattleScene")
        assertThat(outcome.question?.text).contains("Map_scene→TurnBattleScene")
        assertThat(outcome.notices.none { it.contains("실행 방법 미상으로 두었습니다") }).isTrue()
    }

    @Test
    fun `사이에 아무것도 필요 없으면 스텝이 늘어나지 않는다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "맵 확인", description = "같은 자리",
                    steps = listOf(
                        ChatScenarioStep(action = "첫 확인", caseId = a),
                        ChatScenarioStep(action = "둘째 확인", caseId = b),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        val stored = storedSteps()
        assertThat(stored.map { it.action }).containsExactly("첫 확인", "둘째 확인")
        assertThat(stored.map { it.stepSource })
            .containsExactly(ScenarioStepSource.CASE, ScenarioStepSource.CASE)
    }

    @Test
    fun `씬 명세가 없는 프로젝트에서는 원본 그대로 저장된다`(): Unit = runBlocking {
        // 되돌리는 자리이기도 하다 — 지도가 없으면 이 기능은 없는 것과 같이 동작해야 한다.
        val now = Instant.now()
        val bare = projectRepository.save(
            ProjectEntity(name = "bare-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!.id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = bare, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val bareRun = runRepository.save(TestRunEntity(projectId = bare, name = "런")).id!!
        val a = testCaseRepository.save(
            TestCaseEntity(projectId = bare, scene = "Map_scene", step = "a", expectedValue = "e")
        ).id!!
        val b = testCaseRepository.save(
            TestCaseEntity(projectId = bare, scene = "TurnBattleScene", step = "b", expectedValue = "e")
        ).id!!

        val outcome = reconcileService.reconcile(
            bareRun, bare, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "맵", caseId = a),
                        ChatScenarioStep(action = "전투", caseId = b),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        // 지도가 없는 것은 "확인했더니 길이 없다"가 아니라 **확인을 못한 것**이다. 그 둘을 같이
        // 다루면 지도가 아직 없는 모든 프로젝트의 스텝 사이마다 "모른다" 줄이 하나씩 붙는다.
        val stored = storedSteps(bareRun)
        assertThat(stored.map { it.action }).containsExactly("맵", "전투")
        assertThat(outcome.notices).isEmpty()
    }

    @Test
    fun `없는 기능을 인용한 스텝이 있으면 저장하지 않는다`(): Unit = runBlocking {
        // 없는 케이스 번호를 지어낸 것과 같은 종류다. 실재를 안 보면 아무 숫자나 적는 것이 가장 싼
        // 통과 방법이 된다.
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "첫 확인", caseId = a),
                        ChatScenarioStep(
                            action = "어떤 기능을 쓴다",
                            stepSource = ScenarioStepSource.CAPABILITY,
                            stepSourceCapabilityId = 999_999_999L,
                        ),
                        ChatScenarioStep(action = "둘째 확인", caseId = b),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isZero()
        assertThat(outcome.rejected).isTrue()
        assertThat(outcome.findings.ungrounded.single().reason).contains("999999999")
    }

    @Test
    fun `실재하는 기능을 인용한 스텝은 통과한다`(): Unit = runBlocking {
        val real = capability(mapSceneId, interaction = "press", inputKey = "Space")
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "첫 확인", caseId = a),
                        ChatScenarioStep(
                            action = "Space 를 누른다",
                            stepSource = ScenarioStepSource.CAPABILITY,
                            stepSourceCapabilityId = real,
                        ),
                        ChatScenarioStep(action = "둘째 확인", caseId = b),
                    ),
                )
            ),
        )

        assertThat(outcome.rejected).isFalse()
        assertThat(outcome.applied).isEqualTo(1)
    }

    @Test
    fun `메울 구간이 없는 시나리오도 근거가 확정된다`(): Unit = runBlocking {
        // 스텝 하나짜리는 물어볼 구간이 없다. 그렇다고 근거를 안 박으면 그 시나리오만 출처가
        // 비어 저장되고, 검사는 통과하는데 실행하는 쪽에서 보면 무엇을 보는 스텝인지 모른다.
        val only = case("Map_scene", "Map_scene 화면인 상태")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "한 건", description = "d",
                    steps = listOf(ChatScenarioStep(action = "맵을 확인한다", caseId = only)),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        val stored = storedSteps().single()
        assertThat(stored.stepSource).isEqualTo(ScenarioStepSource.CASE)
        assertThat(stored.stepKind).isEqualTo(ScenarioStepKind.ACTION)
    }

    // ---- 고른 범위 ---------------------------------------------------------------------

    @Test
    fun `씬의 일부만 담았으면 그 비율을 알린다`(): Unit = runBlocking {
        // 요청이 애매하면 경계는 누군가 정해야 하고 지금은 모델이 조용히 정한다. 고른 결과가
        // 보이지 않는 것이 문제이지, 애매하게 물은 것이 문제가 아니다.
        val taken = case("Map_scene", "Map_scene 화면인 상태", step = "하나")
        case("Map_scene", "Map_scene 화면인 상태", step = "둘")
        case("Map_scene", "Map_scene 화면인 상태", step = "셋")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = taken)),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        assertThat(outcome.question?.id).startsWith("scope:")
        assertThat(outcome.question?.why).contains("Map_scene 1/3")
    }

    @Test
    fun `씬을 다 담았으면 범위를 말하지 않는다`(): Unit = runBlocking {
        // 고를 것이 없었으면 알릴 것도 없다. 매 턴 같은 줄이 붙으면 읽히지 않는다.
        val only = case("TitleScene", "TitleScene 화면인 상태", step = "유일")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = only)),
                )
            ),
        )

        assertThat(outcome.notices.none { it.contains("담은 범위") }).isTrue()
    }

    // ---- 검증 스텝의 조작 -------------------------------------------------------------

    @Test
    fun `검증 스텝에도 그 케이스의 조작을 채운다`(): Unit = runBlocking {
        // 브리지에는 계산된 조작이 들어가는데 검증 스텝은 비어 있었다. 무엇을 눌러 확인하는지를
        // 실행하는 쪽이 다시 추측하게 두면, 문구를 다듬을 때마다 실행이 흔들린다.
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        evidence(move, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        val target = case(
            "Map_scene", "Map_scene 화면인 상태",
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79",
        )

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(ChatScenarioStep(action = "오른쪽으로 이동해 확인한다", caseId = target)),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        assertThat(storedSteps().single().input).isEqualTo("key:RightArrow")
    }

    @Test
    fun `모델이 적어 둔 조작은 덮어쓰지 않는다`(): Unit = runBlocking {
        // 사용자가 고친 값일 수 있다.
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        evidence(move, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        val target = case(
            "Map_scene", "Map_scene 화면인 상태",
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79",
        )

        reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "확인", caseId = target, input = "key:UpArrow"),
                    ),
                )
            ),
        )

        assertThat(storedSteps().single().input).isEqualTo("key:UpArrow")
    }

    @Test
    fun `조작을 하나로 좁히지 못하면 비워 둔다`(): Unit = runBlocking {
        // 근거 키가 둘을 가리키면 어느 것인지 모른다. 채우면 그게 곧 지어내는 것이다.
        val right = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        evidence(right, "Assembly-CSharp|Map.MapMove|CharacterMove|System.Void()")
        val up = capability(mapSceneId, interaction = "press", inputKey = "UpArrow")
        evidence(up, "Assembly-CSharp|Map.MapMove|SelectStage|System.Void(System.Int32)")
        val target = case(
            "Map_scene", "Map_scene 화면인 상태",
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|CharacterMove|System.Void()@79 / " +
                "Assembly-CSharp|WordVenture.Map.MapMove|SelectStage|System.Void(System.Int32)@12",
        )

        reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "t", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = target)),
                )
            ),
        )

        assertThat(storedSteps().single().input).isNull()
    }

    // ---- 같은 자리의 케이스 -----------------------------------------------------------

    @Test
    fun `동시에 성립할 수 없는 두 케이스를 한 시나리오에 담으면 나눠 저장한다`(): Unit = runBlocking {
        // 실행이 불가능한 조합이지만 **막지 않는다**(ARTEL-497). "24건 전부 담아줘"가 실제 요청이었고,
        // 거절당한 사용자에게는 다음 수가 없었다 — 무엇을 어떤 묶음으로 볼지는 요청이 정한다.
        //
        // **묻지도 않는다.** 되묻기로 해 봤더니 답이 무엇을 뜻하는지 모델이 몰라 같은 질문이 답할
        // 때마다 다시 나갔다(런 152). 나누는 일은 계산이므로 코드가 한다.
        val notFive = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition != 5", step = "관찰한다")
        val five = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 5", step = "관찰한다")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "두 갈래", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "확인", caseId = notFive),
                        ChatScenarioStep(action = "확인", caseId = five),
                    ),
                )
            ),
        )

        // 한 벌로 왔지만 두 벌로 저장된다.
        assertThat(outcome.applied).isEqualTo(2)
        assertThat(outcome.rejected).isFalse()
        // 나눴으므로 남은 동거 불가가 없다.
        assertThat(outcome.findings.conflicting).isEmpty()
        assertThat(outcome.question?.id.orEmpty()).doesNotStartWith("conflict:")
        assertThat(outcome.notices).anyMatch { it.contains("2개로 나눴습니다") }
    }

    @Test
    fun `부등식끼리 어긋난 둘도 나눠 담는다`(): Unit = runBlocking {
        // 확정값이 없어 예전에는 통과하던 모양이다(ARTEL-497). word-venture TurnBattleScene 에서
        // 사망(hp <= 0)과 생존(hp > 0)이 한 시나리오에 담긴 것이 이 경우다.
        val dead = case("Map_scene", "Map_scene 화면인 상태 / Player.hp <= 0", step = "쓰러진 뒤 관찰한다")
        val alive = case("Map_scene", "Map_scene 화면인 상태 / Player.hp > 0", step = "버틴 뒤 관찰한다")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "생사 혼재", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "확인", caseId = dead),
                        ChatScenarioStep(action = "확인", caseId = alive),
                    ),
                )
            ),
        )

        assertThat(outcome.findings.conflicting).isEmpty()
        assertThat(outcome.applied).isEqualTo(2)
        assertThat(outcome.notices).anyMatch { it.contains("2개로 나눴습니다") }
    }

    @Test
    fun `갈래가 시나리오로 갈려 있으면 저장하고 나머지 갈래만 알린다`(): Unit = runBlocking {
        val notFive = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition != 5", step = "관찰한다")
        case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 5", step = "관찰한다")

        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "한 갈래", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = notFive)),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(1)
        // 물은 것은 통보로 되풀이하지 않는다.
        assertThat(outcome.question?.id).isEqualTo("arm:$notFive:${notFive + 1}")
        assertThat(outcome.question?.options?.map { it.id }).containsExactly("add", "skip")
    }

    /**
     * 덜 담긴 것은 **런 전체로** 본다(ARTEL-516).
     *
     * 실측(런 155): TC 66건을 전부 담아 미커버 0/66 인 화면에서 "이 갈래도 만들까요?"가 계속
     * 나왔다. 판정이 이번 턴에 쓴 시나리오만 보고 있었기 때문이다 — 나머지 갈래는 런의 다른
     * 시나리오에 이미 들어 있었다.
     */
    @Test
    fun `다른 시나리오가 이미 담은 갈래는 다시 묻지 않는다`(): Unit = runBlocking {
        val notFive = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition != 5", step = "관찰한다")
        val five = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 5", step = "관찰한다")

        // 첫 턴: 한 갈래만 담는다 → 나머지 갈래를 묻는다.
        val first = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "한 갈래", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = notFive)),
                )
            ),
        )
        assertThat(first.question?.id).isEqualTo("arm:$notFive:$five")

        // 둘째 턴: 나머지 갈래를 **새 시나리오로** 담는다. 이제 런에는 둘 다 있다.
        val second = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "다른 갈래", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = five)),
                )
            ),
        )

        assertThat(second.applied).isEqualTo(1)
        // 이번 턴만 보면 notFive 가 "빠졌다"로 보인다. 런 전체로 보면 담겨 있다.
        assertThat(second.question?.id.orEmpty()).doesNotStartWith("arm:")
        assertThat(second.notices).noneMatch { it.contains("빠졌습니다") }
    }

    /**
     * 나눠서 생긴 조각은 **원본 바로 뒤**에 놓인다(ARTEL-518).
     *
     * 새 시나리오는 런 끝에 붙는 것이 기본이고 대부분 그게 맞다. 그런데 나눠서 생긴 조각은 새로
     * 만든 것이 아니라 원본의 나머지 반쪽이다 — 실측(런 155)에서 맵 구간(position 2~8)을 나눈
     * 조각이 EndingScene 뒤인 position 21 에 놓였고, 흐름을 순서대로 읽는 화면에서 그 조각은
     * 아무 데서도 이어지지 않았다.
     */
    @Test
    fun `나눈 조각은 런 끝이 아니라 원본 바로 뒤에 놓인다`(): Unit = runBlocking {
        val dead = case("Map_scene", "Map_scene 화면인 상태 / Player.hp <= 0", step = "쓰러진 뒤 관찰한다")
        val alive = case("Map_scene", "Map_scene 화면인 상태 / Player.hp > 0", step = "버틴 뒤 관찰한다")
        val other = case("Map_scene", "Map_scene 화면인 상태", step = "그냥 관찰한다")

        // 먼저 나뉠 시나리오를 만들고, 그 뒤에 다른 시나리오를 붙여 런 끝을 차지하게 한다.
        reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "생사 혼재", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = dead)),
                )
            ),
        )
        reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    title = "맨 뒤", description = "d",
                    steps = listOf(ChatScenarioStep(action = "확인", caseId = other)),
                )
            ),
        )
        val split = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
        val origin = split.first().testScenarioId
        val last = split.last().testScenarioId

        // 이제 첫 시나리오를 고쳐 쓰면서 함께 담을 수 없는 케이스를 넣는다 → 나뉜다.
        val outcome = reconcileService.reconcile(
            runId, projectId, userId,
            listOf(
                ScenarioResult(
                    scenarioId = origin, title = "생사 혼재", description = "d",
                    steps = listOf(
                        ChatScenarioStep(action = "확인", caseId = dead),
                        ChatScenarioStep(action = "확인", caseId = alive),
                    ),
                )
            ),
        )

        assertThat(outcome.applied).isEqualTo(2)
        val order = runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList()
            .map { it.testScenarioId }
        // 원본 · 조각 · 그다음에 원래 맨 뒤였던 것.
        assertThat(order).hasSize(3)
        assertThat(order[0]).isEqualTo(origin)
        assertThat(order[2]).isEqualTo(last)
        // 자리 번호는 빈틈 없이 다시 매겨진다.
        assertThat(runScenarioRepository.findByTestRunIdOrderByPosition(runId).toList().map { it.position })
            .containsExactly(0, 1, 2)
        assertThat(outcome.notices).anyMatch { it.contains("새 시나리오 1개") }
    }

    // ---- 픽스처 ----------------------------------------------------------------------

    private suspend fun storedSteps(run: Long = runId): List<ChatScenarioStep> {
        val link = runScenarioRepository.findByTestRunIdOrderByPosition(run).toList().single()
        val steps = scenarioRepository.findById(link.testScenarioId)!!.toDraft(objectMapper).steps
        return steps.map {
            ChatScenarioStep(
                action = it.action, caseId = it.caseId, input = it.input, stepSource = it.stepSource,
                stepKind = it.stepKind,
                stepSourceCapabilityId = it.stepSourceCapabilityId, stepUnknownReason = it.stepUnknownReason,
            )
        }
    }

    private suspend fun case(
        scene: String,
        precondition: String,
        step: String = "step-${seq.incrementAndGet()}",
        evidence: String? = null,
    ): Long = testCaseRepository.save(
        TestCaseEntity(
            projectId = projectId, scene = scene, step = step,
            precondition = precondition, expectedValue = "expected",
            metadata = evidence?.let {
                Json.of(objectMapper.writeValueAsString(mapOf("source" to mapOf("evidence" to it))))
            } ?: Json.of("{}"),
        )
    ).id!!

    private suspend fun evidence(capabilityId: Long, methodId: String) {
        template.insert(
            CapabilityEvidenceEntity(
                capabilityId = capabilityId,
                entryId = "Assembly-CSharp|Map.MapMove|Update|System.Void()",
                ownerType = "Map.MapMove", method = methodId.split("|")[2], methodId = methodId,
                recordKind = "candidate", triggerKind = "lifecycle", analysisConfidence = "derived",
                conditionTree = Json.of("{}"),
                callPath = Json.of("[\"System.Void Map.MapMove::Update()\"]"),
            )
        ).awaitSingle()
    }

    private suspend fun capability(sceneId: Long, interaction: String, inputKey: String): Long =
        capabilityRepository.save(
            CapabilityEntity(
                sceneId = sceneId, contentMapId = contentMapId, origin = "evidence",
                summary = "test capability",
                interaction = interaction, inputKey = inputKey,
                inputPhase = if (interaction == "press") "down" else null,
                status = "runnable",
            )
        ).id!!


    @Test
    fun `한 번 거절한 질문은 다시 묻지 않는다`(): Unit = runBlocking {
        // 조건은 그대로라 같은 질문이 매 턴 다시 만들어진다. 그것을 그대로 내보내면 "그대로 두기"를
        // 누른 사용자에게 같은 것을 계속 묻는 셈이 된다(ARTEL-487).
        val notFive = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition != 5", step = "관찰한다")
        case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 5", step = "관찰한다")
        val one = ScenarioResult(
            title = "한 갈래", description = "d",
            steps = listOf(ChatScenarioStep(action = "확인", caseId = notFive)),
        )

        val asked = reconcileService.reconcile(runId, projectId, userId, listOf(one))
        assertThat(asked.question?.id).startsWith("arm:")

        // 사용자가 "이번엔 그대로 두기"를 눌렀다 — 대화에 답한 기록이 남는다.
        runMessageRepository.save(
            TestRunMessageEntity(
                testRunId = runId, appUserId = userId, role = "ASSISTANT",
                content = "그대로 두었습니다.",
                payload = Json.of(
                    objectMapper.writeValueAsString(mapOf("kind" to "answered", "id" to asked.question!!.id))
                ),
            )
        )

        val again = reconcileService.reconcile(runId, projectId, userId, listOf(one))

        assertThat(again.question).isNull()
        // 묻지 않는 대신 통보는 남는다 — 조건이 사라진 것이 아니라 답을 들은 것뿐이다.
        assertThat(again.notices).anyMatch { it.contains("다른 갈래") }
    }
}
