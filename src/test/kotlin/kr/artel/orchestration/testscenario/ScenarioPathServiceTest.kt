package kr.artel.orchestration.testscenario

import com.fasterxml.jackson.databind.ObjectMapper
import io.r2dbc.postgresql.codec.Json
import kotlinx.coroutines.reactor.awaitSingle
import kotlinx.coroutines.runBlocking
import kr.artel.orchestration.auth.service.OAuthIdentity
import kr.artel.orchestration.auth.service.OAuthUserService
import kr.artel.orchestration.contentmap.entity.CapabilityEffectEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEntity
import kr.artel.orchestration.contentmap.entity.CapabilityEvidenceEntity
import kr.artel.orchestration.contentmap.entity.ContentMapEntity
import kr.artel.orchestration.contentmap.entity.SceneEdgeEntity
import kr.artel.orchestration.contentmap.entity.SceneEntity
import kr.artel.orchestration.contentmap.repository.CapabilityEffectRepository
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
import kr.artel.orchestration.testscenario.service.ScenarioOrdering
import kr.artel.orchestration.testscenario.service.ScenarioPathResult
import kr.artel.orchestration.testscenario.service.ScenarioPathService
import org.assertj.core.api.Assertions.assertThat
import org.junit.jupiter.api.BeforeEach
import org.junit.jupiter.api.Test
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.test.context.SpringBootTest
import org.springframework.test.context.ActiveProfiles
import java.time.Instant
import java.util.concurrent.atomic.AtomicLong

/**
 * 경로 조회의 세 답을 **정답을 아는 사례로** 고정한다(ARTEL-466).
 *
 * word-venture 실측에서 정답이 확정된 두 전이가 기준선이다.
 * - `position 0→1` — RightArrow 하나로 옮겨진다. 사이에 무언가가 **필요하다**
 * - `StagePosition 1→2` — 전투를 클리어해야 하는데 명세에 그 규칙이 없다. **모른다**
 *
 * 이 둘이 뒤집히면 저작이 다시 도달 불가 경로를 만들기 시작한다.
 */
@ActiveProfiles("test")
@SpringBootTest
class ScenarioPathServiceTest {

    @Autowired private lateinit var service: ScenarioPathService
    @Autowired private lateinit var objectMapper: ObjectMapper
    @Autowired private lateinit var projectRepository: ProjectRepository
    @Autowired private lateinit var memberRepository: ProjectMemberRepository
    @Autowired private lateinit var buildRepository: GameBuildRepository
    @Autowired private lateinit var contentMapRepository: ContentMapRepository
    @Autowired private lateinit var sceneRepository: SceneRepository
    @Autowired private lateinit var sceneEdgeRepository: SceneEdgeRepository
    @Autowired private lateinit var capabilityRepository: CapabilityRepository
    @Autowired private lateinit var effectRepository: CapabilityEffectRepository
    @Autowired private lateinit var testCaseRepository: TestCaseRepository
    @Autowired private lateinit var oauthUserService: OAuthUserService
    @Autowired private lateinit var template: org.springframework.data.r2dbc.core.R2dbcEntityTemplate

    private val seq = AtomicLong(900_000)

    private var projectId: Long = 0
    private var userId: Long = 0
    private var contentMapId: Long = 0
    private var mapSceneId: Long = 0
    private var battleSceneId: Long = 0

    @BeforeEach
    fun fixture(): Unit = runBlocking {
        val now = Instant.now()
        userId = oauthUserService.upsert(
            OAuthIdentity(
                provider = "github", providerUserId = "path-${seq.incrementAndGet()}",
                login = "path", displayName = "Path", avatarUrl = null, email = null,
            )
        )!!.userId.toLong()
        val project = projectRepository.save(
            ProjectEntity(name = "path-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        projectId = project.id!!
        memberRepository.save(
            ProjectMemberEntity(projectId = projectId, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val build = buildRepository.save(
            GameBuildEntity(
                projectId = projectId, version = "v-${seq.incrementAndGet()}",
                createdAt = now, updatedAt = now,
            )
        )
        val map = contentMapRepository.save(
            ContentMapEntity(
                gameBuildId = build.id!!, schemaVersion = 6, capture = "editor",
                evidenceDigest = "test-digest-${seq.incrementAndGet()}",
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

    // ---- 사례 -------------------------------------------------------------------------

    @Test
    fun `가드가 어긋나지 않으면 사이에 아무것도 필요 없다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.NOT_REQUIRED)
        assertThat(answer.capabilityIds).isEmpty()
    }

    /**
     * 실측 기준선 하나. RightArrow 가 `position` 을 옮긴다는 것이 명세에 있으므로 길이 있다.
     */
    @Test
    fun `값을 옮기는 기능이 있으면 그 기능을 답한다`(): Unit = runBlocking {
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(move)
        assertThat(answer.actions.single()).contains("RightArrow")
    }

    /**
     * 실측 기준선 둘. `StagePosition` 을 올리는 규칙이 명세에 없다 —
     * 실제로는 전투를 클리어해야 하는데 그 지식이 지도에 없다. **지어내지 않고 모른다고 답해야 한다.**
     */
    @Test
    fun `값을 바꿀 수단이 없으면 무엇이 막는지와 함께 모른다고 답한다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 2")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("StagePosition")
        assertThat(answer.note).contains("명세에 없다")
    }

    /**
     * 출발 상태의 절반은 사전조건이 아니라 **그 케이스가 바꾼 값**에서 온다
     * (`metadata.source.state_after`). 그것을 읽지 않으면 이미 옮겨 놓은 상태를 모르고
     * 필요 없는 브리지를 끼우게 된다.
     */
    @Test
    fun `케이스가 바꾼 값을 출발 상태로 읽는다`(): Unit = runBlocking {
        // 사전조건은 position == 0 이지만, 이 케이스를 실행하면 1이 된다.
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0",
                     stateAfter = "MapMove.position=1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        // 이미 1이므로 사이에 할 것이 없다. state_after 를 안 읽으면 0→1 을 메우려 든다.
        assertThat(answer.result).isEqualTo(ScenarioPathResult.NOT_REQUIRED)
    }

    /**
     * 값을 **직접 쓰는** 기능이 있으면 되풀이가 아니라 그 기능 한 번이다.
     * `+1` 만 다루던 판은 이 분기를 한 번도 지나가지 않았다.
     */
    @Test
    fun `구체값을 쓰는 기능은 되풀이 없이 한 번으로 답한다`(): Unit = runBlocking {
        val reset = capability(mapSceneId, interaction = "click", label = "처음으로")
        effect(reset, target = "MapMove.position", detail = "0")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(reset)
        assertThat(answer.actions.single())
            .contains("처음으로")
            .contains("position → 0")
            .doesNotContain("되풀이")
    }

    @Test
    fun `씬 간선이 있으면 그 조작을 답한다`(): Unit = runBlocking {
        val enter = capability(mapSceneId, interaction = "press", inputKey = "Return")
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )

        val a = case("Map_scene", "Map_scene 화면인 상태")
        val b = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(enter)
        assertThat(answer.actions.single()).contains("Map_scene → TurnBattleScene")
    }

    @Test
    fun `진입 경로를 모르는 씬으로는 씬 쌍을 들어 모른다고 답한다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태")
        val b = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("Map_scene→TurnBattleScene")
    }

    /**
     * 부등식을 읽지 않던 판은 실제 사전조건의 비교 중 58%를 "충돌 없음"으로 통과시켰다.
     * `>=` 가 어긋나는 것을 잡아야 한다.
     */
    @Test
    fun `부등식 가드가 어긋나는 것도 잡는다`(): Unit = runBlocking {
        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position >= 3")

        val answer = service.findPath(projectId, userId, a, b)

        // 옮길 수단이 없으므로 모른다 — 그러나 **어긋난다는 것 자체는 잡혔다**.
        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("position")
    }

    @Test
    fun `씬 명세가 없는 프로젝트는 모른다고 답하고 예외를 던지지 않는다`(): Unit = runBlocking {
        val now = Instant.now()
        val bare = projectRepository.save(
            ProjectEntity(name = "bare-${seq.incrementAndGet()}", genre = "RPG", createdAt = now, updatedAt = now)
        )!!
        memberRepository.save(
            ProjectMemberEntity(projectId = bare.id!!, appUserId = userId, role = "OWNER", createdAt = now)
        )
        val a = testCaseRepository.save(
            TestCaseEntity(projectId = bare.id!!, scene = "Map_scene", step = "a", expectedValue = "e")
        ).id!!
        val b = testCaseRepository.save(
            TestCaseEntity(projectId = bare.id!!, scene = "Map_scene", step = "b", expectedValue = "e")
        ).id!!

        val answer = service.findPath(bare.id!!, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("content-map")
    }

    @Test
    fun `다른 프로젝트의 케이스를 물으면 답하지 않는다`(): Unit = runBlocking {
        val mine = case("Map_scene", "Map_scene 화면인 상태")
        val answer = service.findPath(projectId, userId, mine, 999_999_999L)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).startsWith("case:")
    }

    /**
     * 명세에 있다는 것과 시킬 수 있다는 것은 다른 말이다.
     *
     * 실제 지도에서 `MapMove.StagePosition` 을 올리는 것은 마지막 웨이브가 끝날 때 저절로 도는
     * 코드뿐이다(`not-a-step` · `interaction=none`). 그것을 답으로 내면 "조작 미상(none)" 이라는
     * 실행할 수 없는 스텝이 시나리오에 들어간다 — 이 작업이 없애려는 것이 바로 그것이다.
     */
    @Test
    fun `지시할 수 없는 기능이 쓴 값은 길로 치지 않는다`(): Unit = runBlocking {
        val auto = capabilityRepository.save(
            CapabilityEntity(
                sceneId = battleSceneId, contentMapId = contentMapId, origin = "evidence", summary = "웨이브가 끝나면 오른다",
                interaction = "none", status = "not-a-step",
            )
        ).id!!
        effect(auto, target = "MapMove.StagePosition", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.StagePosition == 2")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("StagePosition")
        // 없는 것과 구분해서 말해 준다 — 사용자가 채울 것이 "어떻게 하면 그 일이 일어나는가"다.
        assertThat(answer.note).contains("조작으로 지시할 수 없다")
    }

    @Test
    fun `저절로 넘어가는 씬 전이는 조작으로 답하지 않는다`(): Unit = runBlocking {
        val auto = capabilityRepository.save(
            CapabilityEntity(
                sceneId = battleSceneId, contentMapId = contentMapId, origin = "evidence", summary = "마지막 웨이브가 끝나면 넘어간다",
                interaction = "none", status = "not-a-step",
            )
        ).id!!
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = battleSceneId, toSceneName = "Map_scene",
                toSceneId = mapSceneId, capabilityId = auto, source = "static",
            )
        )
        val a = case("TurnBattleScene", "TurnBattleScene 화면인 상태")
        val b = case("Map_scene", "Map_scene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("TurnBattleScene→Map_scene")
        assertThat(answer.note).contains("저절로")
    }

    @Test
    fun `쓸 수 있는 기능이 있어도 그 값으로 못 만들면 없다고 답한다`(): Unit = runBlocking {
        // 실제 지도가 이 모양이다 — `StagePosition` 을 0 으로 되돌리는 버튼은 있고 2 로 만드는
        // 방법은 없다. 이때 "자동이라 못 시킨다"고 말하면 사실과 다른 안내가 된다.
        val reset = capability(mapSceneId, interaction = "click", label = "처음으로")
        effect(reset, target = "MapMove.StagePosition", detail = "0")

        val a = case("Map_scene", "Map_scene 화면인 상태 / StagePosition == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / StagePosition == 2")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("StagePosition")
        assertThat(answer.note).contains("명세에 없다").doesNotContain("저절로")
    }

    // ---- 조작 자신의 사전조건 --------------------------------------------------------

    /**
     * 길로 끼워 넣는 조작에도 **자기 사전조건**이 있다. 실제 지도에서 맵 조작 전부가
     * `InteractionLock.IsLocked == 0` 을 요구한다. 이것을 안 보면 코드가 스스로 실행 불가
     * 스텝을 만든다 — 없애려던 것을 다른 자리에서 만드는 셈이다.
     */
    @Test
    fun `조작 자신이 지금 못 하는 것이면 길로 답하지 않는다`(): Unit = runBlocking {
        val move = capability(
            mapSceneId, interaction = "press", inputKey = "RightArrow",
            given = "`InteractionLock.IsLocked == 0`",
        )
        effect(move, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / (MapMove.position == 0 그리고 InteractionLock.IsLocked == 1)")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("IsLocked")
        assertThat(answer.note).contains("그 조작 자신이")
    }

    /**
     * 거르는 일이자 **고르는 일**이다. 같은 변수를 쓰는 조작이 여럿이고 각자 성립 조건이 다르면
     * 지금 상태에서 실제로 되는 쪽을 집어야 한다.
     */
    @Test
    fun `같은 값을 쓰는 조작이 여럿이면 지금 되는 것을 고른다`(): Unit = runBlocking {
        val atZero = capability(
            mapSceneId, interaction = "press", inputKey = "RightArrow",
            given = "`MapMove.position == 0`",
        )
        effect(atZero, target = "MapMove.position", detail = "+1")
        val atOne = capability(
            mapSceneId, interaction = "press", inputKey = "UpArrow",
            given = "`MapMove.position == 1`",
        )
        effect(atOne, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 2")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(atOne)
        assertThat(answer.actions.single()).contains("UpArrow")
    }

    @Test
    fun `모르는 값은 조작을 막지 않는다`(): Unit = runBlocking {
        // 대부분의 기능이 InteractionLock 을 요구하는데 그 값을 아는 경우는 드물다. 모르는 것을
        // 위반으로 세면 거의 모든 길이 막힌다.
        val move = capability(
            mapSceneId, interaction = "press", inputKey = "RightArrow",
            given = "`InteractionLock.IsLocked == 0`",
        )
        effect(move, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(move)
    }

    @Test
    fun `씬을 넘는 조작이 지금 못 하는 것이면 그 조건을 들어 모른다고 답한다`(): Unit = runBlocking {
        val enter = capability(
            mapSceneId, interaction = "press", inputKey = "Return",
            given = "`InteractionLock.IsLocked == 0`",
        )
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )
        val a = case("Map_scene", "Map_scene 화면인 상태 / InteractionLock.IsLocked == 1")
        val b = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("IsLocked")
    }

    // ---- 지도가 말해 주는 값 ---------------------------------------------------------

    /**
     * **흐린 효과는 단정 근거가 못 된다**(ARTEL-478).
     *
     * `ambiguous` 는 후보를 하나로 못 좁힌 것이다. 그 값을 "이 조작이 이 값을 만든다"로 옮겨 적으면
     * 명세가 모른다고 적어 둔 것을 우리가 안다고 말하는 셈이 된다.
     */
    @Test
    fun `확실하지 않은 효과는 길로 쓰지 않는다`(): Unit = runBlocking {
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1", resolution = "ambiguous")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("position")
    }

    @Test
    fun `확실한 효과는 그대로 길이 된다`(): Unit = runBlocking {
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1", resolution = "exact")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(move)
    }

    /**
     * 되풀이해야 하는지는 **지도가 말해 준다**(ARTEL-473). 값을 직접 쓰는 기능이라도 그렇게
     * 선언돼 있으면 한 번으로 끝나지 않는다 — 우리 추론(증감이면 되풀이)보다 그쪽이 먼저다.
     */
    @Test
    fun `되풀이한다고 선언된 조작은 구체값을 써도 되풀이로 답한다`(): Unit = runBlocking {
        val press = capabilityRepository.save(
            CapabilityEntity(
                sceneId = mapSceneId, contentMapId = contentMapId, origin = "evidence",
                summary = "끝날 때까지 누른다", interaction = "press", inputKey = "Space",
                inputPhase = "down", repeatUntilDone = true,
            )
        ).id!!
        effect(press, target = "MapMove.position", detail = "3")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.actions.single()).contains("되풀이한다")
    }

    /**
     * **증감은 방향을 골라야 한다**(런 152, TS 250).
     *
     * `position` 을 1에서 3으로 올려야 하는 자리에 `LeftArrow`(-1)를 "3 이 될 때까지 되풀이한다"로
     * 적어 넣고 있었다. 먼저 걸리는 것을 집었기 때문이다. 되풀이해도 영영 도달하지 않는 스텝이고,
     * 실행하는 사람은 그 앞에서 멎는다.
     */
    @Test
    fun `증감으로 옮길 때 값을 미는 방향으로 고른다`(): Unit = runBlocking {
        val left = capability(mapSceneId, interaction = "press", inputKey = "LeftArrow")
        effect(left, target = "MapMove.position", detail = "-1")
        val right = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(right, target = "MapMove.position", detail = "+1")

        val low = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val high = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3")

        // 올려야 하면 올리는 조작.
        val up = service.findPath(projectId, userId, low, high)
        assertThat(up.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(up.capabilityIds).containsExactly(right)

        // 내려야 하면 내리는 조작.
        val down = service.findPath(projectId, userId, high, low)
        assertThat(down.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(down.capabilityIds).containsExactly(left)
    }

    @Test
    fun `미는 방향의 조작이 없으면 반대쪽을 넣지 않고 모른다고 한다`(): Unit = runBlocking {
        // 미상은 사용자가 채울 수 있지만, 거짓 스텝은 실행하다 만난다.
        val left = capability(mapSceneId, interaction = "press", inputKey = "LeftArrow")
        effect(left, target = "MapMove.position", detail = "-1")

        val low = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val high = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3")

        val answer = service.findPath(projectId, userId, low, high)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.blockedBy).isEqualTo("position")
    }

    @Test
    fun `미는 방향의 조작이 지금 못 하는 것이면 없는 것과 다르게 말한다`(): Unit = runBlocking {
        // 실측(런 153): `position` 을 2에서 3으로 올릴 자리. `RightArrow` 가 +1 을 쓰지만 지도에는
        // 그 조작의 사전조건이 `position == 0` 으로 적혀 있다. "명세에 없다"고 말하면 사용자는
        // 없는 것을 알려주려 하게 되고, 정작 손볼 자리(지도의 사전조건)는 가려진다.
        val left = capability(mapSceneId, interaction = "press", inputKey = "LeftArrow")
        effect(left, target = "MapMove.position", detail = "-1")
        val right = capability(
            mapSceneId, interaction = "press", inputKey = "RightArrow",
            given = "`MapMove.position == 0`",
        )
        effect(right, target = "MapMove.position", detail = "+1")

        val answer = service.findPath(
            projectId, userId,
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 2"),
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3"),
        )

        assertThat(answer.result).isEqualTo(ScenarioPathResult.UNKNOWN)
        assertThat(answer.note).contains("그 조작 자신이").contains("position == 0")
    }

    /**
     * 증감의 **크기를 보지 않는다.**
     *
     * `+1` 만 아는 것으로 짜여 있었는데, 추출기는 `x += 10` 을 `"+10"` 으로 낸다. 특정 게임에
     * `+1` 밖에 없어서 드러나지 않았을 뿐이고, 그 밖의 증감은 통째로 안 보여 미상으로 떨어졌다.
     */
    @Test
    fun `한 칸씩이 아니어도 증감으로 읽는다`(): Unit = runBlocking {
        val up = capability(mapSceneId, interaction = "press", inputKey = "PageUp")
        effect(up, target = "MapMove.position", detail = "+7")

        val answer = service.findPath(
            projectId, userId,
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0"),
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 21"),
        )

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(up)
        // 몇 번인지는 지어내지 않는다 — 끝 조건만 말한다.
        assertThat(answer.actions.single()).contains("되풀이한다").doesNotContain("3번")
    }

    @Test
    fun `크기가 달라도 방향은 부호가 정한다`(): Unit = runBlocking {
        val down = capability(mapSceneId, interaction = "press", inputKey = "PageDown")
        effect(down, target = "MapMove.position", detail = "-4")
        val up = capability(mapSceneId, interaction = "press", inputKey = "PageUp")
        effect(up, target = "MapMove.position", detail = "+7")

        val high = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 21")
        val low = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3")

        assertThat(service.findPath(projectId, userId, low, high).capabilityIds).containsExactly(up)
        assertThat(service.findPath(projectId, userId, high, low).capabilityIds).containsExactly(down)
    }

    /**
     * 증감을 **값을 정하는 조작으로 읽지 않는다.**
     *
     * `guard.holds("+2")` 는 `position >= 1` 에 대해 참이라, 거르지 않으면 "한 번 눌러 만족시킨다"
     * 로 읽혀 되풀이 문구가 사라진다. 한 번 눌러서 `>= 1` 이 되는지는 지금 값에 달렸다.
     */
    @Test
    fun `증감은 값을 정하는 조작이 아니다`(): Unit = runBlocking {
        val up = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(up, target = "MapMove.position", detail = "+2")

        val answer = service.findPath(
            projectId, userId,
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0"),
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position >= 1"),
        )

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.actions.single()).contains("되풀이한다")
    }

    @Test
    fun `되풀이 끝 조건은 읽을 수 있는 문장이다`(): Unit = runBlocking {
        // `position 가 == 1 가 될 때까지` 가 실제로 저장돼 있던 문구다(TS 250).
        val right = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(right, target = "MapMove.position", detail = "+1")

        val answer = service.findPath(
            projectId, userId,
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1"),
            case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 3"),
        )

        assertThat(answer.actions.single()).endsWith("position 이 3 이 될 때까지 되풀이한다")
    }

    // ---- 이미 한 조작 -----------------------------------------------------------------

    /**
     * 실측(런 32). "맵에서 Return 을 누른다"를 검증하는 케이스 바로 뒤에 같은 Return 을 누르는
     * 브리지가 붙었다 — 실행하는 사람은 같은 것을 두 번 하게 되고 화면에는 스텝이 중복돼 보인다.
     */
    @Test
    fun `출발 케이스가 이미 그 조작이면 브리지를 넣지 않는다`(): Unit = runBlocking {
        val enter = capability(mapSceneId, interaction = "press", inputKey = "Return")
        evidence(enter, "Assembly-CSharp|Map.MapMove|SelectStage|System.Void(System.Int32)")
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )
        // 이 케이스가 가리키는 코드가 곧 그 기능이다.
        val press = case(
            "Map_scene", "Map_scene 화면인 상태",
            evidence = "Assembly-CSharp|WordVenture.Map.MapMove|SelectStage|System.Void(System.Int32)@12",
        )
        val inBattle = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, press, inBattle)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.NOT_REQUIRED)
        assertThat(answer.capabilityIds).isEmpty()
    }

    @Test
    fun `다른 케이스라면 그대로 브리지를 넣는다`(): Unit = runBlocking {
        val enter = capability(mapSceneId, interaction = "press", inputKey = "Return")
        evidence(enter, "Assembly-CSharp|Map.MapMove|SelectStage|System.Void(System.Int32)")
        sceneEdgeRepository.save(
            SceneEdgeEntity(
                fromSceneId = mapSceneId, toSceneName = "TurnBattleScene",
                toSceneId = battleSceneId, capabilityId = enter, source = "static",
            )
        )
        // 근거가 없는 케이스는 판단하지 않는다 — 모르면 넣는 쪽이 안전하다. 빠뜨린 스텝은 눈에
        // 띄지만 없는 스텝은 실행할 때까지 모른다.
        val watch = case("Map_scene", "Map_scene 화면인 상태")
        val inBattle = case("TurnBattleScene", "TurnBattleScene 화면인 상태")

        val answer = service.findPath(projectId, userId, watch, inBattle)

        assertThat(answer.result).isEqualTo(ScenarioPathResult.KNOWN)
        assertThat(answer.capabilityIds).containsExactly(enter)
    }

    // ---- 순서 ------------------------------------------------------------------------

    /**
     * 실측 사례. 141번이 `position 0→1`, 142번이 `1→2`다. 뒤집어 물으면 **뒤집으면 이어진다**고
     * 답해야 한다 — 지금까지는 이 자리를 이동 스텝으로 조용히 덮었다.
     */
    @Test
    fun `뒤집으면 이어지는 두 케이스는 순서를 지적한다`(): Unit = runBlocking {
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1")

        val oneToTwo = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1",
                            stateAfter = "MapMove.position=2")
        val zeroToOne = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0",
                             stateAfter = "MapMove.position=1")

        val answer = service.findPath(projectId, userId, oneToTwo, zeroToOne)

        assertThat(answer.ordering).isEqualTo(ScenarioOrdering.REVERSED)
    }

    @Test
    fun `제대로 이어진 순서에는 아무 말도 하지 않는다`(): Unit = runBlocking {
        val zeroToOne = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0",
                             stateAfter = "MapMove.position=1")
        val oneToTwo = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1",
                            stateAfter = "MapMove.position=2")

        val answer = service.findPath(projectId, userId, zeroToOne, oneToTwo)

        assertThat(answer.ordering).isEqualTo(ScenarioOrdering.CHAINED)
        assertThat(answer.result).isEqualTo(ScenarioPathResult.NOT_REQUIRED)
    }

    @Test
    fun `실행 뒤 상태를 선언하지 않은 케이스에는 순서를 말하지 않는다`(): Unit = runBlocking {
        // 조작 효과로 after 를 유도하지 않는다 — 그 가정이 맞는다는 보장이 없다.
        val move = capability(mapSceneId, interaction = "press", inputKey = "RightArrow")
        effect(move, target = "MapMove.position", detail = "+1")

        val a = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 1")
        val b = case("Map_scene", "Map_scene 화면인 상태 / MapMove.position == 0")

        val answer = service.findPath(projectId, userId, a, b)

        assertThat(answer.ordering).isEqualTo(ScenarioOrdering.NO_OPINION)
    }

    // ---- 픽스처 ----------------------------------------------------------------------

    private suspend fun case(
        scene: String,
        precondition: String,
        stateAfter: String? = null,
        evidence: String? = null,
    ): Long {
        val source = buildMap {
            stateAfter?.let { put("state_after", it) }
            evidence?.let { put("evidence", it) }
        }
        val metadata =
            if (source.isEmpty()) Json.of("{}")
            else Json.of(objectMapper.writeValueAsString(mapOf("source" to source)))
        return testCaseRepository.save(
            TestCaseEntity(
                projectId = projectId, scene = scene, step = "step-${seq.incrementAndGet()}",
                precondition = precondition, expectedValue = "expected", metadata = metadata,
            )
        ).id!!
    }

    private suspend fun capability(
        sceneId: Long,
        interaction: String,
        inputKey: String? = null,
        label: String? = null,
        given: String? = null,
    ): Long = capabilityRepository.save(
        CapabilityEntity(
            sceneId = sceneId, contentMapId = contentMapId, origin = "evidence", summary = "test capability",
            givenText = given,
            interaction = interaction, inputKey = inputKey, controlLabel = label,
            inputPhase = if (interaction == "press") "down" else null,
            status = "runnable",
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

    private suspend fun effect(
        capabilityId: Long,
        target: String,
        detail: String?,
        resolution: String? = null,
    ) {
        effectRepository.save(
            CapabilityEffectEntity(
                capabilityId = capabilityId, category = "state", kind = "write",
                target = target, detail = detail, watchable = true, resolution = resolution,
            )
        )
    }
}
